package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AiRequest — AI 请求参数（默认值与多轮消息判定）")
class AiRequestTest {

    @Test
    @DisplayName("默认值：temperature=0.3、maxTokens=4096、metadata 为空 Map、prompt/systemPrompt/messages/tools 为 null")
    void defaults_shouldBeExact() {
        AiRequest request = new AiRequest();

        assertEquals(0.3, request.getTemperature(), 1e-9);
        assertEquals(4096, request.getMaxTokens());
        assertEquals(Collections.emptyMap(), request.getMetadata());
        assertNull(request.getPrompt());
        assertNull(request.getSystemPrompt());
        assertNull(request.getMessages());
        assertNull(request.getTools());
    }

    @Test
    @DisplayName("prompt 与 systemPrompt 的 setter/getter 原样往返")
    void promptAndSystemPrompt_shouldRoundTrip() {
        AiRequest request = new AiRequest();
        request.setPrompt("分析这个类");
        request.setSystemPrompt("你是代码助手");

        assertEquals("分析这个类", request.getPrompt());
        assertEquals("你是代码助手", request.getSystemPrompt());
    }

    @Test
    @DisplayName("temperature 与 maxTokens 可覆盖（含 0 与边界值）")
    void samplingParameters_shouldBeOverridable() {
        AiRequest request = new AiRequest();
        request.setTemperature(0.0);

        assertEquals(0.0, request.getTemperature(), 1e-9);

        request.setTemperature(1.0);
        request.setMaxTokens(1);

        assertEquals(1.0, request.getTemperature(), 1e-9);
        assertEquals(1, request.getMaxTokens());

        request.setMaxTokens(0);
        assertEquals(0, request.getMaxTokens());
    }

    @Test
    @DisplayName("metadata setter：整体替换（含 null）")
    void setMetadata_shouldReplaceWholeMap() {
        AiRequest request = new AiRequest();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("traceId", "t-1");

        request.setMetadata(metadata);
        assertSame(metadata, request.getMetadata());
        assertEquals("t-1", request.getMetadata().get("traceId"));

        request.setMetadata(null);
        assertNull(request.getMetadata());
    }

    @Test
    @DisplayName("addMetadata：写入键值并返回 this 支持链式调用")
    void addMetadata_shouldPutAndReturnThis() {
        AiRequest request = new AiRequest();

        AiRequest returned = request.addMetadata("user", "zferrys").addMetadata("projectId", 42);

        assertSame(request, returned);
        assertEquals("zferrys", request.getMetadata().get("user"));
        assertEquals(42, request.getMetadata().get("projectId"));
        assertEquals(2, request.getMetadata().size());
    }

    @Test
    @DisplayName("tools setter/getter 原样往返")
    void setTools_shouldRoundTrip() {
        AiRequest request = new AiRequest();
        List<ToolDef> tools = Arrays.asList(new ToolDef("search", "搜索", Collections.emptyMap()));

        request.setTools(tools);

        assertSame(tools, request.getTools());
    }

    @Test
    @DisplayName("hasMessages：null → false、空列表 → false、非空列表 → true")
    void hasMessages_shouldDistinguishNullEmptyAndNonEmpty() {
        AiRequest request = new AiRequest();
        assertFalse(request.hasMessages(), "messages 为 null 时不是多轮模式");

        request.setMessages(Collections.emptyList());
        assertFalse(request.hasMessages(), "messages 为空列表时不是多轮模式");

        request.setMessages(Collections.singletonList(Message.user("hi")));
        assertTrue(request.hasMessages(), "messages 非空时进入多轮模式");
    }
}
