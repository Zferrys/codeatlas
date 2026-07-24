package com.codeatlas.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;

/**
 * JWT Token 黑名单服务。使用 Redis 存储已撤销的 token，支持服务端主动失效。
 * Key 使用 SHA-256 哈希避免特殊字符问题，TTL 对齐 token 剩余有效期自动清理。
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** 将 token 加入黑名单，有效期为其剩余过期时间 */
    public void blacklist(String token, long expiresAtMillis) {
        try {
            long ttlSeconds = Math.max(1, (expiresAtMillis - System.currentTimeMillis()) / 1000);
            String key = BLACKLIST_PREFIX + sha256(token);
            redisTemplate.opsForValue().set(key, "1", Duration.ofSeconds(ttlSeconds));
            log.debug("Token blacklisted: expires in {}s", ttlSeconds);
        } catch (Exception e) {
            log.warn("Failed to blacklist token (Redis unavailable, token may remain valid): {}", e.getMessage());
        }
    }

    /** 检查 token 是否在黑名单中 */
    public boolean isBlacklisted(String token) {
        try {
            String key = BLACKLIST_PREFIX + sha256(token);
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.debug("Token blacklist check failed (Redis unavailable, allowing): {}", e.getMessage());
            return false;
        }
    }

    /** 获取 token 过期时间戳（毫秒），用于设置黑名单 TTL */
    public static long getExpiresAtMillis(String token) {
        // JWT expiration is embedded in the token; parsed by JwtTokenProvider
        // This is a convenience method — caller should use JwtTokenProvider for actual parsing
        return 0;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
