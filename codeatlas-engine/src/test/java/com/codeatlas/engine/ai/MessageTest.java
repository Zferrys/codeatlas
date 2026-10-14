package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Message — 多轮对话消息与工厂方法")
class MessageTest {

    @Test
    @DisplayName("无参构造器：全字段 null")
    void defaultConstructor_shouldBeAllNull() {
        Message message = new Message();

        assertNull(message.getRole());
        assertNull(message.getContent());
        assertNull(message.getToolCallId());
        assertNull(message.getToolCalls());
    }

    @Test
    @DisplayName("双参构造器：role/content 填充，工具字段为 null")
    void roleContentConstructor_shouldFillRoleAndContent() {
        Message message = new Message("user", "你好");

        assertEquals("user", message.getRole());
        assertEquals("你好", message.getContent());
        assertNull(message.getToolCallId());
        assertNull(message.getToolCalls());
    }

    @Test
    @DisplayName("setter/getter 全字段往返")
    void accessors_shouldRoundTrip() {
        Message message = new Message();
        List<ToolCall> toolCalls = Collections.singletonList(new ToolCall());
        message.setRole("assistant");
        message.setContent("内容");
        message.setToolCallId("call_9");
        message.setToolCalls(toolCalls);

        assertEquals("assistant", message.getRole());
        assertEquals("内容", message.getContent());
        assertEquals("call_9", message.getToolCallId());
        assertSame(toolCalls, message.getToolCalls());
    }

    @Test
    @DisplayName("system/user/assistant(content) 工厂：role 精确且内容透传")
    void contentFactories_shouldSetExactRoles() {
        Message system = Message.system("系统提示");
        assertEquals("system", system.getRole());
        assertEquals("系统提示", system.getContent());

        Message user = Message.user("问题");
        assertEquals("user", user.getRole());
        assertEquals("问题", user.getContent());

        Message assistant = Message.assistant("回答");
        assertEquals("assistant", assistant.getRole());
        assertEquals("回答", assistant.getContent());
    }

    @Test
    @DisplayName("assistant(toolCalls) 工厂：content 为 null、toolCalls 原样持有")
    void assistantToolCallsFactory_shouldCarryToolCalls() {
        List<ToolCall> toolCalls = Collections.singletonList(new ToolCall("id", "search", null));

        Message message = Message.assistant(toolCalls);

        assertEquals("assistant", message.getRole());
        assertNull(message.getContent());
        assertSame(toolCalls, message.getToolCalls());
    }

    @Test
    @DisplayName("tool(toolCallId, content) 工厂：role=tool、toolCallId 精确配对")
    void toolFactory_shouldPairToolCallId() {
        Message message = Message.tool("call_1", "工具结果");

        assertEquals("tool", message.getRole());
        assertEquals("工具结果", message.getContent());
        assertEquals("call_1", message.getToolCallId());
        assertNull(message.getToolCalls());
    }
}
