package com.codeatlas.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * AI Token 月度预算管理器。
 * 通过 Redis 跟踪月度 Token 消耗，防止 AI 费用超标。
 * Redis 不可用时降级为不限流。
 */
@Component
public class TokenBudgetManager {

    private static final Logger log = LoggerFactory.getLogger(TokenBudgetManager.class);
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    private static final long MONTHLY_TOKEN_BUDGET = 10_000_000L; // 1000 万 token/月

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    /**
     * 消费前检查预算。返回 false 表示预算已耗尽。
     */
    public boolean tryConsume(long estimatedTokens) {
        if (redisTemplate == null) {
            return true; // Redis 不可用，不限流
        }
        try {
            String monthKey = "ai:budget:" + LocalDate.now().format(MONTH_FMT) + ":tokens";
            Long used = redisTemplate.opsForValue().increment(monthKey, estimatedTokens);
            if (used == null) {
                return true;
            }
            // 首次使用时设置过期（下月自动清零）
            if (used == estimatedTokens) {
                redisTemplate.expire(monthKey, 40, TimeUnit.DAYS);
            }
            if (used > MONTHLY_TOKEN_BUDGET) {
                redisTemplate.opsForValue().decrement(monthKey, estimatedTokens);
                log.warn("AI token monthly budget exceeded: used={}, budget={}", used, MONTHLY_TOKEN_BUDGET);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("Token budget check failed (Redis error): {}", e.getMessage());
            return true; // Redis 异常时不拦截
        }
    }

    /**
     * 获取本月使用情况，返回 [已用, 总额]
     */
    public long[] getBudgetStatus() {
        if (redisTemplate == null) {
            return new long[]{0, MONTHLY_TOKEN_BUDGET};
        }
        try {
            String monthKey = "ai:budget:" + LocalDate.now().format(MONTH_FMT) + ":tokens";
            String val = redisTemplate.opsForValue().get(monthKey);
            long used = val != null ? Long.parseLong(val) : 0;
            return new long[]{used, MONTHLY_TOKEN_BUDGET};
        } catch (Exception e) {
            return new long[]{0, MONTHLY_TOKEN_BUDGET};
        }
    }
}
