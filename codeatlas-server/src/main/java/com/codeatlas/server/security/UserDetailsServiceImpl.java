package com.codeatlas.server.security;

import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    private final UserMapper userMapper;

    public UserDetailsServiceImpl(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.findByUsername(username);
        if (user == null) {
            log.warn("User not found: {}", username);
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        if (user.getStatus() == null || user.getStatus() == 0) {
            log.warn("User disabled: {}", username);
            throw new UsernameNotFoundException("用户已被禁用: " + username);
        }
        return new CodeAtlasUserDetails(user.getId(), user.getUsername(), user.getRole());
    }
}
