package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl 单元测试")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, jwtTokenProvider);
    }

    // ========== register ==========

    @Test
    @DisplayName("注册成功返回 token")
    void shouldRegisterSuccessfully() {
        when(userMapper.countByUsername("newuser")).thenReturn(0);
        when(passwordEncoder.encode("123456")).thenReturn("hashed_pw");

        // 模拟 MyBatis 自动设置主键
        doAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(10L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        when(jwtTokenProvider.generateToken(eq(10L), eq("newuser"), eq("DEVELOPER")))
                .thenReturn("jwt_token_xxx");

        Map<String, Object> result = userService.register("newuser", "123456", "test@test.com");

        assertEquals("jwt_token_xxx", result.get("token"));
        assertEquals("newuser", result.get("username"));
        assertEquals("DEVELOPER", result.get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("用户名太短抛异常")
    void shouldRejectShortUsername() {
        assertThrows(BusinessException.class,
                () -> userService.register("ab", "123456", null));
    }

    @Test
    @DisplayName("用户名已存在抛异常")
    void shouldRejectDuplicateUsername() {
        when(userMapper.countByUsername("exist")).thenReturn(1);

        assertThrows(BusinessException.class,
                () -> userService.register("exist", "123456", null));
    }

    @Test
    @DisplayName("密码太短抛异常")
    void shouldRejectWeakPassword() {
        when(userMapper.countByUsername("test")).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> userService.register("test", "12345", null));
    }

    // ========== login ==========

    @Test
    @DisplayName("登录成功返回 token")
    void shouldLoginSuccessfully() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed_pw");
        user.setRole("DEVELOPER");
        user.setStatus(1);

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("123456", "hashed_pw")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "testuser", "DEVELOPER"))
                .thenReturn("jwt_token_xxx");

        Map<String, Object> result = userService.login("testuser", "123456");

        assertEquals("jwt_token_xxx", result.get("token"));
        assertEquals(1L, result.get("userId"));
    }

    @Test
    @DisplayName("用户不存在登录失败")
    void shouldFailLoginOnWrongUsername() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> userService.login("nobody", "123456"));
    }

    @Test
    @DisplayName("密码错误登录失败")
    void shouldFailLoginOnWrongPassword() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed_pw");
        user.setStatus(1);

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hashed_pw")).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> userService.login("testuser", "wrong"));
    }

    @Test
    @DisplayName("被禁用用户登录失败")
    void shouldFailLoginOnDisabledUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("disabled");
        user.setPasswordHash("hashed_pw");
        user.setStatus(0);

        when(userMapper.findByUsername("disabled")).thenReturn(user);

        assertThrows(BusinessException.class,
                () -> userService.login("disabled", "123456"));
    }

    // ========== getCurrentUser ==========

    @Test
    @DisplayName("获取当前用户信息")
    void shouldGetCurrentUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");

        when(userMapper.findById(1L)).thenReturn(user);

        User result = userService.getCurrentUser(1L);
        assertEquals("testuser", result.getUsername());
    }

    @Test
    @DisplayName("获取不存在的用户抛异常")
    void shouldThrowOnMissingUser() {
        when(userMapper.findById(999L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> userService.getCurrentUser(999L));
    }
}
