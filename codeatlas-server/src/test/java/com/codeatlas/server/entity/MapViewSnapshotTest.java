package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("MapViewSnapshot 单元测试（全字段读写）")
class MapViewSnapshotTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        MapViewSnapshot snapshot = new MapViewSnapshot();

        assertNull(snapshot.getId());
        assertNull(snapshot.getProjectId());
        assertNull(snapshot.getUserId());
        assertNull(snapshot.getName());
        assertNull(snapshot.getViewState());
        assertNull(snapshot.getIsPublic());
        assertNull(snapshot.getCreatedAt());
        assertNull(snapshot.getUpdatedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        MapViewSnapshot snapshot = new MapViewSnapshot();
        LocalDateTime createdAt = LocalDateTime.of(2026, 3, 1, 9, 0, 0);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 3, 2, 18, 45, 30);

        snapshot.setId(31L);
        snapshot.setProjectId(32L);
        snapshot.setUserId(33L);
        snapshot.setName("架构全景视图");
        snapshot.setViewState("{\"zoom\":1.5,\"position\":{\"x\":10,\"y\":20}}");
        snapshot.setIsPublic(1);
        snapshot.setCreatedAt(createdAt);
        snapshot.setUpdatedAt(updatedAt);

        assertEquals(31L, snapshot.getId());
        assertEquals(32L, snapshot.getProjectId());
        assertEquals(33L, snapshot.getUserId());
        assertEquals("架构全景视图", snapshot.getName());
        assertEquals("{\"zoom\":1.5,\"position\":{\"x\":10,\"y\":20}}", snapshot.getViewState());
        assertEquals(1, snapshot.getIsPublic());
        assertEquals(createdAt, snapshot.getCreatedAt());
        assertEquals(updatedAt, snapshot.getUpdatedAt());
    }

    @Test
    @DisplayName("isPublic：0（私有）与 1（公开）均可原值存取")
    void isPublic_shouldStorePrivateAndPublicFlags() {
        MapViewSnapshot snapshot = new MapViewSnapshot();

        snapshot.setIsPublic(0);
        assertEquals(0, snapshot.getIsPublic());

        snapshot.setIsPublic(1);
        assertEquals(1, snapshot.getIsPublic());
    }
}
