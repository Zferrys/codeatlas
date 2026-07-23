package com.codeatlas.server.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 滑动窗口限流拦截器。
 * 记录每个请求的时间戳，在窗口内计数，过期时间戳自动清理。
 * 生产环境建议升级为 Redis + Lua 脚本方案以获得分布式一致性。
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RateLimitInterceptor.class);

    /** key → 请求时间戳队列 */
    private static final Map<String, ConcurrentLinkedDeque<Long>> WINDOWS = new ConcurrentHashMap<>();

    /** 每个窗口允许的最大请求数 */
    private final int maxRequests;
    /** 滑动窗口时长（毫秒） */
    private final long windowMs;
    /** 最大容量，防止恶意填满内存 */
    private static final int MAX_QUEUE_SIZE = 200;

    public RateLimitInterceptor(int maxRequestsPerMinute) {
        this.maxRequests = maxRequestsPerMinute;
        this.windowMs = 60_000L;
    }

    public RateLimitInterceptor(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        String clientIp = getClientIp(request);
        String key = clientIp + ":" + normalizePath(path);

        long now = System.currentTimeMillis();
        long cutoff = now - windowMs;

        ConcurrentLinkedDeque<Long> timestamps = WINDOWS.computeIfAbsent(
                key, k -> new ConcurrentLinkedDeque<>());

        // 清理过期时间戳
        while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
            timestamps.pollFirst();
        }

        // 容量保护：如果队列异常膨胀，直接拒绝
        if (timestamps.size() >= MAX_QUEUE_SIZE) {
            log.warn("Rate limit queue overflow: ip={}, path={}, size={}", clientIp, path, timestamps.size());
            sendRateLimitResponse(response);
            return false;
        }

        timestamps.addLast(now);

        if (timestamps.size() > maxRequests) {
            log.warn("Rate limit exceeded: ip={}, path={}, count={}, limit={}",
                    clientIp, path, timestamps.size(), maxRequests);
            sendRateLimitResponse(response);
            return false;
        }

        return true;
    }

    private void sendRateLimitResponse(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}");
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    private String normalizePath(String path) {
        return path.replaceAll("/\\d+", "/{id}");
    }
}
