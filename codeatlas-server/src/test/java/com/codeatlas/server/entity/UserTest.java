package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("User 单元测试（全字段读写）")
class UserTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        User user = new User();

        assertNull(user.getId());
        assertNull(user.getUsername());
        assertNull(user.getPasswordHash());
        assertNull(user.getEmail());
        assertNull(user.getAvatarUrl());
        assertNull(user.getRole());
        assertNull(user.getStatus());
        assertNull(user.getLastLoginAt());
        assertNull(user.getCreatedAt());
        assertNull(user.getUpdatedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        User user = new User();
        LocalDateTime lastLoginAt = LocalDateTime.of(2026, 9, 9, 8, 7, 6);
        LocalDateTime createdAt = LocalDateTime.of(2025, 1, 2, 3, 4, 5);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 14, 23, 59, 59);

        user.setId(51L);
        user.setUsername("zferrys");
        user.setPasswordHash("$2a$10$abcdefghijklmnopqrstuv");
        user.setEmail("zferrys@example.com");
        user.setAvatarUrl("https://cdn.example.com/avatar/51.png");
        user.setRole("ADMIN");
        user.setStatus(1);
        user.setLastLoginAt(lastLoginAt);
        user.setCreatedAt(createdAt);
        user.setUpdatedAt(updatedAt);

        assertEquals(51L, user.getId());
        assertEquals("zferrys", user.getUsername());
        assertEquals("$2a$10$abcdefghijklmnopqrstuv", user.getPasswordHash());
        assertEquals("zferrys@example.com", user.getEmail());
        assertEquals("https://cdn.example.com/avatar/51.png", user.getAvatarUrl());
        assertEquals("ADMIN", user.getRole());
        assertEquals(1, user.getStatus());
        assertEquals(lastLoginAt, user.getLastLoginAt());
        assertEquals(createdAt, user.getCreatedAt());
        assertEquals(updatedAt, user.getUpdatedAt());
    }

    @Test
    @DisplayName("status：1（正常）与 0（禁用）均可原值存取")
    void status_shouldStoreEnabledAndDisabledFlags() {
        User user = new User();

        user.setStatus(1);
        assertEquals(1, user.getStatus());

        user.setStatus(0);
        assertEquals(0, user.getStatus());
    }
}
