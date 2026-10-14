package com.codeatlas.server.dto.response;

import com.codeatlas.server.entity.InsightEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("InsightVO 单元测试（实体 → VO 映射）")
class InsightVOTest {

    @Test
    @DisplayName("from：实体 10 个字段逐一映射，值互不串位")
    void from_shouldMapAllFieldsExactly() {
        InsightEntity entity = new InsightEntity();
        BigDecimal confidence = new BigDecimal("0.92");
        LocalDateTime createdAt = LocalDateTime.of(2026, 2, 3, 4, 5, 6);
        entity.setId(11L);
        entity.setScanId(12L);
        entity.setProjectId(13L);
        entity.setType("ANTI_PATTERN");
        entity.setTitle("反模式扫描报告");
        entity.setContent("发现 3 处循环依赖");
        entity.setConfidence(confidence);
        entity.setSources("[\"com.demo.AService\",\"com.demo.BService\"]");
        entity.setMetadata("{\"tokensUsed\":500}");
        entity.setCreatedAt(createdAt);

        InsightVO vo = InsightVO.from(entity);

        assertEquals(11L, vo.getId());
        assertEquals(12L, vo.getScanId());
        assertEquals(13L, vo.getProjectId());
        assertEquals("ANTI_PATTERN", vo.getType());
        assertEquals("反模式扫描报告", vo.getTitle());
        assertEquals("发现 3 处循环依赖", vo.getContent());
        assertEquals(confidence, vo.getConfidence());
        assertEquals("[\"com.demo.AService\",\"com.demo.BService\"]", vo.getSources());
        assertEquals("{\"tokensUsed\":500}", vo.getMetadata());
        assertEquals(createdAt, vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：实体字段为 null 时映射结果保持 null，不代入默认值")
    void from_shouldKeepNullsWhenEntityEmpty() {
        InsightVO vo = InsightVO.from(new InsightEntity());

        assertNull(vo.getId());
        assertNull(vo.getScanId());
        assertNull(vo.getProjectId());
        assertNull(vo.getType());
        assertNull(vo.getTitle());
        assertNull(vo.getContent());
        assertNull(vo.getConfidence());
        assertNull(vo.getSources());
        assertNull(vo.getMetadata());
        assertNull(vo.getCreatedAt());
    }

    @Test
    @DisplayName("from：每次调用返回独立实例，修改一个不影响另一个")
    void from_shouldReturnIndependentInstances() {
        InsightEntity entity = new InsightEntity();
        entity.setId(19L);
        entity.setType("ARCH_STORY");

        InsightVO first = InsightVO.from(entity);
        InsightVO second = InsightVO.from(entity);

        assertNotSame(first, second);
        assertEquals(19L, first.getId());
        assertEquals(19L, second.getId());

        first.setId(200L);
        assertEquals(19L, second.getId());
    }
}
