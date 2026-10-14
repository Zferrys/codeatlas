package com.codeatlas.server.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TelemetryController 单元测试")
class TelemetryControllerTest {

    private TelemetryController controller;

    @BeforeEach
    void setUp() {
        controller = new TelemetryController();
    }

    @SuppressWarnings("unchecked")
    private Map<String, AtomicLong> counters() {
        return (Map<String, AtomicLong>) ReflectionTestUtils.getField(controller, "errorCounters");
    }

    private static Map<String, Object> payload(String type, String message, String url, String stack) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("message", message);
        body.put("url", url);
        body.put("stack", stack);
        return body;
    }

    @Test
    @DisplayName("reportError — 正常上报计数 +1 并返回 200")
    void reportErrorCountsByType() {
        ResponseEntity<Void> response = controller.reportError(
                payload("TypeError", "boom", "http://a/b", "at x"));

        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, counters().get("TypeError").get());
    }

    @Test
    @DisplayName("reportError — 同类型多次上报累加")
    void reportErrorAccumulates() {
        controller.reportError(payload("TypeError", "a", "u", "s"));
        controller.reportError(payload("TypeError", "b", "u", "s"));

        Map<String, AtomicLong> counters = counters();
        assertEquals(1, counters.size());
        assertEquals(2L, counters.get("TypeError").get());
    }

    @Test
    @DisplayName("reportError — 缺少 type 时归入 unknown")
    void reportErrorDefaultsToUnknownWhenTypeMissing() {
        controller.reportError(new HashMap<>());

        assertEquals(1L, counters().get("unknown").get());
    }

    @Test
    @DisplayName("reportError — type 为空串时归入 unknown")
    void reportErrorDefaultsToUnknownWhenTypeEmpty() {
        controller.reportError(payload("", "m", "u", "s"));

        assertEquals(1L, counters().get("unknown").get());
        assertFalse(counters().containsKey(""));
    }

    @Test
    @DisplayName("reportError — type 显式为 null 时归入 unknown")
    void reportErrorDefaultsToUnknownWhenTypeNull() {
        controller.reportError(payload(null, "m", "u", "s"));

        assertEquals(1L, counters().get("unknown").get());
    }

    @Test
    @DisplayName("reportError — 超长 type 截断到 64 字符作为计数键")
    void reportErrorTruncatesType() {
        String longType = "T".repeat(70);

        controller.reportError(payload(longType, "m", "u", "s"));

        Map<String, AtomicLong> counters = counters();
        assertEquals(1, counters.size());
        assertTrue(counters.containsKey("T".repeat(64)));
        assertEquals(1L, counters.get("T".repeat(64)).get());
    }

    @Test
    @DisplayName("reportError — 超长 message/url/stack 被截断仍可正常计数")
    void reportErrorTruncatesLongTextFields() {
        controller.reportError(payload("E", "m".repeat(600), "u".repeat(600), "s".repeat(600)));

        assertEquals(1L, counters().get("E").get());
    }

    @Test
    @DisplayName("reportError — 非字符串字段触发异常被吞掉，仍返回 200")
    void reportErrorSwallowsClassCastException() {
        Map<String, Object> body = new HashMap<>();
        body.put("type", 123);

        ResponseEntity<Void> response = controller.reportError(body);

        assertEquals(200, response.getStatusCode().value());
        assertTrue(counters().isEmpty());
    }

    @Test
    @DisplayName("reportError — 类型基数达到 500 上限后新类型不再计入")
    void reportErrorStopsAddingBeyondTypeCap() {
        Map<String, AtomicLong> counters = counters();
        for (int i = 0; i < 500; i++) {
            counters.put("k" + i, new AtomicLong());
        }

        controller.reportError(payload("brand-new", "m", "u", "s"));

        assertEquals(500, counters.size());
        assertFalse(counters.containsKey("brand-new"));
    }

    @Test
    @DisplayName("reportError — 类型基数达上限后已存在类型仍可累加")
    void reportErrorStillCountsExistingTypeAtCap() {
        Map<String, AtomicLong> counters = counters();
        for (int i = 0; i < 500; i++) {
            counters.put("k" + i, new AtomicLong());
        }

        controller.reportError(payload("k0", "m", "u", "s"));

        assertEquals(500, counters.size());
        assertEquals(1L, counters.get("k0").get());
    }

    @Test
    @DisplayName("reportError — 全部字段缺失也不抛异常")
    void reportErrorHandlesAllDefaults() {
        ResponseEntity<Void> response = controller.reportError(new ConcurrentHashMap<>());

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(1L, counters().get("unknown").get());
    }
}
