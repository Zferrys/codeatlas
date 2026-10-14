package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.Message;
import com.codeatlas.engine.ai.ToolCall;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.AiAuditLogEntity;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgenticSearchService 单元测试")
class AgenticSearchServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final String TASK_ID = "aitask-agentic-1";

    @Mock
    private AiClient aiClient;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ProjectService projectService;
    @Mock
    private ScanService scanService;
    @Mock
    private TokenBudgetManager tokenBudgetManager;
    @Mock
    private AiAuditLogMapper aiAuditLogMapper;
    @Mock
    private ChatHistoryService chatHistoryService;

    private AgenticSearchService agenticSearchService;

    private final AtomicInteger toolResultCounter = new AtomicInteger();
    private final AiTaskProgressRegistry progressRegistry = new AiTaskProgressRegistry(new ObjectMapper());

    @BeforeEach
    void setUp() {
        agenticSearchService = new AgenticSearchService(aiClient, toolRegistry,
                projectService, scanService, tokenBudgetManager, aiAuditLogMapper,
                new EvidenceVerifier(), chatHistoryService, progressRegistry);

        Project project = new Project();
        project.setName("TestProject");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(Collections.emptyList());
        lenient().when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        lenient().when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenAnswer(invocation -> "工具结果" + toolResultCounter.incrementAndGet());
    }

    @Test
    @DisplayName("五轮工具调用用尽后，移除工具强制总结并返回答案")
    void search_toolRoundsExhausted_shouldForceFinalSynthesis() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"), toolCallResponse("call_2"), toolCallResponse("call_3"),
                        toolCallResponse("call_4"), toolCallResponse("call_5"))
                .thenReturn(textResponse("最终答案"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "项目里有哪些 Controller？", USER_ID);

        assertEquals("最终答案", result.get("answer"));

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(6)).chat(requestCaptor.capture());
        AiRequest synthesisRequest = requestCaptor.getAllValues().get(5);
        assertNull(synthesisRequest.getTools(), "强制总结请求不应携带工具");

        List<Message> synthesisMessages = synthesisRequest.getMessages();
        long toolMessages = synthesisMessages.stream()
                .filter(msg -> "tool".equals(msg.getRole()))
                .count();
        assertEquals(5, toolMessages, "五轮工具结果都必须进入强制总结请求");
        assertTrue(synthesisMessages.stream().anyMatch(msg -> "tool".equals(msg.getRole())
                        && "工具结果5".equals(msg.getContent())),
                "第 5 轮工具结果必须进入强制总结请求");
        assertEquals("user", synthesisMessages.get(synthesisMessages.size() - 1).getRole());

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.TRUE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("模型在轮次内给出答案时，不再发起强制总结")
    void search_modelAnswersWithinLimit_shouldNotForceSynthesis() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("在轮次内给出的答案"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "简单问题", USER_ID);

        assertEquals("在轮次内给出的答案", result.get("answer"));
        verify(aiClient, times(2)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("强制总结调用失败时，保留截断提示并记录失败审计")
    void search_synthesisCallFails_shouldKeepTruncationFallback() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"), toolCallResponse("call_2"), toolCallResponse("call_3"),
                        toolCallResponse("call_4"), toolCallResponse("call_5"))
                .thenThrow(new RuntimeException("boom"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("分析未能在 5 轮内完成，请尝试更具体的问题。", result.get("answer"));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("AI 调用抛异常时返回通用文案且仍写审计日志")
    void search_aiCallFails_shouldHideExceptionDetailAndWriteAudit() {
        when(aiClient.chat(any(AiRequest.class))).thenThrow(new RuntimeException("secret-internal-detail"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        String answer = String.valueOf(result.get("answer"));
        assertFalse(answer.contains("secret-internal-detail"), "异常细节不得回传前端");
        assertFalse(answer.contains("AI 调用失败: "), "不应再拼接异常原文");

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess(), "失败路径必须落审计（配额已扣）");
    }

    @Test
    @DisplayName("模型全部熔断（响应为 null）时返回熔断文案且仍写审计日志")
    void search_nullResponse_shouldWriteAudit() {
        when(aiClient.chat(any(AiRequest.class))).thenReturn(null);

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("AI 服务暂不可用（所有模型已熔断），请稍后重试。", result.get("answer"));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess(), "失败路径必须落审计（配额已扣）");
    }

    @Test
    @DisplayName("答案含未溯源类引用时触发修正轮并采用修正版")
    void search_unsourcedReferences_shouldTriggerCorrectionAndAdoptCorrectedAnswer() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenReturn("找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("GhostService 与 PhantomService 是核心类，RealService 也参与其中。"))
                .thenReturn(textResponse("RealService 是核心服务类。"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("RealService 是核心服务类。", result.get("answer"));

        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertNotNull(verification, "验证报告必须挂到结果中");
        assertEquals(Boolean.TRUE, verification.get("corrected"));
        assertEquals(1, verification.get("correctionRounds"));
        assertTrue(((List<?>) verification.get("unsourced")).isEmpty(), "修正后应无未溯源引用");

        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(3)).chat(requestCaptor.capture());
        AiRequest correctionRequest = requestCaptor.getAllValues().get(2);
        assertNull(correctionRequest.getTools(), "修正轮请求不应携带工具");
        assertEquals(0.1, correctionRequest.getTemperature());
        List<Message> correctionMessages = correctionRequest.getMessages();
        assertEquals("user", correctionMessages.get(correctionMessages.size() - 1).getRole());
        assertTrue(correctionMessages.get(correctionMessages.size() - 1).getContent().contains("GhostService"),
                "修正请求必须包含未溯源类名清单");
    }

    @Test
    @DisplayName("答案类引用全部溯源时不做修正轮")
    void search_allRefsSourced_shouldNotTriggerCorrection() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenReturn("找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("RealService 是核心服务类。"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("RealService 是核心服务类。", result.get("answer"));

        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertNotNull(verification);
        assertEquals(Boolean.FALSE, verification.get("corrected"));
        verify(aiClient, times(2)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("token 预算拒绝修正轮时保留原答案，correctionRounds 上报为 0")
    void search_correctionBudgetRejected_shouldKeepAnswerAndReportZeroRounds() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenReturn("找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");
        // 首次调用是入口预算闸门（放行），第二次是修正轮预算（拒绝）
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true, false);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("GhostService 与 PhantomService 是核心类，RealService 也参与其中。"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("GhostService 与 PhantomService 是核心类，RealService 也参与其中。", result.get("answer"),
                "预算拒绝时保留原答案");

        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertNotNull(verification);
        assertEquals(Boolean.FALSE, verification.get("corrected"));
        assertEquals(0, verification.get("correctionRounds"), "未实际发起修正轮时不应上报轮次");
        verify(aiClient, times(2)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("用户终止后收尾审计：success=false 且 errorMessage 为 Cancelled by user（而非 Truncated）")
    void search_cancelled_shouldAuditCancelledByUser() {
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        progressRegistry.cancel(TASK_ID);

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID, TASK_ID);

        assertEquals("本次回答已被用户终止", result.get("answer"));
        assertEquals(Boolean.TRUE, result.get("cancelled"));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess());
        assertEquals("Cancelled by user", auditCaptor.getValue().getErrorMessage(),
                "终止审计不得再复用 Truncated 截断文案");
    }

    private ClassSummaryEntity makeClass(String fqn, String simpleName) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setFqn(fqn);
        entity.setSimpleName(simpleName);
        return entity;
    }

    private AiResponse toolCallResponse(String toolCallId) {
        ToolCall call = new ToolCall();
        call.setId(toolCallId);
        call.setName("search_classes");
        call.setArguments(Collections.singletonMap("keyword", "demo"));

        AiResponse response = new AiResponse();
        response.setToolCalls(Collections.singletonList(call));
        response.setTokensUsed(100);
        return response;
    }

    private AiResponse textResponse(String content) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(50);
        return response;
    }

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    @Test
    @DisplayName("未配置 AI 客户端：直接返回配置提示且不调用 AI")
    void search_noAiClient_shouldReturnConfigHint() {
        // 该路径在读取项目数据前即返回：手动消费 setUp 严格桩，避免严格模式误报未使用
        projectService.getProjectEntity(PROJECT_ID);
        scanService.getClassSummaries(PROJECT_ID);

        Map<String, Object> result = serviceWithVerifier(null, new EvidenceVerifier())
                .search(PROJECT_ID, "问题", USER_ID);

        assertEquals("AI 服务未配置，无法执行智能搜索。请检查 API Key 设置。", result.get("answer"));
        verify(aiClient, never()).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("项目不存在：返回提示且不调用 AI")
    void search_projectMissing_shouldReturnNotFound() {
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(null);
        // 该路径在类摘要加载前返回：手动消费 setUp 严格桩，避免严格模式误报未使用
        scanService.getClassSummaries(PROJECT_ID);

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("项目不存在", result.get("answer"));
        verify(aiClient, never()).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("问题净化后为空：返回无效提示且不调用 AI")
    void search_sanitizedQuestionEmpty_shouldReturnInvalidHint() {
        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "   ", USER_ID);

        assertEquals("   ", result.get("question"));
        assertEquals("问题内容无效（净化后为空），请重新输入。", result.get("answer"));
        verify(aiClient, never()).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("入口预算耗尽：返回配额提示且不调用 AI")
    void search_budgetExhaustedAtEntry_shouldReturnQuotaHint() {
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(false);

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("本月 AI Token 配额已用完，请下月再试。", result.get("answer"));
        verify(aiClient, never()).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("工具轮带正文时助手消息携带正文并进入下一轮请求")
    void search_toolTurnWithContent_shouldCarryContentIntoAssistantMessage() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1", "我先检索一下"))
                .thenReturn(textResponse("最终答案"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("最终答案", result.get("answer"));
        ArgumentCaptor<AiRequest> requestCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(2)).chat(requestCaptor.capture());
        assertTrue(requestCaptor.getAllValues().get(1).getMessages().stream()
                        .anyMatch(msg -> "assistant".equals(msg.getRole()) && "我先检索一下".equals(msg.getContent())),
                "工具轮的助手正文必须进入下一轮请求上下文");
    }

    @Test
    @DisplayName("五轮工具用尽后用户终止：不再强制总结，按取消收尾")
    void search_cancelledAfterExhaustedRounds_shouldFinishCancelled() {
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 5) {
                progressRegistry.cancel(TASK_ID);
            }
            return toolCallResponse("call_" + calls.get());
        });

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID, TASK_ID);

        assertEquals("本次回答已被用户终止", result.get("answer"));
        assertEquals(Boolean.TRUE, result.get("cancelled"));
        verify(aiClient, times(5)).chat(any(AiRequest.class));
        verify(chatHistoryService, never()).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("Cancelled by user", auditCaptor.getValue().getErrorMessage());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("成功答案产出后、证据校验前终止：按取消收尾且不落历史")
    void search_cancelledAfterSuccessBeforeVerification_shouldFinishCancelled() {
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return toolCallResponse("call_1");
            }
            progressRegistry.cancel(TASK_ID);
            return textResponse("最终答案");
        });

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID, TASK_ID);

        assertEquals("本次回答已被用户终止", result.get("answer"));
        assertEquals(Boolean.TRUE, result.get("cancelled"));
        verify(chatHistoryService, never()).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("Cancelled by user", auditCaptor.getValue().getErrorMessage());
    }

    @Test
    @DisplayName("修正结果未减少异常数：保留原答案且不下调报告")
    void search_correctionNoImprovement_shouldKeepOriginalAnswer() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenReturn("找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("GhostService 与 PhantomService 是核心类，RealService 也参与其中。"))
                .thenReturn(textResponse("GhostService 与 PhantomService 仍然是核心类。"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("GhostService 与 PhantomService 是核心类，RealService 也参与其中。", result.get("answer"),
                "未改善时应保留原答案");
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertEquals(Boolean.FALSE, verification.get("corrected"));
        assertEquals(1, verification.get("correctionRounds"));
        assertEquals(2, ((List<?>) verification.get("unsourced")).size(), "未采用修正时报告保持原异常数");
        verify(aiClient, times(3)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("修正轮调用抛异常：被吞并保留原答案，修正轮次照常上报")
    void search_correctionRoundThrows_shouldKeepOriginalAnswer() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
                .thenReturn("找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("GhostService 与 PhantomService 是核心类，RealService 也参与其中。"))
                .thenThrow(new RuntimeException("correction boom"));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("GhostService 与 PhantomService 是核心类，RealService 也参与其中。", result.get("answer"));
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = (Map<String, Object>) result.get("verification");
        assertEquals(Boolean.FALSE, verification.get("corrected"));
        assertEquals(1, verification.get("correctionRounds"), "异常前置的轮次计数已置 1");
        verify(aiClient, times(3)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("证据校验自身抛异常：非致命降级，答案照常交付且不挂验证报告")
    void search_verificationThrows_shouldDeliverAnswerWithoutReport() {
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        EvidenceVerifier failingVerifier = mock(EvidenceVerifier.class);
        when(failingVerifier.verify(anyString(), anyList(), anyList()))
                .thenThrow(new RuntimeException("verifier boom"));
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("RealService 是核心服务类。"));

        Map<String, Object> result = serviceWithVerifier(aiClient, failingVerifier)
                .search(PROJECT_ID, "核心类有哪些？", USER_ID);

        assertEquals("RealService 是核心服务类。", result.get("answer"));
        assertNull(result.get("verification"), "校验异常时不得挂验证报告");
        verify(chatHistoryService).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.TRUE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("证据校验期间终止：回答丢弃、不落历史，按取消审计")
    void search_cancelledDuringVerification_shouldFinishCancelled() {
        progressRegistry.subscribe(TASK_ID, PROJECT_ID);
        when(scanService.getClassSummaries(PROJECT_ID))
                .thenReturn(Collections.singletonList(makeClass("com.demo.RealService", "RealService")));
        EvidenceVerifier cancellingVerifier = mock(EvidenceVerifier.class);
        when(cancellingVerifier.verify(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            progressRegistry.cancel(TASK_ID);
            return new EvidenceVerifier().verify(invocation.getArgument(0),
                    invocation.getArgument(1), invocation.getArgument(2));
        });
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("RealService 是核心服务类。"));

        Map<String, Object> result = serviceWithVerifier(aiClient, cancellingVerifier)
                .search(PROJECT_ID, "核心类有哪些？", USER_ID, TASK_ID);

        assertEquals("本次回答已被用户终止", result.get("answer"));
        assertEquals(Boolean.TRUE, result.get("cancelled"));
        verify(chatHistoryService, never()).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals("Cancelled by user", auditCaptor.getValue().getErrorMessage());
        assertEquals(Boolean.FALSE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("模型无正文（占位文案）时成功交付但不落对话历史")
    void search_placeholderAnswer_shouldNotRecordHistory() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse(null));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("(AI 未返回内容)", result.get("answer"));
        verify(chatHistoryService, never()).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.TRUE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("对话历史入库抛异常：被吞并且不影响答案与审计")
    void search_historyRecordThrows_shouldBeSwallowed() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("最终答案"));
        doThrow(new RuntimeException("history db down"))
                .when(chatHistoryService).record(any(ChatHistoryEntity.class));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("最终答案", result.get("answer"));
        verify(chatHistoryService).record(any(ChatHistoryEntity.class));

        ArgumentCaptor<AiAuditLogEntity> auditCaptor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(auditCaptor.capture());
        assertEquals(Boolean.TRUE, auditCaptor.getValue().getSuccess());
    }

    @Test
    @DisplayName("审计入库抛异常：被吞并且不影响结果返回")
    void search_auditInsertThrows_shouldBeSwallowed() {
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(toolCallResponse("call_1"))
                .thenReturn(textResponse("最终答案"));
        doThrow(new RuntimeException("audit db down"))
                .when(aiAuditLogMapper).insert(any(AiAuditLogEntity.class));

        Map<String, Object> result = agenticSearchService.search(PROJECT_ID, "问题", USER_ID);

        assertEquals("最终答案", result.get("answer"));
        verify(aiAuditLogMapper).insert(any(AiAuditLogEntity.class));
        verify(chatHistoryService).record(any(ChatHistoryEntity.class));
    }

    private AgenticSearchService serviceWithVerifier(AiClient client, EvidenceVerifier verifier) {
        return new AgenticSearchService(client, toolRegistry, projectService, scanService,
                tokenBudgetManager, aiAuditLogMapper, verifier, chatHistoryService, progressRegistry);
    }

    private AiResponse toolCallResponse(String toolCallId, String content) {
        AiResponse response = toolCallResponse(toolCallId);
        response.setContent(content);
        return response;
    }
}
