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
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * DeepSeek API 客户端 — OpenAI 兼容的 chat/completions 接口。
 */
public class DeepSeekClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private static final String DEFAULT_API_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "deepseek-chat";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final String apiUrl;
    private final String apiKey;
    private final String model;

    public DeepSeekClient(String apiKey) {
        this(apiKey, DEFAULT_MODEL);
    }

    public DeepSeekClient(String apiKey, String model) {
        this(apiKey, model, DEFAULT_API_URL);
    }

    public DeepSeekClient(String apiKey, String model, String apiUrl) {
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
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(body, JSON_MEDIA_TYPE))
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "";
                    log.error("DeepSeek API error: HTTP {} — {}", response.code(), errorBody);
                    throw new RuntimeException("DeepSeek API error: HTTP " + response.code());
                }

                String responseBody = response.body() != null ? response.body().string() : "";
                return parseResponse(responseBody, System.currentTimeMillis() - start);
            }
        } catch (IOException e) {
            log.error("DeepSeek API call failed", e);
            throw new RuntimeException("DeepSeek API call failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void chatStream(AiRequest request, StreamCallback callback) {
        try {
            String body = buildRequestBody(request, true);
            okhttp3.Request httpRequest = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer " + apiKey)
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
                                JsonNode choices = node.path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    JsonNode delta = choices.get(0).path("delta");
                                    String content = delta.path("content").asText();
                                    if (!content.isEmpty()) {
                                        callback.onToken(content);
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
            log.error("DeepSeek API stream failed", e);
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
            log.warn("DeepSeek health check failed: {}", e.getMessage());
            return false;
        }
    }

    private String buildRequestBody(AiRequest request, boolean stream) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());

        List<Map<String, Object>> messages = new ArrayList<>();

        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            Map<String, Object> sysMsg = new LinkedHashMap<>();
            sysMsg.put("role", "system");
            sysMsg.put("content", request.getSystemPrompt());
            messages.add(sysMsg);
        }

        Map<String, Object> userMsg = new LinkedHashMap<>();
        userMsg.put("role", "user");
        userMsg.put("content", request.getPrompt());
        messages.add(userMsg);

        body.put("messages", messages);

        if (stream) {
            body.put("stream", true);
        }

        return OBJECT_MAPPER.writeValueAsString(body);
    }

    private AiResponse parseResponse(String responseBody, long latencyMs) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);

        String content = "";
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            content = choices.get(0).path("message").path("content").asText();
        }

        int tokensUsed = 0;
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            tokensUsed = usage.path("total_tokens").asInt(0);
        }

        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokensUsed);
        response.setLatencyMs(latencyMs);
        response.setSources(Collections.emptyList());
        return response;
    }
}
