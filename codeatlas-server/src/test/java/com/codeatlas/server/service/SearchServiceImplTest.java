package com.codeatlas.server.service;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.service.impl.SearchServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchServiceImpl 单元测试")
class SearchServiceImplTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ClassSummaryMapper classSummaryMapper;

    @InjectMocks
    private SearchServiceImpl searchService;

    private Project project(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setDescription("desc-" + id);
        return p;
    }

    private ClassSummaryEntity classSummary(Long id, Long projectId, String fqn) {
        ClassSummaryEntity c = new ClassSummaryEntity();
        c.setId(id);
        c.setProjectId(projectId);
        c.setFqn(fqn);
        c.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        c.setLayer("service");
        return c;
    }

    @Test
    @DisplayName("空关键字（含纯空白）：返回 projects/classes 两个空列表，不查库")
    void search_blankKeyword_shouldReturnBothEmptyListsWithoutQueries() {
        Map<String, Object> result = searchService.search("   ", "all", USER_ID);

        assertEquals(2, result.size());
        assertEquals(List.of(), result.get("projects"));
        assertEquals(List.of(), result.get("classes"));
        verifyNoInteractions(projectMapper);
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("type=project：只查项目且关键字先 trim，映射 4 个字段")
    void search_projectType_shouldOnlySearchProjectsWithTrimmedKeyword() {
        when(projectMapper.searchByNameForUser("Service", 20, USER_ID))
                .thenReturn(List.of(project(1L, "ServiceHub")));

        Map<String, Object> result = searchService.search("  Service  ", "project", USER_ID);

        assertEquals(1, result.size());
        assertFalse(result.containsKey("classes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> projects = (List<Map<String, Object>>) result.get("projects");
        assertEquals(1, projects.size());
        Map<String, Object> item = projects.get(0);
        assertEquals(1L, item.get("id"));
        assertEquals("ServiceHub", item.get("name"));
        assertEquals("project", item.get("type"));
        assertEquals("desc-1", item.get("description"));
        assertEquals(4, item.size());
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("type=class：用户无任何项目时 classes 为空列表")
    void search_classType_noUserProjects_shouldReturnEmptyClasses() {
        when(projectMapper.findByUserId(USER_ID)).thenReturn(List.of());

        Map<String, Object> result = searchService.search("AService", "class", USER_ID);

        assertEquals(1, result.size());
        assertEquals(List.of(), result.get("classes"));
        verifyNoInteractions(classSummaryMapper);
    }

    @Test
    @DisplayName("type=all：多项目命中按轮次交错合并（不按项目独占）")
    void search_all_shouldInterleaveHitsByRound() {
        Project p1 = project(1L, "P1");
        Project p2 = project(2L, "P2");
        when(projectMapper.searchByNameForUser("A", 20, USER_ID)).thenReturn(List.of(p1, p2));
        when(projectMapper.findByUserId(USER_ID)).thenReturn(List.of(p1, p2));
        when(classSummaryMapper.searchByKeyword(1L, "A", 20))
                .thenReturn(List.of(classSummary(11L, 1L, "com.p1.A1"),
                        classSummary(12L, 1L, "com.p1.A2")));
        when(classSummaryMapper.searchByKeyword(2L, "A", 20))
                .thenReturn(List.of(classSummary(21L, 2L, "com.p2.B1")));

        Map<String, Object> result = searchService.search("A", "all", USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) result.get("classes");
        assertEquals(3, classes.size());
        // 轮次交错：P1 第 1 条 → P2 第 1 条 → P1 第 2 条
        assertEquals("com.p1.A1", classes.get(0).get("fqn"));
        assertEquals("com.p2.B1", classes.get(1).get("fqn"));
        assertEquals("com.p1.A2", classes.get(2).get("fqn"));

        Map<String, Object> item = classes.get(0);
        assertEquals(11L, item.get("id"));
        assertEquals("A1", item.get("simpleName"));
        assertEquals("class", item.get("type"));
        assertEquals(1L, item.get("projectId"));
        assertEquals("service", item.get("layer"));
        assertEquals(6, item.size());
    }

    @Test
    @DisplayName("type=all：无命中的项目被跳过，不影响其他项目结果")
    void search_all_shouldSkipProjectsWithoutHits() {
        Project p1 = project(1L, "P1");
        Project p2 = project(2L, "P2");
        when(projectMapper.searchByNameForUser("B", 20, USER_ID)).thenReturn(List.of());
        when(projectMapper.findByUserId(USER_ID)).thenReturn(List.of(p1, p2));
        when(classSummaryMapper.searchByKeyword(1L, "B", 20)).thenReturn(List.of());
        when(classSummaryMapper.searchByKeyword(2L, "B", 20))
                .thenReturn(List.of(classSummary(21L, 2L, "com.p2.B1")));

        Map<String, Object> result = searchService.search("B", "all", USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) result.get("classes");
        assertEquals(1, classes.size());
        assertEquals("com.p2.B1", classes.get(0).get("fqn"));
        verify(classSummaryMapper, times(2)).searchByKeyword(anyLong(), eq("B"), eq(20));
    }

    @Test
    @DisplayName("type=all：全局结果上限 20，超出轮次截断")
    void search_all_shouldCapAtTwentyResults() {
        List<Project> projects = List.of(project(1L, "P1"), project(2L, "P2"), project(3L, "P3"));
        when(projectMapper.searchByNameForUser("X", 20, USER_ID)).thenReturn(List.of());
        when(projectMapper.findByUserId(USER_ID)).thenReturn(projects);
        for (int projectIndex = 1; projectIndex <= 3; projectIndex++) {
            List<ClassSummaryEntity> hits = new ArrayList<>();
            for (int hitIndex = 1; hitIndex <= 20; hitIndex++) {
                hits.add(classSummary((long) (projectIndex * 100 + hitIndex),
                        (long) projectIndex, "com.p" + projectIndex + ".C" + hitIndex));
            }
            when(classSummaryMapper.searchByKeyword((long) projectIndex, "X", 20)).thenReturn(hits);
        }

        Map<String, Object> result = searchService.search("X", "all", USER_ID);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> classes = (List<Map<String, Object>>) result.get("classes");
        assertEquals(20, classes.size());
        // 最后一条是 P2 第 7 轮（第 20 条），P3 第 7 轮被截断
        assertEquals("com.p2.C7", classes.get(19).get("fqn"));
        long p3Count = classes.stream().filter(c -> Long.valueOf(3L).equals(c.get("projectId"))).count();
        assertEquals(6, p3Count);
    }

    @Test
    @DisplayName("未知 type：返回空 Map（无任何键）")
    void search_unknownType_shouldReturnEmptyMap() {
        Map<String, Object> result = searchService.search("A", "unknown", USER_ID);

        assertTrue(result.isEmpty());
        verifyNoInteractions(projectMapper);
        verifyNoInteractions(classSummaryMapper);
    }
}
