package com.codeatlas.server.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 前端遥测端点 — 匿名收集前端错误和性能数据。
 * 无需认证，不对用户做身份关联。
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private static final Logger log = LoggerFactory.getLogger(TelemetryController.class);

    /** 单字段最大长度，防止匿名端点被超长报文灌爆日志 */
    private static final int MAX_TYPE_LENGTH = 64;
    private static final int MAX_TEXT_LENGTH = 500;
    /** type 维度基数上限，防止匿名端点无限撑大内存计数器 */
    private static final int MAX_TYPE_KEYS = 500;

    private final Map<String, AtomicLong> errorCounters = new ConcurrentHashMap<>();

    @PostMapping("/frontend-error")
    public ResponseEntity<Void> reportError(@RequestBody Map<String, Object> payload) {
        try {
            String type = truncate((String) payload.getOrDefault("type", "unknown"), MAX_TYPE_LENGTH);
            if (type.isEmpty()) {
                type = "unknown";
            }
            String message = truncate((String) payload.getOrDefault("message", ""), MAX_TEXT_LENGTH);
            String url = truncate((String) payload.getOrDefault("url", ""), MAX_TEXT_LENGTH);
            String stack = truncate((String) payload.getOrDefault("stack", ""), MAX_TEXT_LENGTH);

            if (errorCounters.size() < MAX_TYPE_KEYS || errorCounters.containsKey(type)) {
                errorCounters.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
            }

            log.warn("Frontend error [{}] at {}: {} — stack: {}", type, url, message, stack);
        } catch (Exception e) {
            log.debug("Failed to record frontend telemetry: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }
}
