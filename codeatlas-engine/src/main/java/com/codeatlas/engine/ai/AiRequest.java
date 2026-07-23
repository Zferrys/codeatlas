package com.codeatlas.engine.ai;

import java.util.HashMap;
import java.util.Map;

public class AiRequest {

    private String prompt;
    private String systemPrompt;
    private double temperature = 0.3;
    private int maxTokens = 4096;
    private Map<String, Object> metadata;

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
}
