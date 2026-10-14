package com.codeatlas.server.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ProjectMember 单元测试（全字段读写）")
class ProjectMemberTest {

    @Test
    @DisplayName("新建实例：全部字段默认 null")
    void newInstance_shouldHaveAllFieldsNull() {
        ProjectMember member = new ProjectMember();

        assertNull(member.getId());
        assertNull(member.getProjectId());
        assertNull(member.getUserId());
        assertNull(member.getRole());
        assertNull(member.getJoinedAt());
    }

    @Test
    @DisplayName("setter/getter：全字段往返值一致（互不串位）")
    void accessors_shouldRoundTripDistinctValues() {
        ProjectMember member = new ProjectMember();
        LocalDateTime joinedAt = LocalDateTime.of(2026, 5, 20, 14, 0, 0);

        member.setId(41L);
        member.setProjectId(42L);
        member.setUserId(43L);
        member.setRole("ARCHITECT");
        member.setJoinedAt(joinedAt);

        assertEquals(41L, member.getId());
        assertEquals(42L, member.getProjectId());
        assertEquals(43L, member.getUserId());
        assertEquals("ARCHITECT", member.getRole());
        assertEquals(joinedAt, member.getJoinedAt());
    }
}
