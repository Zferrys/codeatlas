package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

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
        // 校验密码强度
        if (password == null || password.length() < 6) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
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
        User user = userMapper.findByUsername(username);
        if (user == null) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            throw new BusinessException(ErrorCode.USER_DISABLED);
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.LOGIN_FAILED);
        }

        String token = jwtTokenProvider.generateToken(user.getId(), user.getUsername(), user.getRole());
        log.info("User logged in: id={}, username={}", user.getId(), username);

        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("role", user.getRole());
        return result;
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
        if (newPassword == null || newPassword.length() < 6) {
            throw new BusinessException(ErrorCode.PASSWORD_TOO_WEAK);
        }
        userMapper.updatePassword(userId, passwordEncoder.encode(newPassword));
        log.info("Password changed for userId={}", userId);
    }
}
