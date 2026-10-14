package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConstitutionCheckService 单元测试（规约钩子 + 骨架）")
class ConstitutionCheckServiceTest {

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
    private ConstitutionRuleService constitutionRuleService;
    @Mock
    private HallucinationChecker hallucinationChecker;
    @Mock
    private TokenBudgetManager tokenBudgetManager;
    @Mock
    private AiClient aiClient;

    private ConstitutionCheckService service;

    @BeforeEach
    void setUp() {
        service = new ConstitutionCheckService(aiAnalysisService, projectService, scanService,
                insightService, constitutionRuleService, hallucinationChecker, tokenBudgetManager, aiClient,
                new AiTaskProgressRegistry(new ObjectMapper()));
    }

    private void stubProjectScanClasses() {
        Project project = new Project();
        project.setId(PROJECT_ID);
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

    private ConstitutionRuleEntity rule(String name, String severity, String description) {
        ConstitutionRuleEntity rule = new ConstitutionRuleEntity();
        rule.setName(name);
        rule.setSeverity(severity);
        rule.setDescription(description);
        rule.setRuleDefinition("def-" + name);
        return rule;
    }

    @Test
    @DisplayName("项目未启用任何规约时抛 400 且不调用 AI")
    void analyzeConstitution_noRules_shouldThrow400() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.analyzeConstitution(PROJECT_ID, USER_ID));
        assertEquals(400, ex.getCode());
        assertEquals("当前项目没有启用的架构规则，请先在规约管理中启用", ex.getMessage());
    }

    @Test
    @DisplayName("成功路径：规约定义注入提示词、metadata 带 ruleCount 并落 CONSTITUTION_CHECK 洞察")
    void analyzeConstitution_success_shouldInjectRulesAndSaveInsight() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Arrays.asList(
                rule("命名规范", "MAJOR", "类名必须使用大驼峰"),
                rule("禁止循环依赖", "CRITICAL", "模块之间不得循环引用")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("规约检查结果：全部通过");
        response.setTokensUsed(600);
        response.setPromptTokens(300);
        response.setCompletionTokens(300);
        response.setLatencyMs(90);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(response);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.9));

        InsightEntity insight = service.analyzeConstitution(PROJECT_ID, USER_ID);

        assertNotNull(insight);
        assertEquals("CONSTITUTION_CHECK", insight.getType());
        assertEquals("规约合规检查 — Demo", insight.getTitle());
        assertTrue(insight.getMetadata().contains("\"ruleCount\":2"));

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService).callAiWithResilience(requestCaptor.capture());
        String prompt = requestCaptor.getValue().getPrompt();
        assertTrue(prompt.contains("命名规范"), "规约名称应注入提示词");
        assertTrue(prompt.contains("禁止循环依赖"), "规约名称应注入提示词");
        assertTrue(prompt.contains("类名必须使用大驼峰"), "规约描述应注入提示词");

        verify(insightService).saveInsight(insight);
        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("CONSTITUTION_CHECK"),
                eq(PROJECT_ID), eq(USER_ID), eq(300L), eq(300L), eq(600L), eq(90L),
                isNull(), eq(true));
    }

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    private static final String TASK_ID = "aitask-constitution-01";

    @Test
    @DisplayName("taskId 重载：成功路径落 CONSTITUTION_CHECK 洞察")
    void analyzeConstitution_taskIdOverload_shouldSaveInsight() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID))
                .thenReturn(Collections.singletonList(rule("命名规范", "MAJOR", "类名必须使用大驼峰")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("规约检查结果：全部通过");
        response.setTokensUsed(600);
        response.setPromptTokens(300);
        response.setCompletionTokens(300);
        response.setLatencyMs(90);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(response);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.9));

        InsightEntity insight = service.analyzeConstitution(PROJECT_ID, USER_ID, TASK_ID);

        assertNotNull(insight);
        assertEquals("CONSTITUTION_CHECK", insight.getType());
        verify(insightService).saveInsight(insight);
    }

    @Test
    @DisplayName("幻觉重试调用返回 null：降级使用原文，仅主调用 Token 入账")
    void analyzeConstitution_retryCallReturnsNull_shouldKeepOriginalContent() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID))
                .thenReturn(Collections.singletonList(rule("命名规范", "MAJOR", "类名必须使用大驼峰")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("原始规约结果 com.demo.AService");
        response.setTokensUsed(600);
        response.setPromptTokens(300);
        response.setCompletionTokens(300);
        response.setLatencyMs(90);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class)))
                .thenReturn(response)
                .thenReturn(null);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(
                        true, Collections.singletonList("com.fake.Ghost")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.6));

        InsightEntity insight = service.analyzeConstitution(PROJECT_ID, USER_ID);

        assertEquals("原始规约结果 com.demo.AService", insight.getContent());
        verify(aiAnalysisService).saveAiAuditLog(eq("mock-model"), eq("CONSTITUTION_CHECK"),
                eq(PROJECT_ID), eq(USER_ID), eq(300L), eq(300L), eq(600L), eq(90L),
                isNull(), eq(true));
    }

    // ==================== 变异补杀：钩子字面值 / formatRules / 日志标签精确断言 ====================

    private static final String CONSTITUTION_SYSTEM_PROMPT =
            "你是一位资深软件架构师。请对照架构规约逐条判定项目的合规情况，给出违规类、原因、修复建议与严重度。用中文回答，类名保留原文。";
    private static final String CONSTITUTION_RETRY_SYSTEM_PROMPT =
            "你是一位资深软件架构师。请重新输出规约合规检查结果，只引用给定的类列表。用中文回答。";

    private static void attachAppender(Logger logger, ListAppender<ILoggingEvent> appender) {
        appender.start();
        logger.addAppender(appender);
    }

    private static List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }

    @Test
    @DisplayName("formatRules 精确渲染：severity 原值/null 兜底 MAJOR、定义按非空追加，主请求系统提示词为字面值")
    void analyzeConstitution_formatRules_shouldRenderExactLinesAndSystemPrompt() {
        stubProjectScanClasses();
        ConstitutionRuleEntity critical = rule("禁止循环依赖", "CRITICAL", "模块之间不得循环引用");
        ConstitutionRuleEntity noSeverity = new ConstitutionRuleEntity();
        noSeverity.setName("命名规范");
        noSeverity.setSeverity(null);
        noSeverity.setDescription("类名必须使用大驼峰");
        noSeverity.setRuleDefinition("def-命名规范");
        ConstitutionRuleEntity emptyDefinition = new ConstitutionRuleEntity();
        emptyDefinition.setName("包结构");
        emptyDefinition.setSeverity("MINOR");
        emptyDefinition.setDescription(null);
        emptyDefinition.setRuleDefinition("");
        ConstitutionRuleEntity nullDefinition = new ConstitutionRuleEntity();
        nullDefinition.setName("日志规范");
        nullDefinition.setSeverity("MAJOR");
        nullDefinition.setDescription(null);
        nullDefinition.setRuleDefinition(null);
        when(constitutionRuleService.getRules(PROJECT_ID))
                .thenReturn(Arrays.asList(critical, noSeverity, emptyDefinition, nullDefinition));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("规约结果");
        response.setTokensUsed(100);
        response.setPromptTokens(50);
        response.setCompletionTokens(50);
        response.setLatencyMs(10);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(response);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.9));

        InsightEntity insight = service.analyzeConstitution(PROJECT_ID, USER_ID);

        assertEquals("{\"tokensUsed\":100,\"latencyMs\":10,\"classCount\":1,\"ruleCount\":4}",
                insight.getMetadata());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService).callAiWithResilience(captor.capture());
        AiRequest request = captor.getValue();
        assertEquals(CONSTITUTION_SYSTEM_PROMPT, request.getSystemPrompt());
        String rendered = request.getPrompt();
        assertTrue(rendered.contains("- [CRITICAL] 禁止循环依赖：模块之间不得循环引用\n  定义: def-禁止循环依赖\n"),
                "非空 severity 原样渲染、非空定义必须追加定义行");
        assertTrue(rendered.contains("- [MAJOR] 命名规范：类名必须使用大驼峰\n  定义: def-命名规范\n"),
                "null severity 必须兜底 MAJOR");
        assertTrue(rendered.contains("- [MINOR] 包结构\n"), "null 描述不得追加描述分隔符");
        assertTrue(rendered.contains("- [MAJOR] 日志规范\n"), "null 定义必须安全跳过");
        assertFalse(rendered.contains("定义: \n"), "空定义不得追加定义行");
    }

    @Test
    @DisplayName("幻觉重试请求：原提示词精确保留、系统提示词/温度/maxTokens 按重试口径")
    void analyzeConstitution_retryRequest_shouldUseExactRetrySystemPrompt() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID))
                .thenReturn(Collections.singletonList(rule("命名规范", "MAJOR", "类名必须使用大驼峰")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse main = new AiResponse();
        main.setContent("MAIN com.demo.AService");
        main.setTokensUsed(400);
        main.setPromptTokens(200);
        main.setCompletionTokens(200);
        main.setLatencyMs(60);
        AiResponse retry = new AiResponse();
        retry.setContent("RETRY-CONTENT");
        retry.setTokensUsed(200);
        retry.setPromptTokens(100);
        retry.setCompletionTokens(100);
        retry.setLatencyMs(40);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(main, retry);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(
                                true, Collections.singletonList("com.fake.Ghost")),
                        new HallucinationChecker.HallucinationResult(false, Collections.emptyList()));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.5));

        InsightEntity insight = service.analyzeConstitution(PROJECT_ID, USER_ID);

        assertEquals("RETRY-CONTENT", insight.getContent());
        assertEquals("{\"tokensUsed\":600,\"latencyMs\":100,\"classCount\":1,\"ruleCount\":1}",
                insight.getMetadata());
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiAnalysisService, times(2)).callAiWithResilience(captor.capture());
        AiRequest mainReq = captor.getAllValues().get(0);
        AiRequest retryReq = captor.getAllValues().get(1);
        assertEquals(CONSTITUTION_SYSTEM_PROMPT, mainReq.getSystemPrompt());
        assertEquals(mainReq.getPrompt(), retryReq.getPrompt(), "重试必须复用原提示词");
        assertEquals(CONSTITUTION_RETRY_SYSTEM_PROMPT, retryReq.getSystemPrompt());
        assertEquals(0.1, retryReq.getTemperature());
        assertEquals(8192, retryReq.getMaxTokens());
    }

    @Test
    @DisplayName("无 AI 客户端：跳过日志使用 Constitution check 标签且返回 null")
    void analyzeConstitution_noAiClient_shouldLogSkipWithConstitutionLabel() {
        ConstitutionCheckService skipService = new ConstitutionCheckService(aiAnalysisService, projectService,
                scanService, insightService, constitutionRuleService, hallucinationChecker, tokenBudgetManager,
                null, new AiTaskProgressRegistry(new ObjectMapper()));
        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ConstitutionCheckService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        attachAppender(logger, appender);
        InsightEntity result;
        try {
            result = skipService.analyzeConstitution(PROJECT_ID, USER_ID);
        } finally {
            logger.detachAppender(appender);
        }

        assertNull(result);
        assertTrue(formattedMessages(appender).contains("Constitution check skipped: no AI client for projectId=1"),
                "跳过日志必须携带 Constitution check 标签");
    }

    @Test
    @DisplayName("幻觉检查与落库日志：分别使用 hallucination/insight 专用标签")
    void analyzeConstitution_hallucinationAndInsightLogs_shouldUseConstitutionLabels() {
        stubProjectScanClasses();
        when(constitutionRuleService.getRules(PROJECT_ID))
                .thenReturn(Collections.singletonList(rule("命名规范", "MAJOR", "类名必须使用大驼峰")));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = new AiResponse();
        response.setContent("规约结果 com.demo.AService");
        response.setTokensUsed(200);
        response.setPromptTokens(100);
        response.setCompletionTokens(100);
        response.setLatencyMs(50);
        when(aiAnalysisService.callAiWithResilience(any(AiRequest.class))).thenReturn(response);
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(new HallucinationChecker.HallucinationResult(
                        true, Collections.singletonList("com.fake.Ghost")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.DEGRADED);
        when(aiAnalysisService.calculateConfidenceSimple(anyString(), anyList()))
                .thenReturn(BigDecimal.valueOf(0.4));

        Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ConstitutionCheckService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        attachAppender(logger, appender);
        try {
            service.analyzeConstitution(PROJECT_ID, USER_ID);
        } finally {
            logger.detachAppender(appender);
        }

        List<String> messages = formattedMessages(appender);
        assertTrue(messages.contains("Constitution check hallucination: fakeClasses=1, action=DEGRADED"),
                "幻觉检查日志必须携带专用标签与动作");
        assertTrue(messages.contains("Constitution check insight saved: id=null, projectId=1"),
                "落库日志必须携带专用标签");
    }
}
