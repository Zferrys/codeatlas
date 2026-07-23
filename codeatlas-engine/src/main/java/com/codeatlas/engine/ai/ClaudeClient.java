package com.codeatlas.engine.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Claude API 客户端 — 通过 OkHttp 调用 Anthropic Messages API。
 */
public class ClaudeClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);

    private static final String DEFAULT_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public ClaudeClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL);
    }

    public ClaudeClient(String apiKey, String model, String apiUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.apiUrl = apiUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public AiResponse chat(AiRequest request) {
        long start = System.currentTimeMillis();
        try {
            String body = buildRequestBody(request, false);
            okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("Claude API error: HTTP {} — {}", response.code(), errorBody);
                    throw new RuntimeException("Claude API error: HTTP " + response.code() + " — " + errorBody);
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody, System.currentTimeMillis() - start);
            }
        } catch (IOException e) {
            log.error("Claude API call failed", e);
            throw new RuntimeException("Claude API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(AiRequest request, StreamCallback callback) {
        try {
            String body = buildRequestBody(request, true);
            okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", ANTHROPIC_VERSION)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    callback.onError(new RuntimeException("HTTP " + response.code() + ": " + errorBody));
                    return;
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    callback.onComplete();
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(responseBody.byteStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            String data = line.substring(6);
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            try {
                                JsonNode node = OBJECT_MAPPER.readTree(data);
                                String type = node.path("type").asText();
                                if ("content_block_delta".equals(type)) {
                                    JsonNode delta = node.path("delta");
                                    String text = delta.path("text").asText();
                                    if (!text.isEmpty()) {
                                        callback.onToken(text);
                                    }
                                }
                            } catch (JsonProcessingException ignored) {
                                // skip unparseable lines
                            }
                        }
                    }
                }
                callback.onComplete();
            }
        } catch (IOException e) {
            log.error("Claude API stream failed", e);
            callback.onError(e);
        }
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public boolean healthCheck() {
        try {
            AiRequest req = new AiRequest();
            req.setPrompt("Hi");
            req.setMaxTokens(10);
            chat(req);
            return true;
        } catch (Exception e) {
            log.warn("Claude health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String buildRequestBody(AiRequest request, boolean stream) throws JsonProcessingException {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());

        // messages
        java.util.List<Map<String, Object>> messages = new java.util.ArrayList<>();
        Map<String, Object> userMsg = new java.util.LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", request.getPrompt());
        messages.add(userMsg);
        body.put("messages", messages);

        // system prompt
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            body.put("system", request.getSystemPrompt());
        }

        if (stream) {
            body.put("stream", true);
        }

        return OBJECT_MAPPER.writeValueAsString(body);
    }

    private AiResponse parseResponse(String responseBody, long latencyMs) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);

        int tokensUsed = 0;
        String content = "";

        // Anthropic API response format: content is in content[0].text
        JsonNode contentArray = root.path("content");
        if (contentArray.isArray() && contentArray.size() > 0) {
            for (JsonNode block : contentArray) {
                if ("text".equals(block.path("type").asText())) {
                    content += block.path("text").asText();
                }
            }
        }

        // usage
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            tokensUsed = usage.path("input_tokens").asInt(0) + usage.path("output_tokens").asInt(0);
        }

        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokensUsed);
        response.setLatencyMs(latencyMs);
        response.setSources(Collections.emptyList());
        return response;
    }
}
