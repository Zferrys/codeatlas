package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("AuditLogEntity 单元测试（全字段读写）")
class AuditLogEntityTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        AuditLogEntity entity = new AuditLogEntity();

        assertNull(entity.getId());
        assertNull(entity.getUserId());
        assertNull(entity.getUsername());
        assertNull(entity.getAction());
        assertNull(entity.getTargetType());
        assertNull(entity.getTargetId());
        assertNull(entity.getDetail());
        assertNull(entity.getIpAddress());
        assertNull(entity.getUserAgent());
        assertNull(entity.getCreatedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        AuditLogEntity entity = new AuditLogEntity();
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 14, 10, 30, 15);

        entity.setId(101L);
        entity.setUserId(202L);
        entity.setUsername("zferrys");
        entity.setAction("PROJECT_CREATE");
        entity.setTargetType("PROJECT");
        entity.setTargetId(303L);
        entity.setDetail("创建项目 CodeAtlas");
        entity.setIpAddress("10.20.30.40");
        entity.setUserAgent("Mozilla/5.0 (Windows NT 10.0)");
        entity.setCreatedAt(createdAt);

        assertEquals(101L, entity.getId());
        assertEquals(202L, entity.getUserId());
        assertEquals("zferrys", entity.getUsername());
        assertEquals("PROJECT_CREATE", entity.getAction());
        assertEquals("PROJECT", entity.getTargetType());
        assertEquals(303L, entity.getTargetId());
        assertEquals("创建项目 CodeAtlas", entity.getDetail());
        assertEquals("10.20.30.40", entity.getIpAddress());
        assertEquals("Mozilla/5.0 (Windows NT 10.0)", entity.getUserAgent());
        assertEquals(createdAt, entity.getCreatedAt());
    }
}
