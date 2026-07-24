package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final int LOCK_DURATION_SECONDS = 900; // 15 分钟

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    public UserServiceImpl(UserMapper userMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    @Transactional
    public Map<String, Object> register(String username, String password, String email) {
        // 校验用户名
        if (username == null || username.length() < 3 || username.length() > 50) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "用户名长度 3-50 位");
        }
        if (userMapper.countByUsername(username) > 0) {
            throw new BusinessException(ErrorCode.USERNAME_EXISTS);
        }
        // 校验密码强度：至少 8 位，需包含大小写字母、数字和特殊字符中的至少三类
        if (password == null || password.length() < 8 || !isPasswordStrong(password)) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK, "密码至少 8 位，需包含大小写字母、数字和特殊字符中的至少三类");
        }
        // 校验邮箱
        if (email != null && userMapper.findByEmail(email) != null) {
            throw new BusinessException(ErrorCode.EMAIL_EXISTS);
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setRole("DEVELOPER");
        user.setStatus(1);

        userMapper.insert(user);
        log.info("User registered: id={}, username={}", user.getId(), username);

        // 注册后直接返回 token
        String token = jwtTokenProvider.generateToken(user.getId(), username, "DEVELOPER");
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", username);
        result.put("role", "DEVELOPER");
        return result;
    }

    @Override
    public Map<String, Object> login(String username, String password) {
        String lockKey = "login:lock:" + username;

        // 检查是否被锁定
        if (redisTemplate != null) {
            String locked = redisTemplate.opsForValue().get(lockKey);
            if ("LOCKED".equals(locked)) {
                Long ttl = redisTemplate.getExpire(lockKey, TimeUnit.SECONDS);
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "账户已被临时锁定，请 " + (ttl != null ? ttl / 60 + 1 : 15) + " 分钟后再试");
            }
        }

        User user = userMapper.findByUsername(username);
        if (user == null) {
            recordLoginFailure(username, lockKey);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            recordLoginFailure(username, lockKey);
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        // 登录成功，清除失败计数
        if (redisTemplate != null) {
            redisTemplate.delete("login:attempts:" + username);
            redisTemplate.delete(lockKey);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        userMapper.updateLoginTime(user.getId());
        log.info("User logged in: id={}, username={}", user.getId(), username);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
    }

    private boolean isPasswordStrong(String password) {
        if (password == null) return false;
        boolean hasUpper = false, hasLower = false, hasDigit = false, hasSpecial = false;
        for (char c : password.toCharArray()) {
            if (Character.isUpperCase(c)) hasUpper = true;
            else if (Character.isLowerCase(c)) hasLower = true;
            else if (Character.isDigit(c)) hasDigit = true;
            else hasSpecial = true;
        }
        int categories = (hasUpper ? 1 : 0) + (hasLower ? 1 : 0) + (hasDigit ? 1 : 0) + (hasSpecial ? 1 : 0);
        return categories >= 3;
    }

    private void recordLoginFailure(String username, String lockKey) {
        if (redisTemplate == null) return;
        try {
            String attemptsKey = "login:attempts:" + username;
            Long attempts = redisTemplate.opsForValue().increment(attemptsKey, 1);
            if (attempts != null && attempts == 1) {
                redisTemplate.expire(attemptsKey, LOCK_DURATION_SECONDS, TimeUnit.SECONDS);
            }
            if (attempts != null && attempts >= MAX_LOGIN_ATTEMPTS) {
                redisTemplate.opsForValue().set(lockKey, "LOCKED", LOCK_DURATION_SECONDS, TimeUnit.SECONDS);
                log.warn("Account locked due to {} failed attempts: {}", MAX_LOGIN_ATTEMPTS, username);
            }
        } catch (Exception e) {
            log.debug("Failed to record login failure: {}", e.getMessage());
        }
    }

    @Override
    public User getCurrentUser(Long userId) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    @Override
    @Transactional
    public void changePassword(Long userId, String oldPassword, String newPassword) {
        User user = userMapper.findById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "当前密码错误");
        }
        if (newPassword == null || newPassword.length() < 8 || !isPasswordStrong(newPassword)) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK, "密码至少 8 位，需包含大小写字母、数字和特殊字符中的至少三类");
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        log.info("Password changed for userId={}", userId);
    }
}
