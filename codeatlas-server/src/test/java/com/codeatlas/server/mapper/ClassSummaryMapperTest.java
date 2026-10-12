package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@MybatisTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:test-schema.sql")
@DisplayName("ClassSummaryMapper 集成测试")
class ClassSummaryMapperTest {

    @Autowired
    private ClassSummaryMapper classSummaryMapper;

    private ClassSummaryEntity createEntity(String fqn, Long scanId, Long projectId) {
        ClassSummaryEntity e = new ClassSummaryEntity();
        e.setScanId(scanId);
        e.setProjectId(projectId);
        e.setFqn(fqn);
        e.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        e.setPackageName(fqn.substring(0, fqn.lastIndexOf('.')));
        e.setClassType("CLASS");
        e.setLayer("SERVICE");
        e.setPublicMethods(3);
        e.setTotalMethods(5);
        e.setLineCount(50);
        e.setAnnotations("[]");
        e.setDependencies("[]");
        e.setMethodNames("[]");
        e.setModuleName("core");
        return e;
    }

    @Test
    @DisplayName("插入后按项目ID+类名查询，方法名列表往返一致")
    void shouldInsertAndFindByProjectIdAndFqn() {
        ClassSummaryEntity entity = createEntity("com.codeatlas.engine.ai.AiResponse", 10L, 1L);
        entity.setMethodNames("[\"getContent\",\"setContent\",\"hasToolCalls\"]");
        entity.setLineCount(65);
        entity.setLayer("DTO");
        classSummaryMapper.insert(entity);

        assertNotNull(entity.getId());

        ClassSummaryEntity found = classSummaryMapper.findByProjectIdAndFqn(1L, "com.codeatlas.engine.ai.AiResponse");
        assertNotNull(found);
        assertEquals("[\"getContent\",\"setContent\",\"hasToolCalls\"]", found.getMethodNames());
        assertEquals(65, found.getLineCount());
        assertEquals("DTO", found.getLayer());
    }

    @Test
    @DisplayName("批量插入后按 scanId 查询，每行方法名列表均保留")
    void shouldInsertBatchAndFindByScanId() {
        ClassSummaryEntity a = createEntity("com.example.A", 20L, 2L);
        a.setMethodNames("[\"run\",\"stop\"]");
        ClassSummaryEntity b = createEntity("com.example.B", 20L, 2L);
        b.setMethodNames("[]");
        classSummaryMapper.insertBatch(Arrays.asList(a, b));

        List<ClassSummaryEntity> found = classSummaryMapper.findByScanId(20L);
        assertEquals(2, found.size());

        ClassSummaryEntity fa = found.stream()
                .filter(c -> "com.example.A".equals(c.getFqn()))
                .findFirst().orElse(null);
        ClassSummaryEntity fb = found.stream()
                .filter(c -> "com.example.B".equals(c.getFqn()))
                .findFirst().orElse(null);
        assertNotNull(fa);
        assertNotNull(fb);
        assertEquals("[\"run\",\"stop\"]", fa.getMethodNames());
        assertEquals("[]", fb.getMethodNames());
    }
}
