package com.codeatlas.engine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClaudeClient — Anthropic Messages 请求体序列化")
class ClaudeClientSerializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- 辅助方法 ----

    /** 通过反射调用私有 buildRequestBody，返回真实会发送给 Anthropic 的 JSON */
    private JsonNode buildRequestBody(AiRequest request) throws Exception {
        ClaudeClient client = new ClaudeClient("test-key", "claude-sonnet-4-6", "http://127.0.0.1:1");
        Method method = ClaudeClient.class.getDeclaredMethod("buildRequestBody", AiRequest.class, boolean.class);
        method.setAccessible(true);
        return MAPPER.readTree((String) method.invoke(client, request, false));
    }

    private AiRequest multiTurnRequest(Message... messages) {
        AiRequest request = new AiRequest();
        request.setMessages(Arrays.asList(messages));
        request.setTemperature(0.3);
        request.setMaxTokens(2048);
        return request;
    }

    private ToolCall toolCall(String id, String name) {
        ToolCall call = new ToolCall();
        call.setId(id);
        call.setName(name);
        call.setArguments(Collections.singletonMap("keyword", "demo"));
        return call;
    }

    private List<String> roles(JsonNode body) {
        List<String> result = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            result.add(message.path("role").asText());
        }
        return result;
    }

    /** Anthropic 的 messages 数组只接受 user/assistant —— 出现 "tool" 即非法 */
    private void assertMessagesOnlyContainLegalRoles(JsonNode body) {
        for (JsonNode message : body.path("messages")) {
            String role = message.path("role").asText();
            assertTrue("user".equals(role) || "assistant".equals(role),
                    "messages 中出现非法 role: " + role);
        }
    }

    private Set<String> collectIds(JsonNode body, String blockType, String idField) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode message : body.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if (blockType.equals(block.path("type").asText())) {
                    ids.add(block.path(idField).asText());
                }
            }
        }
        return ids;
    }

    private boolean containsToolResultContent(JsonNode body, String expectedContent) {
        for (JsonNode message : body.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if ("tool_result".equals(block.path("type").asText())
                        && expectedContent.equals(block.path("content").asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---- 测试 ----

    @Test
    @DisplayName("单轮工具结果：tool 消息必须序列化为 role=user + tool_result 块（复现原缺陷：曾以 role=tool 发出）")
    void buildRequestBody_singleToolResult_shouldMapToUserRoleWithToolResultBlock() throws Exception {
        AiRequest request = multiTurnRequest(
                Message.system("你是代码分析助手"),
                Message.user("项目里有哪些 Controller？"),
                Message.assistant(Collections.singletonList(toolCall("call_1", "search_classes"))),
                Message.tool("call_1", "工具结果1"));

        JsonNode body = buildRequestBody(request);

        assertEquals("你是代码分析助手", body.path("system").asText(), "system 必须提取到顶层 system 字段");
        assertEquals(Arrays.asList("user", "assistant", "user"), roles(body));
        assertMessagesOnlyContainLegalRoles(body);

        JsonNode assistantContent = body.path("messages").get(1).path("content");
        assertEquals("tool_use", assistantContent.get(0).path("type").asText());
        assertEquals("call_1", assistantContent.get(0).path("id").asText());
        assertEquals("search_classes", assistantContent.get(0).path("name").asText());

        JsonNode toolResultMessage = body.path("messages").get(2);
        assertEquals("user", toolResultMessage.path("role").asText(),
                "tool 结果必须用 role=user 发送，Anthropic 不接受 role=tool");
        JsonNode resultBlock = toolResultMessage.path("content").get(0);
        assertEquals("tool_result", resultBlock.path("type").asText());
        assertEquals("call_1", resultBlock.path("tool_use_id").asText());
        assertEquals("工具结果1", resultBlock.path("content").asText());

        assertTrue(body.path("messages").get(0).path("content").isTextual(),
                "普通 user 消息内容应保持字符串形式");
    }

    @Test
    @DisplayName("一轮并行多工具：每个 tool_result 都必须以 user 角色发出且与 tool_use id 一一配对")
    void buildRequestBody_parallelToolResults_shouldPairEveryToolUseId() throws Exception {
        AiRequest request = multiTurnRequest(
                Message.system("s"),
                Message.user("q"),
                Message.assistant(Arrays.asList(
                        toolCall("call_1", "search_classes"),
                        toolCall("call_2", "get_class_detail"))),
                Message.tool("call_1", "结果1"),
                Message.tool("call_2", "结果2"));

        JsonNode body = buildRequestBody(request);

        assertEquals(Arrays.asList("user", "assistant", "user", "user"), roles(body),
                "每条 tool 消息各自序列化为 user 消息（连续同角色由 Anthropic 服务端自动合并）");
        assertMessagesOnlyContainLegalRoles(body);
        assertEquals(collectIds(body, "tool_use", "id"), collectIds(body, "tool_result", "tool_use_id"),
                "每个 tool_use 都必须有 tool_result 配对");
    }

    @Test
    @DisplayName("生产事故序列：5 轮 × 2 工具 + 强制总结提示，全程仅合法 role、id 全配对、第 5 轮结果可达")
    void buildRequestBody_productionIncidentSequence_shouldProduceOnlyLegalRoles() throws Exception {
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system("s"));
        messages.add(Message.user("代码库的整体架构是怎样的？"));
        Set<String> expectedIds = new LinkedHashSet<>();
        for (int round = 1; round <= 5; round++) {
            String firstId = "call_" + round + "_a";
            String secondId = "call_" + round + "_b";
            expectedIds.add(firstId);
            expectedIds.add(secondId);
            messages.add(Message.assistant(Arrays.asList(
                    toolCall(firstId, "search_classes"), toolCall(secondId, "get_dependencies"))));
            messages.add(Message.tool(firstId, "结果" + round + "-a"));
            messages.add(Message.tool(secondId, "结果" + round + "-b"));
        }
        messages.add(Message.user("已达到工具调用轮次上限，请立即基于以上已获取的信息直接给出最终回答，不要再请求调用工具。"));

        JsonNode body = buildRequestBody(multiTurnRequest(messages.toArray(new Message[0])));

        assertMessagesOnlyContainLegalRoles(body);
        assertEquals(expectedIds, collectIds(body, "tool_use", "id"));
        assertEquals(expectedIds, collectIds(body, "tool_result", "tool_use_id"));
        List<String> roleSequence = roles(body);
        assertEquals("user", roleSequence.get(0));
        assertEquals("user", roleSequence.get(roleSequence.size() - 1), "最后一条应为强制总结 user 消息");
        assertTrue(containsToolResultContent(body, "结果5-b"),
                "第 5 轮工具结果必须出现在请求中（原用户问题的复现场景）");
    }

    @Test
    @DisplayName("简单模式（无 messages）：维持单条 user 消息 + 顶层 system 的既有行为")
    void buildRequestBody_simplePrompt_shouldKeepSingleUserMessage() throws Exception {
        AiRequest request = new AiRequest();
        request.setPrompt("hello");
        request.setSystemPrompt("sys");
        request.setMaxTokens(10);

        JsonNode body = buildRequestBody(request);

        assertEquals("sys", body.path("system").asText());
        assertEquals(Collections.singletonList("user"), roles(body));
        assertEquals("hello", body.path("messages").get(0).path("content").asText());
    }

    @Test
    @DisplayName("带正文的 assistant 工具消息：text 块在前、tool_use 块在后")
    void buildRequestBody_assistantWithContentAndToolCalls_shouldPutTextBlockFirst() throws Exception {
        Message assistant = Message.assistant(Collections.singletonList(toolCall("call_1", "search_classes")));
        assistant.setContent("我先搜索一下代码库");
        AiRequest request = multiTurnRequest(
                Message.user("q"), assistant, Message.tool("call_1", "r"));

        JsonNode body = buildRequestBody(request);

        JsonNode content = body.path("messages").get(1).path("content");
        assertEquals("text", content.get(0).path("type").asText());
        assertEquals("我先搜索一下代码库", content.get(0).path("text").asText());
        assertEquals("tool_use", content.get(1).path("type").asText());
    }
}
