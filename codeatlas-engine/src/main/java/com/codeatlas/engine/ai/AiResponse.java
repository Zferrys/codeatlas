package com.codeatlas.engine.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiResponse {

    private String content;
    private int tokensUsed;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private List<AiSource> sources;
    private String modelUsed;
    private boolean fallback;
    private List<ToolCall> toolCalls;
    private String finishReason;

    public AiResponse() {
        this.sources = new ArrayList<>();
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int promptTokens) { this.promptTokens = promptTokens; }

    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int completionTokens) { this.completionTokens = completionTokens; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public List<AiSource> getSources() { return sources; }
    public void setSources(List<AiSource> sources) { this.sources = sources != null ? sources : new ArrayList<>(); }

    public String getModelUsed() { return modelUsed; }
    public void setModelUsed(String modelUsed) { this.modelUsed = modelUsed; }

    public boolean isFallback() { return fallback; }
    public void setFallback(boolean fallback) { this.fallback = fallback; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static AiResponse of(String content, int tokensUsed, long latencyMs) {
        AiResponse r = new AiResponse();
        r.content = content;
        r.tokensUsed = tokensUsed;
        r.latencyMs = latencyMs;
        return r;
    }
}
