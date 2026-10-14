package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.PromptTemplate;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
@DisplayName("AntiPatternService 单元测试（maxTokens/类型差异钩子）")
class AntiPatternServiceTest {

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

    private AntiPatternService service;

    @BeforeEach
    void setUp() {
        service = new AntiPatternService(aiAnalysisService, projectService, scanService,
                insightService, hallucinationChecker, tokenBudgetManager, aiClient,
                new AiTaskProgressRegistry(new ObjectMapper()));
    }

    @Test
    @DisplayName("AI 客户端未配置时跳过并返回 null")
    void analyzeAntiPatterns_noAiClient_shouldReturnNull() {
        AntiPatternService noAi = new AntiPatternService(aiAnalysisService, projectService, scanService,
                insightService, hallucinationChecker, tokenBudgetManager, null,
                new AiTaskProgressRegistry(new ObjectMapper()));

        assertNull(noAi.analyzeAntiPatterns(PROJECT_ID, USER_ID));
    }

    @Test
    @DisplayName("成功路径：maxTokens=8192、type=ANTI_PATTERN、审计阶段=ANTI_PATTERN_DETECT")
    void analyzeAntiPatterns_success_shouldUse8192AndSaveInsight() {
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
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("检出反模式：上帝类");
        response.setTokensUsed(500);
        response.setPromptTokens(250);
        response.setCompletionTokens(250);
        response.setLatencyMs(60);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(response);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.75));

        InsightEntity insight = service.analyzeAntiPatterns(PROJECT_ID, USER_ID);

        assertEquals("ANTI_PATTERN", insight.getType());
        assertEquals("反模式检测 — Demo", insight.getTitle());
        assertEquals("检出反模式：上帝类", insight.getContent());
        verify(insightService).saveInsight(insight);

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService).callAiWithResilience(requestCaptor.capture());
        assertEquals(8192, requestCaptor.getValue().getMaxTokens());
        assertTrue(requestCaptor.getValue().getSystemPrompt().contains("反模式"));

        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("ANTI_PATTERN_DETECT"),
                eq(PROJECT_ID), eq(USER_ID), eq(250L), eq(250L), eq(500L), eq(60L),
                isNull(), eq(true));
    }

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    private static final String TASK_ID = "aitask-anti-pattern-01";

    private AntiPatternService serviceWith(AiTaskProgressRegistry progressRegistry) {
        return new AntiPatternService(aiAnalysisService, projectService, scanService,
                insightService, hallucinationChecker, tokenBudgetManager, aiClient, progressRegistry);
    }

    private ClassSummaryEntity classSummary(String fqn) {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(fqn);
        cls.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        return cls;
    }

    private void stubProjectAndScan(List<ClassSummaryEntity> classes) {
        Project project = new Project();
        project.setName("Demo");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setId(100L);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(classes);
        when(aiAnalysisService.buildPromptVariables(any(Project.class), any(ScanRecord.class), anyList()))
                .thenReturn(new HashMap<>());
    }

    private AiResponse aiResponse(String content, int tokensUsed, int prompt, int completion, int latency) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokensUsed);
        response.setPromptTokens(prompt);
        response.setCompletionTokens(completion);
        response.setLatencyMs(latency);
        return response;
    }

    @Test
    @DisplayName("taskId 重载：成功路径落库 ANTI_PATTERN 且任务未被误标取消")
    void analyzeAntiPatterns_taskIdOverload_shouldSaveInsight() {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        AiTaskProgressRegistry progressRegistry = new AiTaskProgressRegistry(new ObjectMapper());
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("检出反模式：循环依赖", 500, 250, 250, 60));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.8));

        InsightEntity insight = serviceWith(progressRegistry)
                .analyzeAntiPatterns(PROJECT_ID, USER_ID, TASK_ID);

        assertEquals("ANTI_PATTERN", insight.getType());
        assertEquals("检出反模式：循环依赖", insight.getContent());
        verify(insightService).saveInsight(insight);
        assertFalse(progressRegistry.isCancelled(TASK_ID), "正常完成的任务不应被标记取消");
    }

    @Test
    @DisplayName("幻觉重试的复核抛异常：降级使用原文，主+重试 Token 一并入账")
    void analyzeAntiPatterns_retryRecheckThrows_shouldKeepOriginalContentAndAccountBoth() {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("原始内容 com.demo.AService", 500, 250, 250, 60),
                        aiResponse("重试内容 com.demo.AService", 200, 100, 100, 30));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(
                        true, Collections.singletonList("com.fake.Ghost")))
                .thenThrow(new RuntimeException("recheck boom"));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.5));

        InsightEntity insight = service.analyzeAntiPatterns(PROJECT_ID, USER_ID);

        assertEquals("原始内容 com.demo.AService", insight.getContent(), "复核异常 → 重试结果不得采纳");
        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("ANTI_PATTERN_DETECT"),
                eq(PROJECT_ID), eq(USER_ID), eq(350L), eq(350L), eq(700L), eq(90L),
                isNull(), eq(true));
    }

    @Test
    @DisplayName("幻觉重试调用返回 null：降级使用原文，仅主调用 Token 入账")
    void analyzeAntiPatterns_retryCallReturnsNull_shouldKeepOriginalContent() {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("原始内容 com.demo.AService", 500, 250, 250, 60))
                .thenReturn(null);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(
                        true, Collections.singletonList("com.fake.Ghost")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.5));

        InsightEntity insight = service.analyzeAntiPatterns(PROJECT_ID, USER_ID);

        assertEquals("原始内容 com.demo.AService", insight.getContent());
        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("ANTI_PATTERN_DETECT"),
                eq(PROJECT_ID), eq(USER_ID), eq(250L), eq(250L), eq(500L), eq(60L),
                isNull(), eq(true));
    }

    @Test
    @DisplayName("幻觉检查自身抛异常：交付原文，不触发重试，主调用 Token 入账")
    void analyzeAntiPatterns_hallucinationCheckThrows_shouldDeliverOriginal() {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(aiResponse("原始内容 com.demo.AService", 500, 250, 250, 60));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenThrow(new RuntimeException("check boom"));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.5));

        InsightEntity insight = service.analyzeAntiPatterns(PROJECT_ID, USER_ID);

        assertEquals("原始内容 com.demo.AService", insight.getContent());
        verify(aiAnalysisService, times(1)).callAiWithResilience(any(AiRequest.class));
        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("ANTI_PATTERN_DETECT"),
                eq(PROJECT_ID), eq(USER_ID), eq(250L), eq(250L), eq(500L), eq(60L),
                isNull(), eq(true));
    }

    @Test
    @DisplayName("模板渲染为空：跳过分析不调 AI 不落审计（模板缺失防御）")
    void analyzeAntiPatterns_emptyPrompt_shouldReturnNullWithoutAiCall() throws Exception {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        java.lang.reflect.Field cacheField = PromptTemplate.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) cacheField.get(null);
        String original = cache.put("prompts/anti-pattern-detect.md", "");

        try {
            assertNull(service.analyzeAntiPatterns(PROJECT_ID, USER_ID));
        } finally {
            if (original != null) {
                cache.put("prompts/anti-pattern-detect.md", original);
            } else {
                cache.remove("prompts/anti-pattern-detect.md");
            }
        }

        verify(aiAnalysisService, never()).callAiWithResilience(any(AiRequest.class));
        verify(aiAnalysisService, never()).saveAiAuditLog(anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), anyBoolean());
        verifyNoInteractions(tokenBudgetManager);
    }

    @Test
    @DisplayName("订阅后显式终止：AI 调用前熔断，零消耗不落审计")
    void analyzeAntiPatterns_cancelledBeforeAiCall_shouldReturnNullWithoutAudit() {
        stubProjectAndScan(Collections.singletonList(classSummary("com.demo.AService")));
        AiTaskProgressRegistry progressRegistry = new AiTaskProgressRegistry(new ObjectMapper());
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        progressRegistry.cancel(TASK_ID);

        assertNull(serviceWith(progressRegistry).analyzeAntiPatterns(PROJECT_ID, USER_ID, TASK_ID));

        verify(aiAnalysisService, never()).callAiWithResilience(any(AiRequest.class));
        verify(aiAnalysisService, never()).saveAiAuditLog(anyString(), anyString(), any(), any(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(), anyBoolean());
        verifyNoInteractions(tokenBudgetManager);
    }
}
