package com.codeatlas.engine.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AiResponse {

    private String content;
    private int tokensUsed;
    private long latencyMs;
    private List<AiSource> sources;

    public AiResponse() {
        this.sources = new ArrayList<>();
    }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public int getTokensUsed() { return tokensUsed; }
    public void setTokensUsed(int tokensUsed) { this.tokensUsed = tokensUsed; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public List<AiSource> getSources() { return sources; }
    public void setSources(List<AiSource> sources) { this.sources = sources != null ? sources : new ArrayList<>(); }

    public static AiResponse of(String content, int tokensUsed, long latencyMs) {
        AiResponse r = new AiResponse();
        r.content = content;
        r.tokensUsed = tokensUsed;
        r.latencyMs = latencyMs;
        return r;
    }
}
