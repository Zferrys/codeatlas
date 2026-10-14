package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AiResponse — AI 响应载体（token 记账与工具调用判定）")
class AiResponseTest {

    @Test
    @DisplayName("默认值：sources 为空列表、数值字段为 0、字符串/工具调用为 null")
    void defaults_shouldBeExact() {
        AiResponse response = new AiResponse();

        assertEquals(Collections.emptyList(), response.getSources());
        assertNull(response.getContent());
        assertEquals(0, response.getTokensUsed());
        assertEquals(0, response.getPromptTokens());
        assertEquals(0, response.getCompletionTokens());
        assertEquals(0, response.getReasoningTokens());
        assertEquals(0L, response.getLatencyMs());
        assertNull(response.getModelUsed());
        assertFalse(response.isFallback());
        assertNull(response.getToolCalls());
        assertNull(response.getFinishReason());
    }

    @Test
    @DisplayName("各 setter/getter 原样往返（含 token 记账四字段）")
    void accessors_shouldRoundTrip() {
        AiResponse response = new AiResponse();
        List<AiSource> sources = Collections.singletonList(new AiSource("com.demo.A"));
        List<ToolCall> toolCalls = Collections.singletonList(new ToolCall("id1", "search", null));

        response.setContent("结果");
        response.setTokensUsed(100);
        response.setPromptTokens(30);
        response.setCompletionTokens(60);
        response.setReasoningTokens(10);
        response.setLatencyMs(1234L);
        response.setSources(sources);
        response.setModelUsed("deepseek-v4-pro");
        response.setFallback(true);
        response.setToolCalls(toolCalls);
        response.setFinishReason("stop");

        assertEquals("结果", response.getContent());
        assertEquals(100, response.getTokensUsed());
        assertEquals(30, response.getPromptTokens());
        assertEquals(60, response.getCompletionTokens());
        assertEquals(10, response.getReasoningTokens());
        assertEquals(1234L, response.getLatencyMs());
        assertSame(sources, response.getSources());
        assertEquals("deepseek-v4-pro", response.getModelUsed());
        assertTrue(response.isFallback());
        assertSame(toolCalls, response.getToolCalls());
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    @DisplayName("setSources(null)：归一化为空列表而非 null，避免调用方 NPE")
    void setSources_null_shouldNormalizeToEmptyList() {
        AiResponse response = new AiResponse();

        response.setSources(null);

        assertEquals(Collections.emptyList(), response.getSources());
    }

    @Test
    @DisplayName("hasToolCalls：null → false、空列表 → false、非空 → true")
    void hasToolCalls_shouldDistinguishNullEmptyAndNonEmpty() {
        AiResponse response = new AiResponse();
        assertFalse(response.hasToolCalls());

        response.setToolCalls(Collections.emptyList());
        assertFalse(response.hasToolCalls());

        response.setToolCalls(Collections.singletonList(new ToolCall()));
        assertTrue(response.hasToolCalls());
    }

    @Test
    @DisplayName("of(content, tokensUsed, latencyMs)：三字段精确填充，其余保持默认")
    void of_shouldFillOnlyThreeFields() {
        AiResponse response = AiResponse.of("文本", 77, 250L);

        assertEquals("文本", response.getContent());
        assertEquals(77, response.getTokensUsed());
        assertEquals(250L, response.getLatencyMs());
        assertEquals(Collections.emptyList(), response.getSources());
        assertNull(response.getFinishReason());
        assertFalse(response.hasToolCalls());
    }

    @Test
    @DisplayName("of(null, ...)：内容为 null 时原样保留（空正文守卫由客户端层判定）")
    void of_withNullContent_shouldKeepNull() {
        AiResponse response = AiResponse.of(null, 0, 0L);

        assertNull(response.getContent());
        assertEquals(0, response.getTokensUsed());
        assertEquals(0L, response.getLatencyMs());
    }

    @Test
    @DisplayName("of 与 setter 混用：后设置的值覆盖工厂结果")
    void of_thenSetters_shouldOverride() {
        AiResponse response = AiResponse.of("a", 1, 2L);
        response.setContent("b");
        response.setTokensUsed(9);
        response.setModelUsed("m");
        response.setFallback(true);

        assertEquals("b", response.getContent());
        assertEquals(9, response.getTokensUsed());
        assertEquals("m", response.getModelUsed());
        assertTrue(response.isFallback());
    }
}
