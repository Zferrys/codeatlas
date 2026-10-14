package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ChatHistoryEntity 单元测试（全字段读写）")
class ChatHistoryEntityTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        ChatHistoryEntity entity = new ChatHistoryEntity();

        assertNull(entity.getId());
        assertNull(entity.getProjectId());
        assertNull(entity.getUserId());
        assertNull(entity.getScope());
        assertNull(entity.getContextFqn());
        assertNull(entity.getQuestion());
        assertNull(entity.getAnswer());
        assertNull(entity.getVerification());
        assertNull(entity.getCreatedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 14, 16, 20, 30);

        entity.setId(61L);
        entity.setProjectId(62L);
        entity.setUserId(63L);
        entity.setScope("CLASS");
        entity.setContextFqn("com.codeatlas.server.service.AiAnalysisService");
        entity.setQuestion("这个类负责什么？");
        entity.setAnswer("负责 AI 架构叙事分析");
        entity.setVerification("{\"hallucination\":false}");
        entity.setCreatedAt(createdAt);

        assertEquals(61L, entity.getId());
        assertEquals(62L, entity.getProjectId());
        assertEquals(63L, entity.getUserId());
        assertEquals("CLASS", entity.getScope());
        assertEquals("com.codeatlas.server.service.AiAnalysisService", entity.getContextFqn());
        assertEquals("这个类负责什么？", entity.getQuestion());
        assertEquals("负责 AI 架构叙事分析", entity.getAnswer());
        assertEquals("{\"hallucination\":false}", entity.getVerification());
        assertEquals(createdAt, entity.getCreatedAt());
    }
}
