package com.codeatlas.server.service;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.service.impl.ClassServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClassServiceImpl 单元测试")
class ClassServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    @Mock
    private ClassSummaryMapper classSummaryMapper;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    @InjectMocks
    private ClassServiceImpl classService;

    private ClassSummaryEntity classEntity() {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setId(10L);
        entity.setFqn("com.demo.AService");
        entity.setSimpleName("AService");
        entity.setPackageName("com.demo");
        entity.setClassType("class");
        entity.setLayer("service");
        entity.setPublicMethods(2);
        entity.setTotalMethods(3);
        entity.setLineCount(40);
        entity.setAnnotations("@Service");
        entity.setDependencies("[\"com.demo.BService\"]");
        entity.setModuleName("server");
        entity.setProjectId(PROJECT_ID);
        return entity;
    }

    @Test
    @DisplayName("类详情：先鉴权再查询，命中时返回全部 12 个字段")
    void getClassDetail_withEntity_shouldMapAllFields() {
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, "com.demo.AService"))
                .thenReturn(classEntity());

        Map<String, Object> result = classService.getClassDetail(PROJECT_ID, "com.demo.AService", USER_ID);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertEquals(10L, result.get("id"));
        assertEquals("com.demo.AService", result.get("fqn"));
        assertEquals("AService", result.get("simpleName"));
        assertEquals("com.demo", result.get("packageName"));
        assertEquals("class", result.get("classType"));
        assertEquals("service", result.get("layer"));
        assertEquals(2, result.get("publicMethods"));
        assertEquals(3, result.get("totalMethods"));
        assertEquals(40, result.get("lineCount"));
        assertEquals("@Service", result.get("annotations"));
        assertEquals("[\"com.demo.BService\"]", result.get("dependencies"));
        assertEquals("server", result.get("moduleName"));
        assertEquals(12, result.size());
    }

    @Test
    @DisplayName("类详情：目标类不存在时返回 null（不抛异常）")
    void getClassDetail_missingClass_shouldReturnNull() {
        when(classSummaryMapper.findByProjectIdAndFqn(PROJECT_ID, "com.demo.Ghost")).thenReturn(null);

        assertNull(classService.getClassDetail(PROJECT_ID, "com.demo.Ghost", USER_ID));

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("类搜索：鉴权后逐条映射关键字查询结果")
    void searchClasses_shouldMapEntities() {
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "Service", 20))
                .thenReturn(Collections.singletonList(classEntity()));

        List<Map<String, Object>> result = classService.searchClasses(PROJECT_ID, "Service", 20, USER_ID);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertEquals(1, result.size());
        Map<String, Object> item = result.get(0);
        assertEquals(10L, item.get("id"));
        assertEquals("com.demo.AService", item.get("fqn"));
        assertEquals("AService", item.get("simpleName"));
        assertEquals("com.demo", item.get("packageName"));
        assertEquals("class", item.get("classType"));
        assertEquals("service", item.get("layer"));
        assertEquals(40, item.get("lineCount"));
        assertEquals(PROJECT_ID, item.get("projectId"));
        assertEquals(8, item.size());
    }

    @Test
    @DisplayName("类搜索：无命中返回空列表")
    void searchClasses_noHits_shouldReturnEmptyList() {
        when(classSummaryMapper.searchByKeyword(PROJECT_ID, "Zzz", 5))
                .thenReturn(Collections.emptyList());

        List<Map<String, Object>> result = classService.searchClasses(PROJECT_ID, "Zzz", 5, USER_ID);

        assertTrue(result.isEmpty());
        verifyNoMoreInteractions(classSummaryMapper);
    }
}
