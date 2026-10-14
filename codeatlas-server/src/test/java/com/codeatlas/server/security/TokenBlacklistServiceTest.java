package com.codeatlas.server.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBlacklistService JWT 黑名单服务测试")
class TokenBlacklistServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private TokenBlacklistService service;

    @BeforeEach
    void setUp() {
        service = new TokenBlacklistService(redisTemplate);
    }

    /** 与生产实现同算法的 SHA-256 十六进制摘要，用于断言 key 格式 */
    private static String sha256(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    @DisplayName("blacklist 用 SHA-256 哈希 key 并按剩余有效期写入 Redis TTL")
    void blacklistShouldStoreHashedKeyWithRemainingTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        long expiresAtMillis = System.currentTimeMillis() + 600_000L; // 10 分钟后过期

        service.blacklist("jwt-token", expiresAtMillis);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("1"), ttlCaptor.capture());
        assertEquals("jwt:blacklist:" + sha256("jwt-token"), keyCaptor.getValue());
        long ttlSeconds = ttlCaptor.getValue().getSeconds();
        assertTrue(ttlSeconds >= 595 && ttlSeconds <= 600,
                "TTL 应为剩余有效期秒数（约 600s），实际=" + ttlSeconds);
    }

    @Test
    @DisplayName("blacklist 对已过期 token 把 TTL 钳制为 1 秒（Math.max(1, ...)）")
    void blacklistShouldClampExpiredTokenTtlToOneSecond() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        long expiresAtMillis = System.currentTimeMillis() - 120_000L; // 已过期 2 分钟

        service.blacklist("expired-token", expiresAtMillis);

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(keyCaptor.capture(), eq("1"), ttlCaptor.capture());
        assertEquals("jwt:blacklist:" + sha256("expired-token"), keyCaptor.getValue());
        assertEquals(Duration.ofSeconds(1), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("blacklist 对恰好到期的 token 同样钳制为 1 秒（0 秒取 max）")
    void blacklistShouldClampZeroRemainingToPositiveTtl() throws Exception {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        service.blacklist("now-token", System.currentTimeMillis());

        ArgumentCaptor<Duration> ttlCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(anyString(), eq("1"), ttlCaptor.capture());
        assertEquals(Duration.ofSeconds(1), ttlCaptor.getValue());
    }

    @Test
    @DisplayName("Redis 不可用时 blacklist 吞掉异常不中断登出流程")
    void blacklistShouldSwallowRedisFailure() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));

        assertDoesNotThrow(() -> service.blacklist("token", System.currentTimeMillis() + 60_000L));
    }

    @Test
    @DisplayName("isBlacklisted 在 Redis 命中时返回 true 并查询哈希后的 key")
    void isBlacklistedShouldReturnTrueWhenKeyExists() throws Exception {
        when(redisTemplate.hasKey("jwt:blacklist:" + sha256("token-a"))).thenReturn(Boolean.TRUE);

        assertTrue(service.isBlacklisted("token-a"));
    }

    @Test
    @DisplayName("isBlacklisted 在 Redis 未命中（false/null）时返回 false")
    void isBlacklistedShouldReturnFalseWhenKeyAbsent() throws Exception {
        when(redisTemplate.hasKey("jwt:blacklist:" + sha256("token-b"))).thenReturn(Boolean.FALSE);
        assertFalse(service.isBlacklisted("token-b"));

        when(redisTemplate.hasKey("jwt:blacklist:" + sha256("token-c"))).thenReturn(null);
        assertFalse(service.isBlacklisted("token-c"));
    }

    @Test
    @DisplayName("Redis 不可用时 isBlacklisted 放行（返回 false 而非抛异常）")
    void isBlacklistedShouldFailOpenOnRedisFailure() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RuntimeException("redis down"));

        assertFalse(service.isBlacklisted("any-token"));
    }

    @Test
    @DisplayName("getExpiresAtMillis 是固定返回 0 的占位实现")
    void getExpiresAtMillisShouldReturnZeroPlaceholder() {
        assertEquals(0L, TokenBlacklistService.getExpiresAtMillis("irrelevant"));
    }

    @Test
    @DisplayName("SHA-256 算法不可用（JDK 防御分支）：sha256 抛出携带原始原因的 RuntimeException")
    void sha256_whenAlgorithmUnavailable_shouldThrowRuntimeException() throws Exception {
        Method sha256Method = TokenBlacklistService.class.getDeclaredMethod("sha256", String.class);
        sha256Method.setAccessible(true);

        try (MockedStatic<MessageDigest> mockedDigest = mockStatic(MessageDigest.class)) {
            mockedDigest.when(() -> MessageDigest.getInstance("SHA-256"))
                    .thenThrow(new NoSuchAlgorithmException("SHA-256 not available"));

            InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                    () -> sha256Method.invoke(null, "any-token"));

            assertInstanceOf(RuntimeException.class, thrown.getCause());
            assertEquals("SHA-256 not available", thrown.getCause().getMessage());
            assertInstanceOf(NoSuchAlgorithmException.class, thrown.getCause().getCause());
        }
    }
}
