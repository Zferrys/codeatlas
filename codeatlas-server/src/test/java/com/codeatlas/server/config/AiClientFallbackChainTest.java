package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.StreamCallback;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiClientFallbackChain 单元测试（链标签 + 降级顺序 + 失败语义）")
class AiClientFallbackChainTest {

    @Mock
    private AiClient firstClient;
    @Mock
    private AiClient secondClient;

    private final StreamCallback callback = token -> { };

    @Test
    @DisplayName("空链：标签为 empty，主模型为 unknown，调用抛出空链异常（cause 为 null）")
    void emptyChain_labelAndFailures() {
        AiClientFallbackChain chain = new AiClientFallbackChain(Collections.emptyList());

        assertEquals("empty", chain.getModelName());
        assertEquals("unknown", chain.getPrimaryModelName());

        RuntimeException chatEx = assertThrows(RuntimeException.class, () -> chain.chat(new AiRequest()));
        assertEquals("All AI models exhausted (empty)", chatEx.getMessage());
        assertNull(chatEx.getCause());

        RuntimeException streamEx = assertThrows(RuntimeException.class,
                () -> chain.chatStream(new AiRequest(), callback));
        assertEquals("All AI models exhausted for streaming (empty)", streamEx.getMessage());
        assertNull(streamEx.getCause());
    }

    @Test
    @DisplayName("单模型：链标签即模型名，主模型名一致")
    void singleClient_labelIsModelName() {
        when(firstClient.getModelName()).thenReturn("m1");
        AiClientFallbackChain chain = new AiClientFallbackChain(Collections.singletonList(firstClient));

        assertEquals("m1", chain.getModelName());
        assertEquals("m1", chain.getPrimaryModelName());
    }

    @Test
    @DisplayName("双模型：链标签按顺序拼接为 'm1 -> m2'，主模型为第一个")
    void twoClients_labelJoinsInOrder() {
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertEquals("m1 -> m2", chain.getModelName());
        assertEquals("m1", chain.getPrimaryModelName());
    }

    @Test
    @DisplayName("构造时防御性拷贝：外部列表被清空后链内模型仍可用")
    void constructor_defensivelyCopiesClientList() {
        AiRequest request = new AiRequest();
        AiResponse response = AiResponse.of("ok", 3, 1L);
        List<AiClient> mutableList = new ArrayList<>();
        mutableList.add(firstClient);
        when(firstClient.getModelName()).thenReturn("m1");
        AiClientFallbackChain chain = new AiClientFallbackChain(mutableList);

        mutableList.clear();
        when(firstClient.chat(request)).thenReturn(response);

        assertSame(response, chain.chat(request));
        assertEquals("m1", chain.getModelName());
        verify(firstClient).chat(request);
    }

    @Test
    @DisplayName("chat：首选成功时标记 modelUsed 且不标记 fallback")
    void chat_firstSucceeds_noFallbackFlag() {
        AiRequest request = new AiRequest();
        AiResponse response = AiResponse.of("ok", 5, 2L);
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.chat(request)).thenReturn(response);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        AiResponse result = chain.chat(request);

        assertSame(response, result);
        assertEquals("m1", result.getModelUsed());
        assertFalse(result.isFallback());
        verify(firstClient).chat(request);
        verify(secondClient, never()).chat(request);
    }

    @Test
    @DisplayName("chat：首选失败降级到次选，按顺序调用并标记 fallback=true")
    void chat_secondSucceeds_marksFallback() {
        AiRequest request = new AiRequest();
        AiResponse response = AiResponse.of("ok2", 6, 3L);
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.chat(request)).thenThrow(new RuntimeException("boom1"));
        when(secondClient.chat(request)).thenReturn(response);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        AiResponse result = chain.chat(request);

        assertSame(response, result);
        assertEquals("m2", result.getModelUsed());
        assertTrue(result.isFallback());
        InOrder order = inOrder(firstClient, secondClient);
        order.verify(firstClient).chat(request);
        order.verify(secondClient).chat(request);
    }

    @Test
    @DisplayName("chat：全部失败抛出携带链标签与最后原因的异常")
    void chat_allFail_throwsWithChainLabelAndLastCause() {
        AiRequest request = new AiRequest();
        RuntimeException first = new RuntimeException("boom1");
        RuntimeException second = new RuntimeException("boom2");
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.chat(request)).thenThrow(first);
        when(secondClient.chat(request)).thenThrow(second);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> chain.chat(request));

        assertEquals("All AI models exhausted (m1 -> m2)", ex.getMessage());
        assertSame(second, ex.getCause());
    }

    @Test
    @DisplayName("chatStream：首选成功后立即返回，不再尝试后续模型")
    void chatStream_firstSucceeds_stopsChain() {
        AiRequest request = new AiRequest();
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertDoesNotThrow(() -> chain.chatStream(request, callback));

        verify(firstClient).chatStream(request, callback);
        verify(secondClient, never()).chatStream(request, callback);
    }

    @Test
    @DisplayName("chatStream：首选失败降级到次选并按顺序调用")
    void chatStream_secondSucceeds_afterFirstFails() {
        AiRequest request = new AiRequest();
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        doThrow(new RuntimeException("stream boom")).when(firstClient).chatStream(request, callback);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertDoesNotThrow(() -> chain.chatStream(request, callback));

        InOrder order = inOrder(firstClient, secondClient);
        order.verify(firstClient).chatStream(request, callback);
        order.verify(secondClient).chatStream(request, callback);
    }

    @Test
    @DisplayName("chatStream：全部失败抛出携带链标签与最后原因的异常")
    void chatStream_allFail_throwsWithChainLabelAndLastCause() {
        AiRequest request = new AiRequest();
        RuntimeException first = new RuntimeException("stream boom1");
        RuntimeException second = new RuntimeException("stream boom2");
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        doThrow(first).when(firstClient).chatStream(request, callback);
        doThrow(second).when(secondClient).chatStream(request, callback);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> chain.chatStream(request, callback));

        assertEquals("All AI models exhausted for streaming (m1 -> m2)", ex.getMessage());
        assertSame(second, ex.getCause());
    }

    @Test
    @DisplayName("healthCheck：首个健康返回 true 且不再检查后续")
    void healthCheck_firstHealthy_returnsTrue() {
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.healthCheck()).thenReturn(true);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertTrue(chain.healthCheck());

        verify(firstClient).healthCheck();
        verify(secondClient, never()).healthCheck();
    }

    @Test
    @DisplayName("healthCheck：降级模型健康返回 true")
    void healthCheck_secondHealthy_returnsTrue() {
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.healthCheck()).thenReturn(false);
        when(secondClient.healthCheck()).thenReturn(true);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertTrue(chain.healthCheck());

        verify(firstClient).healthCheck();
        verify(secondClient).healthCheck();
    }

    @Test
    @DisplayName("healthCheck：全部不健康返回 false")
    void healthCheck_noneHealthy_returnsFalse() {
        when(firstClient.getModelName()).thenReturn("m1");
        when(secondClient.getModelName()).thenReturn("m2");
        when(firstClient.healthCheck()).thenReturn(false);
        when(secondClient.healthCheck()).thenReturn(false);
        AiClientFallbackChain chain = new AiClientFallbackChain(Arrays.asList(firstClient, secondClient));

        assertFalse(chain.healthCheck());
    }
}
