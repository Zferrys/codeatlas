package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ViolationEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ViolationVO 单元测试（实体 → VO 映射）")
class ViolationVOTest {

    @Test
    @DisplayName("from：实体 13 个字段逐一映射，值互不串位")
    void from_shouldMapAllFieldsExactly() {
        ViolationEntity entity = new ViolationEntity();
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime createdAt = LocalDateTime.of(2026, 6, 7, 8, 9, 10);
        entity.setId(1L);
        entity.setScanId(2L);
        entity.setRuleId(3L);
        entity.setProjectId(4L);
        entity.setSeverity("HIGH");
        entity.setClassFqn("com.demo.OrderService");
        entity.setMethodName("createOrder");
        entity.setLineNumber(42);
        entity.setMessage("控制器直接访问 Mapper");
        entity.setSuggestion("改为调用 Service 层");
        entity.setIsResolved(true);
        entity.setResolvedAt(resolvedAt);
        entity.setCreatedAt(createdAt);

        ViolationVO vo = ViolationVO.from(entity);

        assertEquals(1L, vo.getId());
        assertEquals(2L, vo.getScanId());
        assertEquals(3L, vo.getRuleId());
        assertEquals(4L, vo.getProjectId());
        assertEquals("HIGH", vo.getSeverity());
        assertEquals("com.demo.OrderService", vo.getClassFqn());
        assertEquals("createOrder", vo.getMethodName());
        assertEquals(42, vo.getLineNumber());
        assertEquals("控制器直接访问 Mapper", vo.getMessage());
        assertEquals("改为调用 Service 层", vo.getSuggestion());
        assertEquals(Boolean.TRUE, vo.getIsResolved());
        assertEquals(resolvedAt, vo.getResolvedAt());
        assertEquals(createdAt, vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：实体字段为 null 时映射结果保持 null，不代入默认值")
    void from_shouldKeepNullsWhenEntityEmpty() {
        ViolationVO vo = ViolationVO.from(new ViolationEntity());

        assertNull(vo.getId());
        assertNull(vo.getScanId());
        assertNull(vo.getRuleId());
        assertNull(vo.getProjectId());
        assertNull(vo.getSeverity());
        assertNull(vo.getClassFqn());
        assertNull(vo.getMethodName());
        assertNull(vo.getLineNumber());
        assertNull(vo.getMessage());
        assertNull(vo.getSuggestion());
        assertNull(vo.getIsResolved());
        assertNull(vo.getResolvedAt());
        assertNull(vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：每次调用返回独立实例，修改一个不影响另一个")
    void from_shouldReturnIndependentInstances() {
        ViolationEntity entity = new ViolationEntity();
        entity.setId(9L);
        entity.setSeverity("LOW");

        ViolationVO first = ViolationVO.from(entity);
        ViolationVO second = ViolationVO.from(entity);

        assertNotSame(first, second);
        assertEquals(9L, first.getId());
        assertEquals(9L, second.getId());

        first.setId(100L);
        assertEquals(9L, second.getId());
    }
}
