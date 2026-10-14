package com.codeatlas.server.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 骨架级变异补杀：以探查子类驱动 AbstractInsightAnalysisService.execute 的
 * 精确值断言（预算公式/请求字段/洞察字段/进度推送/重试分支/日志标签）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AbstractInsightAnalysisService 骨架变异补杀（探查子类驱动）")
class AbstractInsightAnalysisServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final String TASK_ID = "aitask-skeleton-1";

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

    private AiTaskProgressRegistry spyRegistry;
    private SkeletonService service;

    @BeforeEach
    void setUp() {
        spyRegistry = spy(new AiTaskProgressRegistry(new ObjectMapper()));
        service = new SkeletonService(aiAnalysisService, projectService, scanService, insightService,
                hallucinationChecker, tokenBudgetManager, aiClient, spyRegistry);
    }

    /** 探查子类：所有钩子返回可精确断言的字面值 */
    private static final class SkeletonService extends AbstractInsightAnalysisService {

        SkeletonService(AiAnalysisService aiAnalysisService, ProjectService projectService,
                        ScanService scanService, InsightService insightService,
                        HallucinationChecker hallucinationChecker, TokenBudgetManager tokenBudgetManager,
                        AiClient aiClient, AiTaskProgressRegistry progressRegistry) {
            super(aiAnalysisService, projectService, scanService, insightService,
                    hallucinationChecker, tokenBudgetManager, aiClient, progressRegistry);
        }

        @Override
        protected String stage() {
            return "TEST_STAGE";
        }

        @Override
        protected String templatePath() {
            return "test/skeleton.md";
        }

        @Override
        protected String systemPrompt() {
            return "SYS-PROMPT-TEST";
        }

        @Override
        protected String retrySystemPrompt() {
            return "RETRY-SYS-TEST";
        }

        @Override
        protected String insightType() {
            return "TEST_INSIGHT";
        }

        @Override
        protected String insightTitle(String projectName) {
            return "测试标题 — " + projectName;
        }

        @Override
        protected String logLabel() {
            return "Test label";
        }

        @Override
        protected String hallucinationCheckLabel() {
            return "Test hallucination label";
        }

        @Override
        protected String insightLogLabel() {
            return "Test insight label";
        }

        @Override
        protected int maxTokens() {
            return 1234;
        }
    }

    private void stubProjectAndScan(int classCount) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName("Demo");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setId(100L);
        when(scanService.getLatestScanEntity(PROJECT_ID)).thenReturn(scan);
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 0; i < classCount; i++) {
            ClassSummaryEntity cls = new ClassSummaryEntity();
            cls.setFqn("com.demo.C" + i);
            cls.setSimpleName("C" + i);
            classes.add(cls);
        }
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(classes);
    }

    private static AiResponse response(String content, int tokensUsed) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokensUsed);
        response.setPromptTokens(tokensUsed / 2);
        response.setCompletionTokens(tokensUsed - tokensUsed / 2);
        response.setLatencyMs(120);
        return response;
    }

    private static HallucinationChecker.HallucinationResult noHallucination() {
        return new HallucinationChecker.HallucinationResult(false, Collections.emptyList());
    }

    private static HallucinationChecker.HallucinationResult hallucination() {
        return new HallucinationChecker.HallucinationResult(true,
                Collections.singletonList("com.fake.Ghost"));
    }

    /** 替换 PromptTemplate 静态缓存中的模板内容（finally 还原） */
    private static void withTemplate(String path, String content, Runnable body) throws Exception {
        Field cacheField = PromptTemplate.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) cacheField.get(null);
        String original = cache.put(path, content);
        try {
            body.run();
        } finally {
            if (original != null) {
                cache.put(path, original);
            } else {
                cache.remove(path);
            }
        }
    }

    private static List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    @Test
    @DisplayName("成功路径：变量合并进模板、预算=len*2/3+4096、请求/洞察/审计/进度逐值断言")
    void execute_success_shouldWireEveryHookToExactValues() throws Exception {
        stubProjectAndScan(1);
        when(aiAnalysisService.buildPromptVariables(any(Project.class), any(ScanRecord.class), anyList()))
                .thenReturn(new HashMap<>(Collections.singletonMap("title", "VARS")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(response("内容 com.demo.AService", 800));
        when(hallucinationChecker.check(anyString(), anyList())).thenReturn(noHallucination());
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.8));

        InsightEntity[] holder = new InsightEntity[1];
        withTemplate("test/skeleton.md", "[{{title}}]", () ->
                holder[0] = service.execute(PROJECT_ID, USER_ID, TASK_ID));

        InsightEntity insight = holder[0];
        assertEquals("TEST_INSIGHT", insight.getType());
        assertEquals("测试标题 — Demo", insight.getTitle());
        assertEquals("内容 com.demo.AService", insight.getContent());
        assertEquals(PROJECT_ID, insight.getProjectId());
        assertEquals(100L, insight.getScanId());
        assertEquals("[]", insight.getSources());
        assertEquals(0, BigDecimal.valueOf(0.8).compareTo(insight.getConfidence()));
        assertEquals("{\"tokensUsed\":800,\"latencyMs\":120,\"classCount\":1}", insight.getMetadata());
        verify(insightService).saveInsight(insight);

        // 渲染 [VARS]（6 字符）→ 估算 6*2/3+4096 = 4100
        verify(tokenBudgetManager).tryConsume(4100L);
        ArgumentCaptor<AiRequest> reqCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService).callAiWithResilience(reqCaptor.capture());
        AiRequest request = reqCaptor.getValue();
        assertEquals("[VARS]", request.getPrompt(), "buildPromptVariables 的结果必须合并进渲染变量");
        assertEquals("SYS-PROMPT-TEST", request.getSystemPrompt());
        assertEquals(0.3, request.getTemperature());
        assertEquals(1234, request.getMaxTokens());

        verify(aiAnalysisService).saveAiAuditLog("mock-model", "TEST_STAGE", PROJECT_ID, USER_ID,
                400L, 400L, 800L, 120L, null, true);
        verify(spyRegistry).publish(TASK_ID, "正在渲染分析提示词…");
        verify(spyRegistry).publish(TASK_ID, "AI 正在生成报告（通常需要 1–3 分钟）…");
        verify(spyRegistry).publish(TASK_ID, "正在做类名防幻觉校验…");
        verify(spyRegistry).publish(TASK_ID, "正在保存报告…");
    }

    @Test
    @DisplayName("恰 200 类允许重试：重试请求精确复用提示词/重试系统提示词/maxTokens 并推送重试进度")
    void execute_exactly200Classes_shouldRetryWithExactRequestValues() throws Exception {
        stubProjectAndScan(200);
        when(aiAnalysisService.buildPromptVariables(any(Project.class), any(ScanRecord.class), anyList()))
                .thenReturn(new HashMap<>());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(response("MAIN", 500), response("RETRY", 300));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(hallucination(), noHallucination());
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.7));

        InsightEntity[] holder = new InsightEntity[1];
        withTemplate("test/skeleton.md", "XYZ", () ->
                holder[0] = service.execute(PROJECT_ID, USER_ID, TASK_ID));

        assertEquals("RETRY", holder[0].getContent(), "200 类（未超限）必须允许幻觉重试");
        assertEquals("{\"tokensUsed\":800,\"latencyMs\":240,\"classCount\":200}", holder[0].getMetadata());
        ArgumentCaptor<AiRequest> reqCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService, times(2)).callAiWithResilience(reqCaptor.capture());
        AiRequest retry = reqCaptor.getAllValues().get(1);
        assertEquals("XYZ", retry.getPrompt(), "重试请求必须复用原提示词");
        assertEquals("RETRY-SYS-TEST", retry.getSystemPrompt());
        assertEquals(1234, retry.getMaxTokens());
        assertEquals(0.1, retry.getTemperature());
        verify(spyRegistry).publish(TASK_ID, "发现可疑类名，正在重新生成…");
    }

    @Test
    @DisplayName("重试调用无响应且已尝试重试：必须记录降级告警（else-if 分支不可静默）")
    void execute_retryAttemptedButCallNull_shouldLogDegradedWarning() throws Exception {
        stubProjectAndScan(1);
        when(aiAnalysisService.buildPromptVariables(any(Project.class), any(ScanRecord.class), anyList()))
                .thenReturn(new HashMap<>());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(response("MAIN", 500), null);
        when(hallucinationChecker.check(anyString(), anyList())).thenReturn(hallucination());
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.7));

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(SkeletonService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        InsightEntity[] holder = new InsightEntity[1];
        try {
            withTemplate("test/skeleton.md", "XYZ", () ->
                    holder[0] = service.execute(PROJECT_ID, USER_ID, TASK_ID));
        } finally {
            logger.detachAppender(appender);
        }

        assertEquals("MAIN", holder[0].getContent(), "重试无响应时必须降级使用首轮结果");
        assertTrue(formattedMessages(appender).contains(
                        "Test label hallucination retry call failed for projectId=1, using degraded result"),
                "retryAttempted=true 且重试无响应时必须记录降级告警");
    }
}
