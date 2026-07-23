package com.codeatlas.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("JwtTokenProvider 单元测试")
class JwtTokenProviderTest {

    private static final String TEST_SECRET = "this-is-a-test-secret-key-at-least-32-characters";
    private static final long EXPIRATION_HOURS = 1;

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(TEST_SECRET, EXPIRATION_HOURS);
    }

    @Test
    @DisplayName("生成 token 后能正确解析 userId")
    void shouldGenerateAndParseToken() {
        String token = jwtTokenProvider.generateToken(1L, "testuser", "DEVELOPER");
        assertNotNull(token);

        Long userId = jwtTokenProvider.getUserId(token);
        assertEquals(1L, userId);
    }

    @Test
    @DisplayName("生成 token 后能正确解析 username")
    void shouldGetUsername() {
        String token = jwtTokenProvider.generateToken(2L, "alice", "ARCHITECT");
        assertEquals("alice", jwtTokenProvider.getUsername(token));
    }

    @Test
    @DisplayName("生成 token 后能正确解析 role")
    void shouldGetRole() {
        String token = jwtTokenProvider.generateToken(3L, "bob", "ADMIN");
        assertEquals("ADMIN", jwtTokenProvider.getRole(token));
    }

    @Test
    @DisplayName("有效 token 通过验证")
    void shouldValidateGoodToken() {
        String token = jwtTokenProvider.generateToken(1L, "test", "DEVELOPER");
        assertTrue(jwtTokenProvider.validateToken(token));
    }

    @Test
    @DisplayName("无效 token 不通过验证")
    void shouldRejectBadToken() {
        assertFalse(jwtTokenProvider.validateToken("invalid.token.here"));
    }

    @Test
    @DisplayName("空 token 不通过验证")
    void shouldRejectNullToken() {
        assertFalse(jwtTokenProvider.validateToken(null));
        assertFalse(jwtTokenProvider.validateToken(""));
    }

    @Test
    @DisplayName("短密钥自动补全到 32 字节")
    void shouldPadShortKey() {
        JwtTokenProvider provider = new JwtTokenProvider("short", EXPIRATION_HOURS);
        String token = provider.generateToken(1L, "test", "DEVELOPER");
        assertTrue(provider.validateToken(token));
    }

    @Test
    @DisplayName("空密钥抛异常")
    void shouldThrowOnBlankSecret() {
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("", 24));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider(null, 24));
        assertThrows(IllegalStateException.class, () -> new JwtTokenProvider("   ", 24));
    }

    @Test
    @DisplayName("修改过的 token 不通过验证")
    void shouldRejectTamperedToken() {
        String token = jwtTokenProvider.generateToken(1L, "testuser", "DEVELOPER");
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertFalse(jwtTokenProvider.validateToken(tampered));
    }
}
