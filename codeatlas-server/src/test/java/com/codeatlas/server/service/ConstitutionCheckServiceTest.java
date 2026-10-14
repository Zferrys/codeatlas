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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
