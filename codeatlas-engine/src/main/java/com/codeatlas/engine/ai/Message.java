package com.codeatlas.engine.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话消息 — 用于多轮 tool-calling 场景。
 */
public class Message {

    private String role;
    private String content;
    private String toolCallId;
    private List<ToolCall> toolCalls;

    public Message() {}

    public Message(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    // --- factory methods ---

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }

    public static Message assistant(List<ToolCall> toolCalls) {
        Message msg = new Message("assistant", null);
        msg.toolCalls = toolCalls;
        return msg;
    }

    public static Message tool(String toolCallId, String content) {
        Message msg = new Message("tool", content);
        msg.toolCallId = toolCallId;
        return msg;
    }
}
