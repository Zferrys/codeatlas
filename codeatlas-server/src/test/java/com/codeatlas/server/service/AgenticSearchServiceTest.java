package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.Message;
import com.codeatlas.engine.ai.ToolCall;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.AiAuditLogEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.AiAuditLogMapper;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AgenticSearchService 单元测试")
class AgenticSearchServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;

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

    private AgenticSearchService agenticSearchService;

    private final AtomicInteger toolResultCounter = new AtomicInteger();

    @BeforeEach
    void setUp() {
        agenticSearchService = new AgenticSearchService(aiClient, toolRegistry,
                projectService, scanService, tokenBudgetManager, aiAuditLogMapper);

        Project project = new Project();
        project.setName("TestProject");
        when(projectService.getProjectEntity(PROJECT_ID)).thenReturn(project);
        when(scanService.getClassSummaries(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(toolRegistry.execute(any(ToolCall.class), anyLong()))
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
}
