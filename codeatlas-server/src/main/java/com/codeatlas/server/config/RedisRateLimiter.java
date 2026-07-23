package com.codeatlas.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.List;

/**
 * Redis 分布式滑动窗口限流器。
 * 使用 Lua 脚本保证原子性，Redis 不可用时降级到内存限流。
 */
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    private static final String LUA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local window_ms = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local cutoff = now - window_ms

            redis.call('ZREMRANGEBYSCORE', key, 0, cutoff)
            local count = redis.call('ZCARD', key)
            if count >= limit then
                return 0
            end
            redis.call('ZADD', key, now, now .. ':' .. count)
            redis.call('PEXPIRE', key, window_ms)
            return 1
            """;

    private static final DefaultRedisScript<Long> REDIS_SCRIPT;

    static {
        REDIS_SCRIPT = new DefaultRedisScript<>();
        REDIS_SCRIPT.setScriptText(LUA_SCRIPT);
        REDIS_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 尝试获取许可。
     *
     * @param key       限流 key（如 "rate:api:192.168.1.1:/api/v1/projects"）
     * @param maxReqs   窗口内最大请求数
     * @param windowMs  窗口时长（毫秒）
     * @return true=放行, false=限流
     */
    public boolean tryAcquire(String key, int maxReqs, long windowMs) {
        if (redisTemplate == null) {
            return true; // Redis 不可用，交给内存限流器处理
        }
        try {
            long now = System.currentTimeMillis();
            Long result = redisTemplate.execute(
                    REDIS_SCRIPT,
                    Collections.singletonList("rate:" + key),
                    String.valueOf(maxReqs),
                    String.valueOf(windowMs),
                    String.valueOf(now));
            return result != null && result == 1L;
        } catch (Exception e) {
            log.warn("Redis rate limit check failed, falling back to memory: {}", e.getMessage());
            return true; // Redis 异常时放行，交给内存限流器
        }
    }
}
