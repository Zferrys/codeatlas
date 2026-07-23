package com.codeatlas.server.security;

import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserDetailsServiceImpl 单元测试")
class UserDetailsServiceImplTest {

    @Mock
    private UserMapper userMapper;

    private UserDetailsServiceImpl userDetailsService;

    @BeforeEach
    void setUp() {
        userDetailsService = new UserDetailsServiceImpl(userMapper);
    }

    @Test
    @DisplayName("正常用户加载成功")
    void shouldLoadUserByUsername() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setRole("DEVELOPER");
        user.setStatus(1);

        when(userMapper.findByUsername("testuser")).thenReturn(user);

        UserDetails details = userDetailsService.loadUserByUsername("testuser");

        assertEquals("testuser", details.getUsername());
        assertEquals(1L, ((CodeAtlasUserDetails) details).getUserId());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_DEVELOPER")));
    }

    @Test
    @DisplayName("不存在的用户抛异常")
    void shouldThrowWhenUserNotFound() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("nobody"));
    }

    @Test
    @DisplayName("被禁用的用户抛异常")
    void shouldThrowWhenUserDisabled() {
        User user = new User();
        user.setId(2L);
        user.setUsername("disabled");
        user.setRole("DEVELOPER");
        user.setStatus(0);

        when(userMapper.findByUsername("disabled")).thenReturn(user);

        assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("disabled"));
    }

    @Test
    @DisplayName("null status 的用户抛异常")
    void shouldThrowWhenStatusNull() {
        User user = new User();
        user.setId(3L);
        user.setUsername("noStatus");
        user.setRole("DEVELOPER");
        user.setStatus(null);

        when(userMapper.findByUsername("noStatus")).thenReturn(user);

        assertThrows(UsernameNotFoundException.class,
                () -> userDetailsService.loadUserByUsername("noStatus"));
    }
}
