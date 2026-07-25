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
    private static final String DEFAULT_MODEL = "deepseek-v4-pro";

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

    @SuppressWarnings("unchecked")
    private String buildRequestBody(AiRequest request, boolean stream) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", request.getMaxTokens());
        body.put("temperature", request.getTemperature());

        List<Map<String, Object>> messages;

        if (request.hasMessages()) {
            // multi-turn mode: convert Message list to OpenAI format
            messages = new ArrayList<>();
            for (Message msg : request.getMessages()) {
                messages.add(toOpenAiMessage(msg));
            }
        } else {
            // simple mode: single user prompt (backward compat)
            messages = new ArrayList<>();

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
        }

        body.put("messages", messages);

        // tools (OpenAI function-calling format)
        if (request.getTools() != null && !request.getTools().isEmpty()) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (ToolDef tool : request.getTools()) {
                Map<String, Object> func = new LinkedHashMap<>();
                func.put("name", tool.getName());
                func.put("description", tool.getDescription());
                func.put("parameters", tool.getParameters());
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("type", "function");
                t.put("function", func);
                tools.add(t);
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }

        if (stream) {
            body.put("stream", true);
        }

        return OBJECT_MAPPER.writeValueAsString(body);
    }

    /** 将一个内部 Message 转换为 OpenAI 兼容格式的消息对象 */
    private Map<String, Object> toOpenAiMessage(Message msg) {
        Map<String, Object> apiMsg = new LinkedHashMap<>();
        apiMsg.put("role", msg.getRole());

        if ("assistant".equals(msg.getRole()) && msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            apiMsg.put("content", msg.getContent());
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (ToolCall tc : msg.getToolCalls()) {
                Map<String, Object> tcObj = new LinkedHashMap<>();
                tcObj.put("id", tc.getId());
                tcObj.put("type", "function");
                Map<String, Object> func = new LinkedHashMap<>();
                func.put("name", tc.getName());
                func.put("arguments", OBJECT_MAPPER.convertValue(
                        tc.getArguments() != null ? tc.getArguments() : Collections.emptyMap(), JsonNode.class)
                        .toString());
                tcObj.put("function", func);
                toolCalls.add(tcObj);
            }
            apiMsg.put("tool_calls", toolCalls);
        } else if ("tool".equals(msg.getRole())) {
            apiMsg.put("tool_call_id", msg.getToolCallId());
            apiMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
        } else {
            apiMsg.put("content", msg.getContent() != null ? msg.getContent() : "");
        }

        return apiMsg;
    }

    @SuppressWarnings("unchecked")
    private AiResponse parseResponse(String responseBody, long latencyMs) throws JsonProcessingException {
        JsonNode root = OBJECT_MAPPER.readTree(responseBody);

        String content = "";
        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode choices = root.path("choices");
        if (choices.isArray() && choices.size() > 0) {
            JsonNode message = choices.get(0).path("message");
            content = message.path("content").asText();

            // parse tool_calls
            JsonNode tcArray = message.path("tool_calls");
            if (tcArray.isArray()) {
                for (JsonNode tcNode : tcArray) {
                    ToolCall tc = new ToolCall();
                    tc.setId(tcNode.path("id").asText());
                    JsonNode func = tcNode.path("function");
                    tc.setName(func.path("name").asText());
                    String argsStr = func.path("arguments").asText();
                    if (argsStr != null && !argsStr.isEmpty()) {
                        try {
                            tc.setArguments(OBJECT_MAPPER.readValue(argsStr, Map.class));
                        } catch (JsonProcessingException e) {
                            tc.setArguments(Collections.emptyMap());
                        }
                    }
                    toolCalls.add(tc);
                }
            }
        }

        String finishReason = null;
        if (choices.isArray() && choices.size() > 0) {
            finishReason = choices.get(0).path("finish_reason").asText(null);
        }

        int tokensUsed = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        JsonNode usage = root.path("usage");
        if (!usage.isMissingNode()) {
            promptTokens = usage.path("prompt_tokens").asInt(0);
            completionTokens = usage.path("completion_tokens").asInt(0);
            tokensUsed = usage.path("total_tokens").asInt(0);
        }

        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokensUsed);
        response.setPromptTokens(promptTokens);
        response.setCompletionTokens(completionTokens);
        response.setLatencyMs(latencyMs);
        response.setSources(Collections.emptyList());
        if (!toolCalls.isEmpty()) {
            response.setToolCalls(toolCalls);
        }
        if (finishReason != null) {
            response.setFinishReason(finishReason);
        }
        return response;
    }
}
