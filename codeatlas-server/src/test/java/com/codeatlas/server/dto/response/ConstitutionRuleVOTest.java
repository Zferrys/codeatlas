package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.ConstitutionRuleEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ConstitutionRuleVO 单元测试（实体 → VO 映射）")
class ConstitutionRuleVOTest {

    @Test
    @DisplayName("from：实体 10 个字段逐一映射，值互不串位")
    void from_shouldMapAllFieldsExactly() {
        ConstitutionRuleEntity entity = new ConstitutionRuleEntity();
        LocalDateTime createdAt = LocalDateTime.of(2026, 4, 5, 6, 7, 8);
        entity.setId(21L);
        entity.setProjectId(22L);
        entity.setName("控制器禁止直连 Mapper");
        entity.setDescription("Controller 只能通过 Service 访问数据层");
        entity.setCategory("LAYERING");
        entity.setSeverity("HIGH");
        entity.setRuleDefinition("{\"from\":\"controller\",\"to\":\"mapper\",\"forbidden\":true}");
        entity.setIsEnabled(true);
        entity.setVersion(3);
        entity.setCreatedAt(createdAt);

        ConstitutionRuleVO vo = ConstitutionRuleVO.from(entity);

        assertEquals(21L, vo.getId());
        assertEquals(22L, vo.getProjectId());
        assertEquals("控制器禁止直连 Mapper", vo.getName());
        assertEquals("Controller 只能通过 Service 访问数据层", vo.getDescription());
        assertEquals("LAYERING", vo.getCategory());
        assertEquals("HIGH", vo.getSeverity());
        assertEquals("{\"from\":\"controller\",\"to\":\"mapper\",\"forbidden\":true}", vo.getRuleDefinition());
        assertEquals(Boolean.TRUE, vo.getIsEnabled());
        assertEquals(3, vo.getVersion());
        assertEquals(createdAt, vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：实体字段为 null 时映射结果保持 null，不代入默认值")
    void from_shouldKeepNullsWhenEntityEmpty() {
        ConstitutionRuleVO vo = ConstitutionRuleVO.from(new ConstitutionRuleEntity());

        assertNull(vo.getId());
        assertNull(vo.getProjectId());
        assertNull(vo.getName());
        assertNull(vo.getDescription());
        assertNull(vo.getCategory());
        assertNull(vo.getSeverity());
        assertNull(vo.getRuleDefinition());
        assertNull(vo.getIsEnabled());
        assertNull(vo.getVersion());
        assertNull(vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：每次调用返回独立实例，修改一个不影响另一个")
    void from_shouldReturnIndependentInstances() {
        ConstitutionRuleEntity entity = new ConstitutionRuleEntity();
        entity.setId(29L);
        entity.setIsEnabled(false);

        ConstitutionRuleVO first = ConstitutionRuleVO.from(entity);
        ConstitutionRuleVO second = ConstitutionRuleVO.from(entity);

        assertNotSame(first, second);
        assertEquals(29L, first.getId());
        assertEquals(29L, second.getId());
        assertEquals(Boolean.FALSE, first.getIsEnabled());

        first.setId(300L);
        assertEquals(29L, second.getId());
    }
}
