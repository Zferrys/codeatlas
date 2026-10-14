package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TokenBudgetManager 单元测试（Lua 原子扣减 + Redis 降级/异常容错）")
class TokenBudgetManagerTest {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final long MONTHLY_TOKEN_BUDGET = 10_000_000L;

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private static String currentMonthKey() {
        return "ai:budget:" + LocalDate.now().format(MONTH_FMT) + ":tokens";
    }

    private TokenBudgetManager managerWithRedis() {
        TokenBudgetManager manager = new TokenBudgetManager();
        ReflectionTestUtils.setField(manager, "redisTemplate", redisTemplate);
        return manager;
    }

    @Test
    @DisplayName("无 Redis：tryConsume 恒放行，状态返回 [0, 总额]")
    void noRedis_allowsAndReportsZeroUsage() {
        TokenBudgetManager manager = new TokenBudgetManager();

        assertTrue(manager.tryConsume(999_999L));
        assertArrayEquals(new long[]{0L, MONTHLY_TOKEN_BUDGET}, manager.getBudgetStatus());
    }

    @Test
    @DisplayName("脚本返回 1：放行，且键/参数精确（月键 + tokens + 40 天 TTL + 总额）")
    void tryConsume_allowed_passesExactKeyAndArguments() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(1L);
        TokenBudgetManager manager = managerWithRedis();
        String expectedKey = currentMonthKey();

        assertTrue(manager.tryConsume(150L));

        ArgumentCaptor<RedisScript<Long>> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> tokensCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ttlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> budgetCaptor = ArgumentCaptor.forClass(String.class);

        verify(redisTemplate).execute(scriptCaptor.capture(), keysCaptor.capture(),
                tokensCaptor.capture(), ttlCaptor.capture(), budgetCaptor.capture());

        assertEquals(Collections.singletonList(expectedKey), keysCaptor.getValue());
        assertEquals("150", tokensCaptor.getValue());
        assertEquals(String.valueOf(TimeUnit.DAYS.toSeconds(40)), ttlCaptor.getValue());
        assertEquals("3456000", ttlCaptor.getValue());
        assertEquals("10000000", budgetCaptor.getValue());
    }

    @Test
    @DisplayName("脚本返回 0：预算耗尽拒绝")
    void tryConsume_deniedWhenBudgetExhausted() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(0L);
        TokenBudgetManager manager = managerWithRedis();

        assertFalse(manager.tryConsume(500_000L));
    }

    @Test
    @DisplayName("脚本返回 null：视为不限流放行")
    void tryConsume_nullResult_allows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);
        TokenBudgetManager manager = managerWithRedis();

        assertTrue(manager.tryConsume(500L));
    }

    @Test
    @DisplayName("Redis 异常：不拦截（放行）")
    void tryConsume_redisException_allows() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any()))
                .thenThrow(new RuntimeException("redis down"));
        TokenBudgetManager manager = managerWithRedis();

        assertTrue(manager.tryConsume(500L));
    }

    @Test
    @DisplayName("状态查询：读取本月键返回已用/总额")
    void budgetStatus_readsMonthlyUsage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("12345");
        TokenBudgetManager manager = managerWithRedis();

        assertArrayEquals(new long[]{12345L, MONTHLY_TOKEN_BUDGET}, manager.getBudgetStatus());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).get(keyCaptor.capture());
        assertEquals(currentMonthKey(), keyCaptor.getValue());
    }

    @Test
    @DisplayName("状态查询：键不存在时已用为 0")
    void budgetStatus_nullValue_zeroUsage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);
        TokenBudgetManager manager = managerWithRedis();

        assertArrayEquals(new long[]{0L, MONTHLY_TOKEN_BUDGET}, manager.getBudgetStatus());
    }

    @Test
    @DisplayName("状态查询：值非法（非数字）时容错返回 0")
    void budgetStatus_invalidValue_zeroUsage() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("not-a-number");
        TokenBudgetManager manager = managerWithRedis();

        assertArrayEquals(new long[]{0L, MONTHLY_TOKEN_BUDGET}, manager.getBudgetStatus());
    }

    @Test
    @DisplayName("状态查询：Redis 访问异常时容错返回 [0, 总额]")
    void budgetStatus_redisException_zeroUsage() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("redis down"));
        TokenBudgetManager manager = managerWithRedis();

        assertArrayEquals(new long[]{0L, MONTHLY_TOKEN_BUDGET}, manager.getBudgetStatus());
    }

    @Test
    @DisplayName("Lua 脚本内容包含原子扣减关键指令（INCRBY/EXPIRE/DECRBY）")
    void luaScript_containsAtomicOperations() throws Exception {
        Field field = TokenBudgetManager.class.getDeclaredField("tryConsumeScript");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(new TokenBudgetManager());
        String text = script.getScriptAsString();

        assertTrue(text.contains("INCRBY"));
        assertTrue(text.contains("EXPIRE"));
        assertTrue(text.contains("DECRBY"));
        assertTrue(text.contains("return 0"));
        assertTrue(text.contains("return 1"));
    }
}
