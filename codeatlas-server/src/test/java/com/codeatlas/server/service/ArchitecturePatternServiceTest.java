package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.AiException;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArchitecturePatternService 单元测试（AbstractInsightAnalysisService 骨架）")
class ArchitecturePatternServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

    @Mock
    private AiAnalysisService aiAnalysisService;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScanService scanService;
    @Mock
    private InsightService insightService;
    @Mock
    private HallucinationChecker hallucinationChecker;
    @Mock
    private TokenBudgetManager tokenBudgetManager;
    @Mock
    private AiClient aiClient;

    private ArchitecturePatternService service;

    @BeforeEach
    void setUp() {
        service = new ArchitecturePatternService(aiAnalysisService, projectService, scanService,
                insightService, hallucinationChecker, tokenBudgetManager, aiClient,
                new AiTaskProgressRegistry(new ObjectMapper()));
    }

    private void stubProjectScanClasses() {
        Project project = new Project();
        project.setName("Demo");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setId(100L);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn("com.demo.AService");
        cls.setSimpleName("AService");
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(Collections.singletonList(cls));
        when(aiAnalysisService.buildPromptVariables(any(Project.class), any(ScanRecord.class), anyList()))
                .thenReturn(new HashMap<>());
    }

    private AiResponse aiResponse(String content, int tokens) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokens);
        response.setPromptTokens(tokens / 2);
        response.setCompletionTokens(tokens - tokens / 2);
        response.setLatencyMs(120);
        return response;
    }

    @Test
    @DisplayName("AI 客户端未配置时跳过并返回 null，不触碰项目数据")
    void analyzePatterns_noAiClient_shouldReturnNull() {
        ArchitecturePatternService noAi = new ArchitecturePatternService(aiAnalysisService, projectService,
                scanService, insightService, hallucinationChecker, tokenBudgetManager, null,
                new AiTaskProgressRegistry(new ObjectMapper()));

        assertNull(noAi.analyzePatterns(PROJECT_ID, USER_ID));
        verifyNoInteractions(projectService);
    }

    @Test
    @DisplayName("项目不存在时抛 404")
    void analyzePatterns_projectNotFound_shouldThrow404() {
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("未扫描时抛 400")
    void analyzePatterns_noScan_shouldThrow400() {
        Project project = new Project();
        project.setName("Demo");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(400, ex.getCode());
        assertEquals("请先触发一次扫描", ex.getMessage());
    }

    @Test
    @DisplayName("扫描无类数据时抛 400")
    void analyzePatterns_noClasses_shouldThrow400() {
        Project project = new Project();
        project.setName("Demo");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(new ScanRecord());
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(400, ex.getCode());
        assertEquals("暂无扫描结果，请先触发一次扫描", ex.getMessage());
    }

    @Test
    @DisplayName("Token 预算耗尽时抛预算异常并落失败审计")
    void analyzePatterns_budgetExceeded_shouldAuditAndThrow() {
        stubProjectScanClasses();
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED.getCode(), ex.getCode());

        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("PATTERN_ANALYSIS"),
                eq(PROJECT_ID), eq(USER_ID), eq(0L), eq(0L), eq(0L), eq(0L),
                eq("Monthly token budget exceeded"), eq(false));
        verify(aiAnalysisService, never()).callAiWithResilience(any(AiRequest.class));
        verify(insightService, never()).saveInsight(any(InsightEntity.class));
    }

    @Test
    @DisplayName("AI 全部熔断（响应为 null）时抛 AI 异常并落失败审计")
    void analyzePatterns_nullResponse_shouldAuditAndThrow() {
        stubProjectScanClasses();
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(null);

        AiException ex = assertThrows(AiException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(ErrorCode.AI_API_INTERNAL_ERROR.getCode(), ex.getCode());

        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("PATTERN_ANALYSIS"),
                eq(PROJECT_ID), eq(USER_ID), eq(0L), eq(0L), eq(0L), eq(0L),
                eq("All AI models exhausted or no content"), eq(false));
        verify(insightService, never()).saveInsight(any(InsightEntity.class));
    }

    @Test
    @DisplayName("响应内容为空（推理截断形态）时同样抛 AI 异常并落失败审计")
    void analyzePatterns_emptyContent_shouldAuditAndThrow() {
        stubProjectScanClasses();
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("", 2048));

        AiException ex = assertThrows(AiException.class,
                () -> service.analyzePatterns(PROJECT_ID, USER_ID));
        assertEquals(ErrorCode.AI_API_INTERNAL_ERROR.getCode(), ex.getCode());

        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("PATTERN_ANALYSIS"),
                eq(PROJECT_ID), eq(USER_ID), eq(0L), eq(0L), eq(0L), eq(0L),
                eq("All AI models exhausted or no content"), eq(false));
        verify(insightService, never()).saveInsight(any(InsightEntity.class));
    }

    @Test
    @DisplayName("成功路径：渲染模板调用 AI、落成功审计并保存 PATTERN 洞察")
    void analyzePatterns_success_shouldSaveInsight() {
        stubProjectScanClasses();
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("识别到分层架构", 800));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.85));

        InsightEntity insight = service.analyzePatterns(PROJECT_ID, USER_ID);

        assertNotNull(insight);
        assertEquals("PATTERN", insight.getType());
        assertEquals("设计模式报告 — Demo", insight.getTitle());
        assertEquals("识别到分层架构", insight.getContent());
        assertEquals("[]", insight.getSources());
        assertEquals(100L, insight.getScanId());
        assertEquals(BigDecimal.valueOf(0.85), insight.getConfidence());
        assertTrue(insight.getMetadata().contains("\"tokensUsed\":800"));
        assertTrue(insight.getMetadata().contains("\"classCount\":1"));
        verify(insightService).saveInsight(insight);

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService).callAiWithResilience(requestCaptor.capture());
        AiRequest request = requestCaptor.getValue();
        assertEquals(8192, request.getMaxTokens());
        assertEquals(0.3, request.getTemperature());
        assertTrue(request.getSystemPrompt().contains("架构模式"));

        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("PATTERN_ANALYSIS"),
                eq(PROJECT_ID), eq(USER_ID), eq(400L), eq(400L), eq(800L), eq(120L),
                isNull(), eq(true));
    }

    @Test
    @DisplayName("幻觉重试成功：采用低温重试结果并累加 Token")
    void analyzePatterns_hallucinationRetrySuccess_shouldUseRetriedContent() {
        stubProjectScanClasses();
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("引用了 com.fake.GhostA 和 com.fake.GhostB", 500),
                        aiResponse("修正后的设计模式报告", 300));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(true, Arrays.asList("com.fake.GhostA", "com.fake.GhostB")),
                        new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.7));

        InsightEntity insight = service.analyzePatterns(PROJECT_ID, USER_ID);

        assertEquals("修正后的设计模式报告", insight.getContent());
        assertTrue(insight.getMetadata().contains("\"tokensUsed\":800"));

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService, times(2)).callAiWithResilience(requestCaptor.capture());
        AiRequest retryRequest = requestCaptor.getAllValues().get(1);
        assertEquals(0.1, retryRequest.getTemperature());
        assertTrue(retryRequest.getSystemPrompt().contains("重新输出设计模式报告"));
    }
}
