package com.codeatlas.engine.ai;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AiRequest {

    private String prompt;
    private String systemPrompt;
    private double temperature = 0.3;
    private int maxTokens = 4096;
    private Map<String, Object> metadata;
    private List<Message> messages;
    private List<ToolDef> tools;

    public AiRequest() {
        this.metadata = new HashMap<>();
    }

    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    public AiRequest addMetadata(String key, Object value) {
        this.metadata.put(key, value);
        return this;
    }

    public List<Message> getMessages() { return messages; }
    public void setMessages(List<Message> messages) { this.messages = messages; }

    public List<ToolDef> getTools() { return tools; }
    public void setTools(List<ToolDef> tools) { this.tools = tools; }

    /** 是否使用多轮 messages 模式（替代 prompt+systemPrompt 拼接） */
    public boolean hasMessages() {
        return messages != null && !messages.isEmpty();
    }
}
