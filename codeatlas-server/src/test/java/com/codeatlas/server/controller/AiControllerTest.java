package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.AgenticSearchService;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.AiTaskProgressRegistry;
import com.codeatlas.server.service.AntiPatternService;
import com.codeatlas.server.service.ArchitecturePatternService;
import com.codeatlas.server.service.ConstitutionCheckService;
import com.codeatlas.server.service.ImpactAnalysisService;
import com.codeatlas.server.service.InsightService;
import com.codeatlas.server.service.ProjectAccessChecker;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiController 单元测试：直接调用控制器方法 + Mockito mock 服务层，
 * 断言精确到响应字段值与服务调用参数（变异测试可杀灭）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiController 单元测试")
class AiControllerTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final Long SCAN_ID = 100L;
    private static final String TASK_ID = "aitask-abc123";

    @Mock
    private AiAnalysisService aiAnalysisService;
    @Mock
    private ImpactAnalysisService impactAnalysisService;
    @Mock
    private InsightService insightService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScanService scanService;
    @Mock
    private AgenticSearchService agenticSearchService;
    @Mock
    private ArchitecturePatternService architecturePatternService;
    @Mock
    private AntiPatternService antiPatternService;
    @Mock
    private ConstitutionCheckService constitutionCheckService;
    @Mock
    private ProjectAccessChecker projectAccessChecker;
    @Mock
    private AiTaskProgressRegistry progressRegistry;

    private AiController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new AiController(aiAnalysisService, impactAnalysisService, insightService,
                projectService, scanService, agenticSearchService, architecturePatternService,
                antiPatternService, constitutionCheckService, projectAccessChecker, progressRegistry);
        principal = new CodeAtlasUserDetails(USER_ID, "alice", "DEVELOPER");
    }

    private static InsightEntity insight(Long id, String title, BigDecimal confidence) {
        InsightEntity entity = new InsightEntity();
        entity.setId(id);
        entity.setTitle(title);
        entity.setConfidence(confidence);
        return entity;
    }

    // ==================== GET /story ====================

    @Test
    @DisplayName("getStory — 命中叙事，映射全部字段并校验项目访问")
    void getStoryMapsAllFields() {
        InsightEntity story = insight(11L, "架构叙事", new BigDecimal("0.95"));
        story.setContent("内容正文");
        story.setCreatedAt(LocalDateTime.of(2024, 5, 6, 7, 8, 9));
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(story);

        ApiResponse<Map<String, Object>> response = controller.getStory(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        Map<String, Object> data = response.getData();
        assertEquals(11L, data.get("id"));
        assertEquals("架构叙事", data.get("title"));
        assertEquals("内容正文", data.get("content"));
        assertEquals(new BigDecimal("0.95"), data.get("confidence"));
        assertEquals("2024-05-06T07:08:09", data.get("createdAt"));
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getStory — 无叙事返回 success(null)")
    void getStoryReturnsNullDataWhenAbsent() {
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.getStory(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("getStory — createdAt 为 null 时字段存在但值为 null")
    void getStoryHandlesNullCreatedAt() {
        InsightEntity story = insight(11L, "架构叙事", null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(story);

        Map<String, Object> data = controller.getStory(PROJECT_ID, principal).getData();

        assertNotNull(data);
        assertTrue(data.containsKey("createdAt"));
        assertNull(data.get("createdAt"));
    }

    // ==================== POST /story ====================

    @Test
    @DisplayName("generateStory — 成功生成，映射 id/title/confidence 并收尾任务")
    void generateStorySuccess() {
        Project project = new Project();
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        when(aiAnalysisService.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID, USER_ID))
                .thenReturn(insight(21L, "新叙事", new BigDecimal("0.8")));

        ApiResponse<Map<String, Object>> response = controller.generateStory(PROJECT_ID, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(21L, response.getData().get("id"));
        assertEquals("新叙事", response.getData().get("title"));
        assertEquals(new BigDecimal("0.8"), response.getData().get("confidence"));
        verify(projectAccessChecker).check(project, PROJECT_ID, USER_ID);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("generateStory — 无扫描记录返回 400 且任务仍收尾")
    void generateStoryWithoutScan() {
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(new Project());
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.generateStory(PROJECT_ID, TASK_ID, principal);

        assertEquals(400, response.getCode());
        assertEquals("请先触发一次扫描", response.getMessage());
        assertNull(response.getData());
        verify(aiAnalysisService, never()).analyzeArchitecture(any(), any(), any(), any());
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("generateStory — AI 返回空返回 500 错误（精确文案）")
    void generateStoryAiReturnsNull() {
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(new Project());
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        when(aiAnalysisService.analyzeArchitecture(PROJECT_ID, SCAN_ID, null, USER_ID)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.generateStory(PROJECT_ID, null, principal);

        assertEquals(500, response.getCode());
        assertEquals("AI 分析失败：模型未返回有效内容或服务暂不可用，请稍后重试", response.getMessage());
        verify(progressRegistry).complete(isNull());
    }

    @Test
    @DisplayName("generateStory — 服务抛异常时 finally 仍收尾任务并向上抛出")
    void generateStoryPropagatesAndCompletesTask() {
        when(projectService.getProjectEntity(PROJECT_ID))
                .thenThrow(new BusinessException(404, "项目不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.generateStory(PROJECT_ID, TASK_ID, principal));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        verify(progressRegistry).complete(TASK_ID);
    }

    // ==================== POST /impact-simulate ====================

    @Test
    @DisplayName("impactSimulate — targetClass 为 null 返回 400")
    void impactSimulateRejectsNullTargetClass() {
        Map<String, Object> body = new HashMap<>();
        body.put("targetClass", null);

        ApiResponse<Map<String, Object>> response = controller.impactSimulate(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(400, response.getCode());
        assertEquals("targetClass 不能为空", response.getMessage());
        verifyNoInteractions(impactAnalysisService);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("impactSimulate — targetClass 为空串返回 400")
    void impactSimulateRejectsEmptyTargetClass() {
        Map<String, Object> body = new HashMap<>();
        body.put("targetClass", "");

        ApiResponse<Map<String, Object>> response = controller.impactSimulate(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(400, response.getCode());
        assertEquals("targetClass 不能为空", response.getMessage());
        verify(projectAccessChecker, never()).check(any(), any());
    }

    @Test
    @DisplayName("impactSimulate — 全字段透传，结果原样返回")
    void impactSimulatePassesAllFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("targetClass", "com.a.B");
        body.put("changeType", "DELETE_CLASS");
        body.put("targetMethod", "run");
        body.put("description", "重构");
        Map<String, Object> serviceResult = Map.of("totalImpacted", 3);
        when(impactAnalysisService.analyzeImpact(PROJECT_ID, "com.a.B", "DELETE_CLASS", "run", "重构", USER_ID, TASK_ID))
                .thenReturn(serviceResult);

        ApiResponse<Map<String, Object>> response = controller.impactSimulate(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(serviceResult, response.getData());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("impactSimulate — 缺省字段使用默认值 changeType=MODIFY_METHOD/description=''")
    void impactSimulateUsesDefaults() {
        Map<String, Object> body = new HashMap<>();
        body.put("targetClass", "com.a.B");
        when(impactAnalysisService.analyzeImpact(PROJECT_ID, "com.a.B", "MODIFY_METHOD", null, "", USER_ID, TASK_ID))
                .thenReturn(Map.of("ok", true));

        ApiResponse<Map<String, Object>> response = controller.impactSimulate(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(200, response.getCode());
        verify(impactAnalysisService).analyzeImpact(PROJECT_ID, "com.a.B", "MODIFY_METHOD", null, "", USER_ID, TASK_ID);
    }

    // ==================== POST /context-qa ====================

    @Test
    @DisplayName("contextQa — question 为 null 返回 400")
    void contextQaRejectsNullQuestion() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", null);

        ApiResponse<Map<String, Object>> response = controller.contextQa(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(400, response.getCode());
        assertEquals("question 不能为空", response.getMessage());
        verifyNoInteractions(agenticSearchService, impactAnalysisService);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("contextQa — question 为纯空白返回 400")
    void contextQaRejectsBlankQuestion() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", "   ");

        ApiResponse<Map<String, Object>> response = controller.contextQa(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(400, response.getCode());
        assertEquals("question 不能为空", response.getMessage());
    }

    @Test
    @DisplayName("contextQa — 未指定类名走 Agentic Search，question 去首尾空白后透传")
    void contextQaUsesAgenticSearchWhenNoClassFqn() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", "  类之间如何协作？  ");
        body.put("classFqn", null);
        Map<String, Object> serviceResult = Map.of("answer", "OK");
        when(agenticSearchService.search(PROJECT_ID, "类之间如何协作？", USER_ID, TASK_ID))
                .thenReturn(serviceResult);

        ApiResponse<Map<String, Object>> response = controller.contextQa(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(serviceResult, response.getData());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("contextQa — classFqn 为纯空白也走 Agentic Search")
    void contextQaUsesAgenticSearchForBlankClassFqn() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", "q");
        body.put("classFqn", "   ");
        when(agenticSearchService.search(PROJECT_ID, "q", USER_ID, TASK_ID)).thenReturn(Map.of("a", 1));

        controller.contextQa(PROJECT_ID, body, TASK_ID, principal);

        verify(agenticSearchService).search(PROJECT_ID, "q", USER_ID, TASK_ID);
        verifyNoInteractions(impactAnalysisService);
    }

    @Test
    @DisplayName("contextQa — 指定类名走精确上下文匹配，原 question 不做 trim")
    void contextQaUsesContextualQaWhenClassFqnPresent() {
        Map<String, Object> body = new HashMap<>();
        body.put("question", " 问题 ");
        body.put("classFqn", "com.a.B");
        Map<String, Object> serviceResult = Map.of("answer", "A");
        when(impactAnalysisService.contextualQa(PROJECT_ID, " 问题 ", "com.a.B", USER_ID, TASK_ID))
                .thenReturn(serviceResult);

        ApiResponse<Map<String, Object>> response = controller.contextQa(PROJECT_ID, body, TASK_ID, principal);

        assertEquals(serviceResult, response.getData());
        verify(agenticSearchService, never()).search(any(), any(), any(), any());
    }

    // ==================== GET/POST /pattern ====================

    @Test
    @DisplayName("getPattern — 命中最新报告，返回类型为 PATTERN 的洞察字段")
    void getPatternReturnsLatestInsight() {
        InsightEntity pattern = insight(31L, "设计模式报告", new BigDecimal("0.7"));
        pattern.setContent("模式内容");
        pattern.setCreatedAt(LocalDateTime.of(2024, 1, 2, 3, 4, 5));
        when(insightService.getLatestByType(PROJECT_ID, "PATTERN")).thenReturn(pattern);

        ApiResponse<Map<String, Object>> response = controller.getPattern(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(31L, response.getData().get("id"));
        assertEquals("设计模式报告", response.getData().get("title"));
        assertEquals("模式内容", response.getData().get("content"));
        assertEquals("2024-01-02T03:04:05", response.getData().get("createdAt"));
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getPattern — 无历史报告返回 success(null)")
    void getPatternWithoutHistory() {
        when(insightService.getLatestByType(PROJECT_ID, "PATTERN")).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.getPattern(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("generatePattern — 成功生成并收尾任务")
    void generatePatternSuccess() {
        when(architecturePatternService.analyzePatterns(PROJECT_ID, USER_ID, TASK_ID))
                .thenReturn(insight(41L, "新报告", new BigDecimal("0.9")));

        ApiResponse<Map<String, Object>> response = controller.generatePattern(PROJECT_ID, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(41L, response.getData().get("id"));
        assertEquals("新报告", response.getData().get("title"));
        assertEquals(new BigDecimal("0.9"), response.getData().get("confidence"));
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("generatePattern — 服务返回 null 时 500 并收尾任务")
    void generatePatternReturns500WhenNull() {
        when(architecturePatternService.analyzePatterns(PROJECT_ID, USER_ID, null)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.generatePattern(PROJECT_ID, null, principal);

        assertEquals(500, response.getCode());
        assertEquals("AI 分析失败：服务未就绪或返回为空，请稍后重试", response.getMessage());
        verify(progressRegistry).complete(isNull());
    }

    // ==================== GET/POST /anti-pattern ====================

    @Test
    @DisplayName("getAntiPattern — 无历史返回 success(null)")
    void getAntiPatternWithoutHistory() {
        when(insightService.getLatestByType(PROJECT_ID, "ANTI_PATTERN")).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.getAntiPattern(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("getAntiPattern — 命中时映射 title/confidence")
    void getAntiPatternReturnsLatest() {
        InsightEntity entity = insight(51L, "反模式", new BigDecimal("0.6"));
        entity.setContent("c");
        when(insightService.getLatestByType(PROJECT_ID, "ANTI_PATTERN")).thenReturn(entity);

        Map<String, Object> data = controller.getAntiPattern(PROJECT_ID, principal).getData();

        assertEquals(51L, data.get("id"));
        assertEquals("反模式", data.get("title"));
        assertEquals(new BigDecimal("0.6"), data.get("confidence"));
        assertNull(data.get("createdAt"));
    }

    @Test
    @DisplayName("generateAntiPattern — 成功生成并收尾任务")
    void generateAntiPatternSuccess() {
        when(antiPatternService.analyzeAntiPatterns(PROJECT_ID, USER_ID, TASK_ID))
                .thenReturn(insight(61L, "新反模式", new BigDecimal("0.5")));

        ApiResponse<Map<String, Object>> response = controller.generateAntiPattern(PROJECT_ID, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(61L, response.getData().get("id"));
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("generateAntiPattern — 服务返回 null 时 500")
    void generateAntiPatternReturns500WhenNull() {
        when(antiPatternService.analyzeAntiPatterns(PROJECT_ID, USER_ID, TASK_ID)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.generateAntiPattern(PROJECT_ID, TASK_ID, principal);

        assertEquals(500, response.getCode());
        assertEquals("AI 分析失败：服务未就绪或返回为空，请稍后重试", response.getMessage());
        verify(progressRegistry).complete(TASK_ID);
    }

    // ==================== GET/POST /constitution-check ====================

    @Test
    @DisplayName("getConstitutionCheck — 无历史返回 success(null)")
    void getConstitutionCheckWithoutHistory() {
        when(insightService.getLatestByType(PROJECT_ID, "CONSTITUTION_CHECK")).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.getConstitutionCheck(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("getConstitutionCheck — 命中时映射全部字段")
    void getConstitutionCheckReturnsLatest() {
        InsightEntity entity = insight(71L, "合规检查", new BigDecimal("0.4"));
        entity.setContent("检查内容");
        entity.setCreatedAt(LocalDateTime.of(2024, 3, 4, 5, 6, 7));
        when(insightService.getLatestByType(PROJECT_ID, "CONSTITUTION_CHECK")).thenReturn(entity);

        Map<String, Object> data = controller.getConstitutionCheck(PROJECT_ID, principal).getData();

        assertEquals(71L, data.get("id"));
        assertEquals("合规检查", data.get("title"));
        assertEquals("检查内容", data.get("content"));
        assertEquals(new BigDecimal("0.4"), data.get("confidence"));
        assertEquals("2024-03-04T05:06:07", data.get("createdAt"));
    }

    @Test
    @DisplayName("generateConstitutionCheck — 成功生成并收尾任务")
    void generateConstitutionCheckSuccess() {
        when(constitutionCheckService.analyzeConstitution(PROJECT_ID, USER_ID, TASK_ID))
                .thenReturn(insight(81L, "新合规", new BigDecimal("0.3")));

        ApiResponse<Map<String, Object>> response = controller.generateConstitutionCheck(PROJECT_ID, TASK_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(81L, response.getData().get("id"));
        assertEquals("新合规", response.getData().get("title"));
        assertEquals(new BigDecimal("0.3"), response.getData().get("confidence"));
        verify(progressRegistry).complete(TASK_ID);
    }

    @Test
    @DisplayName("generateConstitutionCheck — 服务返回 null 时 500 并收尾任务")
    void generateConstitutionCheckReturns500WhenNull() {
        when(constitutionCheckService.analyzeConstitution(PROJECT_ID, USER_ID, null)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.generateConstitutionCheck(PROJECT_ID, null, principal);

        assertEquals(500, response.getCode());
        assertEquals("AI 分析失败：服务未就绪或返回为空，请稍后重试", response.getMessage());
        verify(progressRegistry).complete(isNull());
    }

    @Test
    @DisplayName("generateConstitutionCheck — 分析抛异常时 finally 收尾任务并向上抛出")
    void generateConstitutionCheckPropagatesException() {
        when(constitutionCheckService.analyzeConstitution(eq(PROJECT_ID), eq(USER_ID), eq(TASK_ID)))
                .thenThrow(new BusinessException(500, "boom"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.generateConstitutionCheck(PROJECT_ID, TASK_ID, principal));

        assertEquals("boom", ex.getMessage());
        verify(progressRegistry).complete(TASK_ID);
    }

    // ==================== 越权检查调用（变异补强） ====================

    @Test
    @DisplayName("getAntiPattern — 项目访问检查被调用且参数精确")
    void getAntiPatternChecksProjectAccess() {
        when(insightService.getLatestByType(PROJECT_ID, "ANTI_PATTERN")).thenReturn(null);

        controller.getAntiPattern(PROJECT_ID, principal);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("generateAntiPattern — 项目访问检查被调用且参数精确")
    void generateAntiPatternChecksProjectAccess() {
        when(antiPatternService.analyzeAntiPatterns(PROJECT_ID, USER_ID, TASK_ID))
                .thenReturn(insight(62L, "反模式", new BigDecimal("0.5")));

        controller.generateAntiPattern(PROJECT_ID, TASK_ID, principal);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getConstitutionCheck — 项目访问检查被调用且参数精确")
    void getConstitutionCheckChecksProjectAccess() {
        when(insightService.getLatestByType(PROJECT_ID, "CONSTITUTION_CHECK")).thenReturn(null);

        controller.getConstitutionCheck(PROJECT_ID, principal);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("generateConstitutionCheck — 项目访问检查被调用且参数精确")
    void generateConstitutionCheckChecksProjectAccess() {
        when(constitutionCheckService.analyzeConstitution(PROJECT_ID, USER_ID, TASK_ID))
                .thenReturn(insight(82L, "合规", new BigDecimal("0.3")));

        controller.generateConstitutionCheck(PROJECT_ID, TASK_ID, principal);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }
}
