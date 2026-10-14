package com.codeatlas.server.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CodeAtlasUserDetails 认证主体测试")
class CodeAtlasUserDetailsTest {

    @Test
    @DisplayName("构造后暴露 userId/username 并生成带 ROLE_ 前缀的权限")
    void shouldExposeUserIdUsernameAndRoleAuthority() {
        CodeAtlasUserDetails userDetails = new CodeAtlasUserDetails(7L, "alice", "ADMIN");

        assertEquals(7L, userDetails.getUserId());
        assertEquals("alice", userDetails.getUsername());

        Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
        assertEquals(1, authorities.size());
        assertEquals("ROLE_ADMIN", authorities.iterator().next().getAuthority());
    }

    @Test
    @DisplayName("权限集合精确为单元素列表，不随调用变化")
    void authoritiesShouldBeStableSingleton() {
        CodeAtlasUserDetails userDetails = new CodeAtlasUserDetails(1L, "bob", "DEVELOPER");

        assertEquals("ROLE_DEVELOPER", userDetails.getAuthorities().iterator().next().getAuthority());
        assertEquals("ROLE_DEVELOPER", userDetails.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    @DisplayName("password 返回 null（JWT 认证不携带密码）")
    void passwordShouldBeNull() {
        assertNull(new CodeAtlasUserDetails(1L, "bob", "VIEWER").getPassword());
    }

    @Test
    @DisplayName("四个账户状态标志全部为 true")
    void accountFlagsShouldAllBeTrue() {
        CodeAtlasUserDetails userDetails = new CodeAtlasUserDetails(1L, "bob", "VIEWER");

        assertTrue(userDetails.isAccountNonExpired());
        assertTrue(userDetails.isAccountNonLocked());
        assertTrue(userDetails.isCredentialsNonExpired());
        assertTrue(userDetails.isEnabled());
    }
}
