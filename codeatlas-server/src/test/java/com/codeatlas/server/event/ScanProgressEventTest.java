package com.codeatlas.server.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ScanProgressEvent 单元测试")
class ScanProgressEventTest {

    @Test
    @DisplayName("构造器 — 保存全部字段并在构造时打时间戳")
    void constructorStoresAllFields() {
        LocalDateTime before = LocalDateTime.now();

        ScanProgressEvent event = new ScanProgressEvent(1L, 2L, "PARSING", 42, "解析中");

        LocalDateTime after = LocalDateTime.now();
        assertEquals(1L, event.getProjectId());
        assertEquals(2L, event.getScanId());
        assertEquals("PARSING", event.getStage());
        assertEquals(42, event.getProgress());
        assertEquals("解析中", event.getMessage());
        assertNotNull(event.getTimestamp());
        assertFalse(event.getTimestamp().isBefore(before));
        assertFalse(event.getTimestamp().isAfter(after));
    }

    @Test
    @DisplayName("getTimestamp — 两次读取返回同一实例（只赋值一次）")
    void timestampIsStable() {
        ScanProgressEvent event = new ScanProgressEvent(1L, 2L, "RULES", 10, "m");

        assertNotNull(event.getTimestamp());
        assertEquals(event.getTimestamp(), event.getTimestamp());
        assertTrue(event.getTimestamp() instanceof LocalDateTime);
    }

    @Test
    @DisplayName("字段允许 null（scanId 可空）")
    void nullableFieldsPreserved() {
        ScanProgressEvent event = new ScanProgressEvent(3L, null, null, 0, null);

        assertEquals(3L, event.getProjectId());
        assertEquals(null, event.getScanId());
        assertEquals(null, event.getStage());
        assertEquals(0, event.getProgress());
        assertEquals(null, event.getMessage());
    }
}
