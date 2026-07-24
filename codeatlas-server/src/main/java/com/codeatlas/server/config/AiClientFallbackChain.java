package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.StreamCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 多模型降级链。
 * 按优先级依次尝试各个 AI 客户端，某个失败时自动 fallback 到下一个。
 * 实现 {@link AiClient} 接口，可无缝替换单客户端。
 */
public class AiClientFallbackChain implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiClientFallbackChain.class);

    private final List<AiClient> clients;
    private final String chainLabel;

    public AiClientFallbackChain(List<AiClient> clients) {
        this.clients = new ArrayList<>(clients);
        this.chainLabel = clients.stream()
                .map(AiClient::getModelName)
                .reduce((a, b) -> a + " -> " + b)
                .orElse("empty");
        log.info("AiClientFallbackChain initialized: {}", chainLabel);
    }

    @Override
    public AiResponse chat(AiRequest request) {
        Exception lastException = null;

        for (int i = 0; i < clients.size(); i++) {
            AiClient client = clients.get(i);
            try {
                log.info("Trying AI model [{}/{}]: {}", i + 1, clients.size(), client.getModelName());
                AiResponse response = client.chat(request);
                response.setModelUsed(client.getModelName());
                if (i > 0) {
                    response.setFallback(true);
                    log.warn("AI response served by fallback model: {}", client.getModelName());
                }
                return response;
            } catch (Exception e) {
                lastException = e;
                log.warn("AI model {} failed: {}", client.getModelName(), e.getMessage());
            }
        }

        throw new RuntimeException("All AI models exhausted (" + chainLabel + ")",
                lastException);
    }

    @Override
    public void chatStream(AiRequest request, StreamCallback callback) {
        Exception lastException = null;

        for (int i = 0; i < clients.size(); i++) {
            AiClient client = clients.get(i);
            try {
                log.info("Trying AI stream [{}/{}]: {}", i + 1, clients.size(), client.getModelName());
                client.chatStream(request, callback);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("AI stream model {} failed: {}", client.getModelName(), e.getMessage());
            }
        }

        throw new RuntimeException("All AI models exhausted for streaming (" + chainLabel + ")",
                lastException);
    }

    @Override
    public String getModelName() {
        return chainLabel;
    }

    @Override
    public boolean healthCheck() {
        for (AiClient client : clients) {
            if (client.healthCheck()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取主模型名称（优先级最高的可用模型）。
     */
    public String getPrimaryModelName() {
        return clients.isEmpty() ? "unknown" : clients.get(0).getModelName();
    }
}
