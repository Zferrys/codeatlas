package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.StreamCallback;
import com.codeatlas.server.service.AiModelService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiModelRouter 单元测试（当前模型优先 + 降级语义 + 健康检查）")
class AiModelRouterTest {

    @Mock
    private AiModelService modelService;
    @Mock
    private AiClient primaryClient;
    @Mock
    private AiClient fallbackClient;

    private final StreamCallback callback = token -> { };

    @Test
    @DisplayName("chat：调用链为空时抛出无可用模型异常（精确消息）")
    void chat_emptyChain_throwsNoModelException() {
        when(modelService.resolveChain()).thenReturn(Collections.emptyList());
        AiModelRouter router = new AiModelRouter(modelService);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> router.chat(new AiRequest()));

        assertEquals("No AI model available (no API key configured)", ex.getMessage());
        assertNull(ex.getCause());
        verify(modelService).resolveChain();
    }

    @Test
    @DisplayName("chatStream：调用链为空时抛出无可用模型异常（精确消息）")
    void chatStream_emptyChain_throwsNoModelException() {
        when(modelService.resolveChain()).thenReturn(Collections.emptyList());
        AiModelRouter router = new AiModelRouter(modelService);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> router.chatStream(new AiRequest(), callback));

        assertEquals("No AI model available (no API key configured)", ex.getMessage());
        assertNull(ex.getCause());
    }

    @Test
    @DisplayName("chat：首选模型成功时标记 modelUsed 且不标记 fallback")
    void chat_primarySucceeds_setsModelUsedWithoutFallback() {
        AiRequest request = new AiRequest();
        AiResponse response = AiResponse.of("ok", 10, 5L);
        when(modelService.resolveChain()).thenReturn(Collections.singletonList(primaryClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        when(primaryClient.chat(request)).thenReturn(response);
        AiModelRouter router = new AiModelRouter(modelService);

        AiResponse result = router.chat(request);

        assertSame(response, result);
        assertEquals("claude-x", result.getModelUsed());
        assertFalse(result.isFallback());
        verify(primaryClient).chat(request);
        // 一次用于尝试日志，一次用于 setModelUsed
        verify(primaryClient, times(2)).getModelName();
    }

    @Test
    @DisplayName("chat：首选失败降级到次选，返回次选响应并标记 fallback=true")
    void chat_primaryFails_fallbackMarksFallbackTrue() {
        AiRequest request = new AiRequest();
        AiResponse fallbackResponse = AiResponse.of("fallback-ok", 20, 9L);
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        when(fallbackClient.getModelName()).thenReturn("deepseek-y");
        when(primaryClient.chat(request)).thenThrow(new RuntimeException("primary boom"));
        when(fallbackClient.chat(request)).thenReturn(fallbackResponse);
        AiModelRouter router = new AiModelRouter(modelService);

        AiResponse result = router.chat(request);

        assertSame(fallbackResponse, result);
        assertEquals("deepseek-y", result.getModelUsed());
        assertTrue(result.isFallback());
        InOrder order = inOrder(primaryClient, fallbackClient);
        order.verify(primaryClient).chat(request);
        order.verify(fallbackClient).chat(request);
    }

    @Test
    @DisplayName("chat：全部模型失败时抛出携带当前模型 ID 与最后一个原因的异常")
    void chat_allFail_throwsWithCurrentModelAndLastCause() {
        AiRequest request = new AiRequest();
        RuntimeException first = new RuntimeException("primary boom");
        RuntimeException second = new RuntimeException("fallback boom");
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        when(fallbackClient.getModelName()).thenReturn("deepseek-y");
        when(primaryClient.chat(request)).thenThrow(first);
        when(fallbackClient.chat(request)).thenThrow(second);
        when(modelService.getCurrentModelId()).thenReturn("claude-x");
        AiModelRouter router = new AiModelRouter(modelService);

        RuntimeException ex = assertThrows(RuntimeException.class, () -> router.chat(request));

        assertEquals("All AI models exhausted (current=claude-x)", ex.getMessage());
        assertSame(second, ex.getCause());
        verify(primaryClient).chat(request);
        verify(fallbackClient).chat(request);
    }

    @Test
    @DisplayName("chatStream：首选成功后立即返回，不再尝试后续模型")
    void chatStream_primarySucceeds_stopsChain() {
        AiRequest request = new AiRequest();
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        AiModelRouter router = new AiModelRouter(modelService);

        assertDoesNotThrow(() -> router.chatStream(request, callback));

        verify(primaryClient).chatStream(request, callback);
        verifyNoInteractions(fallbackClient);
    }

    @Test
    @DisplayName("chatStream：首选失败后降级到次选并按顺序调用")
    void chatStream_primaryFails_fallbackSucceeds() {
        AiRequest request = new AiRequest();
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        when(fallbackClient.getModelName()).thenReturn("deepseek-y");
        doThrow(new RuntimeException("stream boom")).when(primaryClient).chatStream(request, callback);
        AiModelRouter router = new AiModelRouter(modelService);

        assertDoesNotThrow(() -> router.chatStream(request, callback));

        InOrder order = inOrder(primaryClient, fallbackClient);
        order.verify(primaryClient).chatStream(request, callback);
        order.verify(fallbackClient).chatStream(request, callback);
    }

    @Test
    @DisplayName("chatStream：全部模型流式失败时抛出携带当前模型 ID 与最后原因的异常")
    void chatStream_allFail_throwsWithCurrentModelAndLastCause() {
        AiRequest request = new AiRequest();
        RuntimeException first = new RuntimeException("stream boom 1");
        RuntimeException second = new RuntimeException("stream boom 2");
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.getModelName()).thenReturn("claude-x");
        when(fallbackClient.getModelName()).thenReturn("deepseek-y");
        doThrow(first).when(primaryClient).chatStream(request, callback);
        doThrow(second).when(fallbackClient).chatStream(request, callback);
        when(modelService.getCurrentModelId()).thenReturn("deepseek-y");
        AiModelRouter router = new AiModelRouter(modelService);

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> router.chatStream(request, callback));

        assertEquals("All AI models exhausted for streaming (current=deepseek-y)", ex.getMessage());
        assertSame(second, ex.getCause());
    }

    @Test
    @DisplayName("getModelName：当前模型非空时原样返回，空/未配置时返回 unknown")
    void getModelName_returnsCurrentOrUnknown() {
        when(modelService.getCurrentModelId()).thenReturn("claude-x", "", null);
        AiModelRouter router = new AiModelRouter(modelService);

        assertEquals("claude-x", router.getModelName());
        assertEquals("unknown", router.getModelName());
        assertEquals("unknown", router.getModelName());
    }

    @Test
    @DisplayName("healthCheck：首个模型健康时返回 true 且不再检查后续模型")
    void healthCheck_firstHealthy_returnsTrueWithoutFurtherChecks() {
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.healthCheck()).thenReturn(true);
        AiModelRouter router = new AiModelRouter(modelService);

        assertTrue(router.healthCheck());

        verify(primaryClient).healthCheck();
        verify(fallbackClient, never()).healthCheck();
    }

    @Test
    @DisplayName("healthCheck：降级模型健康时返回 true")
    void healthCheck_fallbackHealthy_returnsTrue() {
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.healthCheck()).thenReturn(false);
        when(fallbackClient.healthCheck()).thenReturn(true);
        AiModelRouter router = new AiModelRouter(modelService);

        assertTrue(router.healthCheck());

        verify(primaryClient).healthCheck();
        verify(fallbackClient).healthCheck();
    }

    @Test
    @DisplayName("healthCheck：全部模型不健康时返回 false")
    void healthCheck_noneHealthy_returnsFalse() {
        when(modelService.resolveChain()).thenReturn(Arrays.asList(primaryClient, fallbackClient));
        when(primaryClient.healthCheck()).thenReturn(false);
        when(fallbackClient.healthCheck()).thenReturn(false);
        AiModelRouter router = new AiModelRouter(modelService);

        assertFalse(router.healthCheck());
    }

    @Test
    @DisplayName("healthCheck：调用链为空时返回 false")
    void healthCheck_emptyChain_returnsFalse() {
        when(modelService.resolveChain()).thenReturn(Collections.emptyList());
        AiModelRouter router = new AiModelRouter(modelService);

        assertFalse(router.healthCheck());
    }
}
