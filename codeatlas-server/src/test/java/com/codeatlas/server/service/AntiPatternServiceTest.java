package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
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
                insightService, hallucinationChecker, tokenBudgetManager, aiClient);
    }

    @Test
    @DisplayName("AI 客户端未配置时跳过并返回 null")
    void analyzeAntiPatterns_noAiClient_shouldReturnNull() {
        AntiPatternService noAi = new AntiPatternService(aiAnalysisService, projectService, scanService,
                insightService, hallucinationChecker, tokenBudgetManager, null);

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
}
