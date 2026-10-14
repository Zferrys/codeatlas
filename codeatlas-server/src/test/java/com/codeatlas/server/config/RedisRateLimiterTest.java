package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisRateLimiter 单元测试（Lua 脚本参数 + 失败开放语义）")
class RedisRateLimiterTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    private static DefaultRedisScript<Long> scriptFromClass() throws Exception {
        Field field = RedisRateLimiter.class.getDeclaredField("REDIS_SCRIPT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(null);
        return script;
    }

    @Test
    @DisplayName("redisTemplate 为 null：直接放行（交给内存限流器）")
    void nullTemplate_allows() {
        RedisRateLimiter limiter = new RedisRateLimiter(null);

        assertTrue(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("脚本返回 1：放行")
    void scriptReturnsOne_allows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

        assertTrue(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("脚本返回 0：限流")
    void scriptReturnsZero_denies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

        assertFalse(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("脚本返回 null：按拒绝处理（仅 1 放行）")
    void scriptReturnsNull_denies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

        assertFalse(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("脚本返回非 0/1（如 2）：按拒绝处理（仅 1 放行）")
    void scriptReturnsOtherValue_denies() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(2L);
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

        assertFalse(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("Redis 异常：失败开放放行（交给内存限流器）")
    void redisFailure_failsOpen() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);

        assertTrue(limiter.tryAcquire("ip:/api/x", 10, 60_000L));
    }

    @Test
    @DisplayName("传递给 Redis 的脚本/键/参数精确（rate: 前缀 + 上限 + 窗口 + 当前毫秒）")
    void tryAcquire_passesExactScriptKeyAndArguments() throws Exception {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        RedisRateLimiter limiter = new RedisRateLimiter(redisTemplate);
        long before = System.currentTimeMillis();

        assertTrue(limiter.tryAcquire("ip:/api/x", 7, 123_456L));

        long after = System.currentTimeMillis();
        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> maxCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> windowCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nowCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(),
                maxCaptor.capture(), windowCaptor.capture(), nowCaptor.capture());

        assertSame(scriptFromClass(), scriptCaptor.getValue());
        assertEquals(Collections.singletonList("rate:ip:/api/x"), keysCaptor.getValue());
        assertEquals("7", maxCaptor.getValue());
        assertEquals("123456", windowCaptor.getValue());
        long nowArg = Long.parseLong(nowCaptor.getValue());
        assertTrue(nowArg >= before && nowArg <= after, "now 参数应取自调用时刻");
    }

    @Test
    @DisplayName("Lua 脚本内容包含关键指令（去重清理/计数/写入/过期/放行）")
    void luaScript_containsCoreOperations() throws Exception {
        String text = scriptFromClass().getScriptAsString();

        assertTrue(text.contains("ZREMRANGEBYSCORE"));
        assertTrue(text.contains("ZCARD"));
        assertTrue(text.contains("count >= limit"));
        assertTrue(text.contains("ZADD"));
        assertTrue(text.contains("PEXPIRE"));
        assertTrue(text.contains("return 1"));
    }
}
