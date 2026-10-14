package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.InsightVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.InsightService;
import com.codeatlas.server.service.ProjectAccessChecker;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import com.codeatlas.server.util.HealthScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InsightController 单元测试")
class InsightControllerTest {

    private static final Long PROJECT_ID = 5L;
    private static final Long USER_ID = 8L;

    @Mock
    private InsightService insightService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScanService scanService;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private InsightController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new InsightController(insightService, projectService, scanService, projectAccessChecker);
        principal = new CodeAtlasUserDetails(USER_ID, "carol", "VIEWER");
    }

    private static InsightEntity insight(Long id, String type) {
        InsightEntity entity = new InsightEntity();
        entity.setId(id);
        entity.setScanId(20L);
        entity.setProjectId(PROJECT_ID);
        entity.setType(type);
        entity.setTitle("标题" + id);
        entity.setContent("内容" + id);
        entity.setConfidence(new BigDecimal("0.88"));
        entity.setSources("sources");
        entity.setMetadata("metadata");
        entity.setCreatedAt(LocalDateTime.of(2024, 6, 1, 12, 0, 0));
        return entity;
    }

    // ==================== GET /insights ====================

    @Test
    @DisplayName("getInsights — 无 type 走全量分页，生成 VO 并保留分页元信息")
    void getInsightsWithoutType() {
        when(insightService.getInsights(PROJECT_ID, 2, 5))
                .thenReturn(new PageResult<>(List.of(insight(1L, "ARCH_STORY")), 11L, 2, 5));

        ApiResponse<PageResult<InsightVO>> response = controller.getInsights(PROJECT_ID, null, principal, 2, 5);

        assertEquals(200, response.getCode());
        PageResult<InsightVO> page = response.getData();
        assertEquals(11L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(5, page.getSize());
        assertEquals(1, page.getRecords().size());
        InsightVO vo = page.getRecords().get(0);
        assertEquals(1L, vo.getId());
        assertEquals(20L, vo.getScanId());
        assertEquals(PROJECT_ID, vo.getProjectId());
        assertEquals("ARCH_STORY", vo.getType());
        assertEquals("标题1", vo.getTitle());
        assertEquals("内容1", vo.getContent());
        assertEquals(new BigDecimal("0.88"), vo.getConfidence());
        assertEquals("sources", vo.getSources());
        assertEquals("metadata", vo.getMetadata());
        assertEquals(LocalDateTime.of(2024, 6, 1, 12, 0, 0), vo.getCreatedAt());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getInsights — type 为空串仍走全量分页")
    void getInsightsWithEmptyType() {
        when(insightService.getInsights(PROJECT_ID, 1, 20))
                .thenReturn(new PageResult<>(Collections.emptyList(), 0L, 1, 20));

        ApiResponse<PageResult<InsightVO>> response = controller.getInsights(PROJECT_ID, "", principal, 1, 20);

        assertEquals(0, response.getData().getRecords().size());
        assertEquals(0L, response.getData().getTotal());
        verify(insightService, never()).getInsightsByType(any(), any(), any(Integer.class), any(Integer.class));
    }

    @Test
    @DisplayName("getInsights — 指定 type 走按类型分页")
    void getInsightsByType() {
        when(insightService.getInsightsByType(PROJECT_ID, "PATTERN", 3, 7))
                .thenReturn(new PageResult<>(List.of(insight(2L, "PATTERN")), 1L, 3, 7));

        ApiResponse<PageResult<InsightVO>> response = controller.getInsights(PROJECT_ID, "PATTERN", principal, 3, 7);

        assertEquals("PATTERN", response.getData().getRecords().get(0).getType());
        assertEquals(1L, response.getData().getTotal());
        assertEquals(3, response.getData().getPage());
        assertEquals(7, response.getData().getSize());
        verify(insightService).getInsightsByType(PROJECT_ID, "PATTERN", 3, 7);
        verify(insightService, never()).getInsights(any(), any(Integer.class), any(Integer.class));
    }

    // ==================== GET /health-score ====================

    @Test
    @DisplayName("getHealthScore — 无扫描记录时四维与总分取零值且不查类列表")
    void getHealthScoreWithoutScan() {
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(new Project());
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(null);
        HealthScoreCalculator.Result result = mock(HealthScoreCalculator.Result.class);
        when(result.getTotal()).thenReturn(new BigDecimal("0.00"));
        when(result.getArchitecture()).thenReturn(0);
        when(result.getStructure()).thenReturn(0);
        when(result.getQuality()).thenReturn(0);
        when(result.getDependency()).thenReturn(0);

        try (MockedStatic<HealthScoreCalculator> mocked = mockStatic(HealthScoreCalculator.class)) {
            mocked.when(() -> HealthScoreCalculator.compute(0, 0, Collections.emptyList())).thenReturn(result);

            ApiResponse<Map<String, Object>> response = controller.getHealthScore(PROJECT_ID, principal);

            assertEquals(200, response.getCode());
            Map<String, Object> data = response.getData();
            assertEquals(PROJECT_ID, data.get("projectId"));
            assertEquals(new BigDecimal("0.00"), data.get("healthScore"));
            @SuppressWarnings("unchecked")
            Map<String, Object> dims = (Map<String, Object>) data.get("dimensions");
            assertEquals(0, dims.get("architectureCompliance"));
            assertEquals(0, dims.get("codeStructure"));
            assertEquals(0, dims.get("codeQuality"));
            assertEquals(0, dims.get("dependencyHealth"));
            assertEquals(List.of("architectureCompliance", "codeStructure", "codeQuality", "dependencyHealth"),
                    List.copyOf(dims.keySet()));
        }
        verify(projectAccessChecker).check(any(Project.class), any(Long.class), any(Long.class));
        verify(scanService, never()).getClassSummaries(any());
    }

    @Test
    @DisplayName("getHealthScore — 扫描统计字段为 null 时按 0 处理")
    void getHealthScoreTreatsNullStatsAsZero() {
        Project project = new Project();
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setTotalClasses(null);
        scan.setTotalViolations(null);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        HealthScoreCalculator.Result result = mock(HealthScoreCalculator.Result.class);
        when(result.getTotal()).thenReturn(BigDecimal.ZERO);
        when(result.getArchitecture()).thenReturn(0);
        when(result.getStructure()).thenReturn(0);
        when(result.getQuality()).thenReturn(0);
        when(result.getDependency()).thenReturn(0);

        try (MockedStatic<HealthScoreCalculator> mocked = mockStatic(HealthScoreCalculator.class)) {
            mocked.when(() -> HealthScoreCalculator.compute(0, 0, Collections.emptyList())).thenReturn(result);

            controller.getHealthScore(PROJECT_ID, principal);

            mocked.verify(() -> HealthScoreCalculator.compute(0, 0, Collections.emptyList()));
        }
        verify(projectAccessChecker).check(project, PROJECT_ID, USER_ID);
        verify(scanService, never()).getClassSummaries(any());
    }

    @Test
    @DisplayName("getHealthScore — 有类数据时透传真实统计与类列表到计算器")
    void getHealthScoreWithClassData() {
        Project project = new Project();
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setTotalClasses(12);
        scan.setTotalViolations(4);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn("com.a.B");
        List<ClassSummaryEntity> classes = List.of(cls);
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(classes);

        HealthScoreCalculator.Result result = mock(HealthScoreCalculator.Result.class);
        when(result.getTotal()).thenReturn(new BigDecimal("87.50"));
        when(result.getArchitecture()).thenReturn(90);
        when(result.getStructure()).thenReturn(80);
        when(result.getQuality()).thenReturn(70);
        when(result.getDependency()).thenReturn(60);

        try (MockedStatic<HealthScoreCalculator> mocked = mockStatic(HealthScoreCalculator.class)) {
            mocked.when(() -> HealthScoreCalculator.compute(12, 4, classes)).thenReturn(result);

            ApiResponse<Map<String, Object>> response = controller.getHealthScore(PROJECT_ID, principal);

            assertEquals(new BigDecimal("87.50"), response.getData().get("healthScore"));
            @SuppressWarnings("unchecked")
            Map<String, Object> dims = (Map<String, Object>) response.getData().get("dimensions");
            assertEquals(90, dims.get("architectureCompliance"));
            assertEquals(80, dims.get("codeStructure"));
            assertEquals(70, dims.get("codeQuality"));
            assertEquals(60, dims.get("dependencyHealth"));
            mocked.verify(() -> HealthScoreCalculator.compute(12, 4, classes));
        }
        verify(scanService).getClassSummaries(PROJECT_ID);
    }

    @Test
    @DisplayName("getHealthScore — totalClasses 为 0 时不拉取类列表")
    void getHealthScoreSkipsClassFetchWhenNoClasses() {
        Project project = new Project();
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setTotalClasses(0);
        scan.setTotalViolations(2);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        HealthScoreCalculator.Result result = mock(HealthScoreCalculator.Result.class);
        when(result.getTotal()).thenReturn(BigDecimal.ONE);
        when(result.getArchitecture()).thenReturn(1);
        when(result.getStructure()).thenReturn(1);
        when(result.getQuality()).thenReturn(1);
        when(result.getDependency()).thenReturn(1);

        try (MockedStatic<HealthScoreCalculator> mocked = mockStatic(HealthScoreCalculator.class)) {
            mocked.when(() -> HealthScoreCalculator.compute(0, 2, Collections.emptyList())).thenReturn(result);

            Map<String, Object> data = controller.getHealthScore(PROJECT_ID, principal).getData();

            assertNull(data.get("missing"));
            assertEquals(BigDecimal.ONE, data.get("healthScore"));
        }
        verify(scanService, never()).getClassSummaries(any());
    }
}
