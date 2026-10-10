package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.StreamCallback;
import com.codeatlas.server.service.AiModelService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * AI 模型路由 — 每次调用按「当前选中模型优先」的顺序解析调用链，
 * 当前模型失败时自动降级到原有 fallback 顺序。
 *
 * 管理后台切换模型后无需重启，下一次调用即生效。
 */
public class AiModelRouter implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(AiModelRouter.class);

    private final AiModelService modelService;

    public AiModelRouter(AiModelService modelService) {
        this.modelService = modelService;
    }

    @Override
    public AiResponse chat(AiRequest request) {
        List<AiClient> chain = modelService.resolveChain();
        if (chain.isEmpty()) {
            throw new RuntimeException("No AI model available (no API key configured)");
        }

        Exception lastException = null;
        for (int i = 0; i < chain.size(); i++) {
            AiClient client = chain.get(i);
            try {
                log.info("Trying AI model [{}/{}]: {}", i + 1, chain.size(), client.getModelName());
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

        throw new RuntimeException("All AI models exhausted (current=" + modelService.getCurrentModelId() + ")",
                lastException);
    }

    @Override
    public void chatStream(AiRequest request, StreamCallback callback) {
        List<AiClient> chain = modelService.resolveChain();
        if (chain.isEmpty()) {
            throw new RuntimeException("No AI model available (no API key configured)");
        }

        Exception lastException = null;
        for (int i = 0; i < chain.size(); i++) {
            AiClient client = chain.get(i);
            try {
                log.info("Trying AI stream [{}/{}]: {}", i + 1, chain.size(), client.getModelName());
                client.chatStream(request, callback);
                return;
            } catch (Exception e) {
                lastException = e;
                log.warn("AI stream model {} failed: {}", client.getModelName(), e.getMessage());
            }
        }

        throw new RuntimeException("All AI models exhausted for streaming (current="
                + modelService.getCurrentModelId() + ")", lastException);
    }

    @Override
    public String getModelName() {
        String current = modelService.getCurrentModelId();
        return current != null && !current.isEmpty() ? current : "unknown";
    }

    @Override
    public boolean healthCheck() {
        for (AiClient client : modelService.resolveChain()) {
            if (client.healthCheck()) {
                return true;
            }
        }
        return false;
    }
}
