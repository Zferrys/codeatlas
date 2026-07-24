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

    private final Map<String, AtomicLong> errorCounters = new ConcurrentHashMap<>();

    @PostMapping("/frontend-error")
    public ResponseEntity<Void> reportError(@RequestBody Map<String, Object> payload) {
        try {
            String type = (String) payload.getOrDefault("type", "unknown");
            String message = (String) payload.getOrDefault("message", "");
            String url = (String) payload.getOrDefault("url", "");
            String stack = (String) payload.getOrDefault("stack", "");

            errorCounters.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();

            log.warn("Frontend error [{}] at {}: {} — stack: {}",
                    type, url, message,
                    stack != null ? stack.substring(0, Math.min(stack.length(), 500)) : "");
        } catch (Exception e) {
            log.debug("Failed to record frontend telemetry: {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
