package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("InsightEntity 单元测试（全字段读写）")
class InsightEntityTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        InsightEntity entity = new InsightEntity();

        assertNull(entity.getId());
        assertNull(entity.getScanId());
        assertNull(entity.getProjectId());
        assertNull(entity.getType());
        assertNull(entity.getTitle());
        assertNull(entity.getContent());
        assertNull(entity.getConfidence());
        assertNull(entity.getSources());
        assertNull(entity.getMetadata());
        assertNull(entity.getCreatedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        InsightEntity entity = new InsightEntity();
        BigDecimal confidence = new BigDecimal("0.87");
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 8, 9, 10, 11);

        entity.setId(71L);
        entity.setScanId(72L);
        entity.setProjectId(73L);
        entity.setType("ARCH_STORY");
        entity.setTitle("架构叙事 — CodeAtlas");
        entity.setContent("# 架构概览\n分层清晰");
        entity.setConfidence(confidence);
        entity.setSources("[\"com.demo.AService\"]");
        entity.setMetadata("{\"tokensUsed\":800}");
        entity.setCreatedAt(createdAt);

        assertEquals(71L, entity.getId());
        assertEquals(72L, entity.getScanId());
        assertEquals(73L, entity.getProjectId());
        assertEquals("ARCH_STORY", entity.getType());
        assertEquals("架构叙事 — CodeAtlas", entity.getTitle());
        assertEquals("# 架构概览\n分层清晰", entity.getContent());
        assertEquals(confidence, entity.getConfidence());
        assertEquals("[\"com.demo.AService\"]", entity.getSources());
        assertEquals("{\"tokensUsed\":800}", entity.getMetadata());
        assertEquals(createdAt, entity.getCreatedAt());
    }
}
