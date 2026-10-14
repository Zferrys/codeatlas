package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.service.AiModelService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminAiModelController 单元测试")
class AdminAiModelControllerTest {

    @Mock
    private AiModelService aiModelService;

    private AdminAiModelController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminAiModelController(aiModelService);
    }

    @Test
    @DisplayName("listModels — 模型清单原样返回")
    void listModelsPassesThrough() {
        Map<String, Object> models = Map.of("models", java.util.List.of("a", "b"), "current", "a");
        when(aiModelService.listModels()).thenReturn(models);

        ApiResponse<Map<String, Object>> response = controller.listModels();

        assertEquals(200, response.getCode());
        assertEquals(models, response.getData());
        verify(aiModelService).listModels();
    }

    @Test
    @DisplayName("switchModel — 先切换再读取当前模型，返回 {current: 新模型}")
    void switchModelSwitchesThenReadsCurrent() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("model", "deepseek-chat");
        when(aiModelService.getCurrentModelId()).thenReturn("deepseek-chat");

        ApiResponse<Map<String, Object>> response = controller.switchModel(body);

        assertEquals(200, response.getCode());
        assertEquals(Map.of("current", "deepseek-chat"), response.getData());
        assertEquals(1, response.getData().size());
        InOrder order = inOrder(aiModelService);
        order.verify(aiModelService).switchModel("deepseek-chat");
        order.verify(aiModelService).getCurrentModelId();
    }

    @Test
    @DisplayName("switchModel — body 缺 model 键时传 null 给服务")
    void switchModelPassesNullWhenMissing() {
        when(aiModelService.getCurrentModelId()).thenReturn("claude");

        ApiResponse<Map<String, Object>> response = controller.switchModel(new LinkedHashMap<>());

        assertEquals("claude", response.getData().get("current"));
        verify(aiModelService).switchModel(null);
    }

    @Test
    @DisplayName("testModel — 连通性测试结果原样返回")
    void testModelPassesThrough() {
        Map<String, String> body = Map.of("model", "gpt-4o");
        Map<String, Object> result = Map.of("ok", true, "latencyMs", 123);
        when(aiModelService.testModel("gpt-4o")).thenReturn(result);

        ApiResponse<Map<String, Object>> response = controller.testModel(body);

        assertEquals(200, response.getCode());
        assertEquals(result, response.getData());
        verify(aiModelService).testModel("gpt-4o");
    }
}
