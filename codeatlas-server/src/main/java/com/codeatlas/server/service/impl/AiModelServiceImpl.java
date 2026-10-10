package com.codeatlas.server.service.impl;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.ClaudeClient;
import com.codeatlas.engine.ai.DeepSeekClient;
import com.codeatlas.server.entity.SystemConfigEntity;
import com.codeatlas.server.mapper.SystemConfigMapper;
import com.codeatlas.server.service.AiModelService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * AI 模型管理实现。
 *
 * 模型清单优先实时查询服务商 /v1/models 接口（带 60s 缓存），
 * 查询失败时降级为内置目录；当前模型持久化在 system_config 表。
 */
public class AiModelServiceImpl implements AiModelService {

    private static final Logger log = LoggerFactory.getLogger(AiModelServiceImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static final String PROVIDER_CLAUDE = "claude";
    public static final String PROVIDER_DEEPSEEK = "deepseek";

    private static final String CONFIG_KEY_CURRENT_MODEL = "ai.current.model";
    private static final String MODEL_LIST_SOURCE_LIVE = "live";
    private static final String MODEL_LIST_SOURCE_FALLBACK = "fallback";

    /** 服务商接口不可用时的兜底模型目录：provider -> (modelId -> 展示名) */
    private static final Map<String, Map<String, String>> FALLBACK_CATALOG;
    static {
        Map<String, Map<String, String>> catalog = new LinkedHashMap<>();
        Map<String, String> deepseek = new LinkedHashMap<>();
        deepseek.put("deepseek-v4-pro", "DeepSeek-V4-Pro");
        deepseek.put("deepseek-flash", "DeepSeek-V4.1-Flash");
        catalog.put(PROVIDER_DEEPSEEK, Collections.unmodifiableMap(deepseek));
        Map<String, String> claude = new LinkedHashMap<>();
        claude.put("claude-sonnet-4-6", "Claude Sonnet 4.6");
        catalog.put(PROVIDER_CLAUDE, Collections.unmodifiableMap(claude));
        FALLBACK_CATALOG = Collections.unmodifiableMap(catalog);
    }

    /** 模型清单缓存有效期，避免频繁打开管理页反复请求服务商接口 */
    private static final long MODEL_LIST_CACHE_MS = 60_000L;
    private static final long MODEL_LIST_TIMEOUT_SECONDS = 5L;
    private static final int TEST_MAX_TOKENS = 16;

    private final Map<String, String> providerKeys;
    private final Map<String, String> providerBaseUrls;
    private final List<String> fallbackOrder;
    private final String defaultModelId;
    private final SystemConfigMapper systemConfigMapper;

    private final OkHttpClient httpClient;
    private final Map<String, AiClient> clientCache = new ConcurrentHashMap<>();

    private volatile String currentModelId;
    private volatile List<Map<String, Object>> cachedModels;
    private volatile long cachedModelsAt;
    private volatile boolean cachedModelsLive;

    public AiModelServiceImpl(Map<String, String> providerKeys,
                              Map<String, String> providerBaseUrls,
                              List<String> fallbackOrder,
                              String defaultModelId,
                              SystemConfigMapper systemConfigMapper) {
        this.providerKeys = new LinkedHashMap<>(providerKeys);
        this.providerBaseUrls = new LinkedHashMap<>(providerBaseUrls);
        this.fallbackOrder = new ArrayList<>(fallbackOrder);
        this.defaultModelId = defaultModelId;
        this.systemConfigMapper = systemConfigMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(MODEL_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(MODEL_LIST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public boolean hasAnyModel() {
        return !providerKeys.isEmpty();
    }

    @Override
    public String getCurrentModelId() {
        String id = currentModelId;
        if (id != null) {
            return id;
        }
        synchronized (this) {
            if (currentModelId == null) {
                String loaded = loadPersistedModel();
                if (loaded != null) {
                    currentModelId = loaded;
                }
            }
            return currentModelId != null ? currentModelId : defaultModelId;
        }
    }

    @Override
    public void switchModel(String modelId) {
        if (modelId == null || modelId.trim().isEmpty()) {
            throw new IllegalArgumentException("模型 ID 不能为空");
        }
        String id = modelId.trim();
        if (findVisibleModel(id) == null) {
            throw new IllegalArgumentException("未知模型: " + id);
        }
        SystemConfigEntity config = new SystemConfigEntity();
        config.setConfigKey(CONFIG_KEY_CURRENT_MODEL);
        config.setConfigValue(id);
        config.setDescription("管理后台切换的 AI 当前模型");
        systemConfigMapper.upsert(config);
        currentModelId = id;
        log.info("AI model switched to: {}", id);
    }

    @Override
    public Map<String, Object> listModels() {
        List<Map<String, Object>> visible = loadModelsWithCache();
        String current = getCurrentModelId();

        List<Map<String, Object>> models = new ArrayList<>();
        boolean currentPresent = false;
        for (Map<String, Object> base : visible) {
            Map<String, Object> item = new LinkedHashMap<>(base);
            boolean isCurrent = current != null && current.equals(base.get("id"));
            item.put("current", isCurrent);
            currentPresent = currentPresent || isCurrent;
            models.add(item);
        }

        // 持久化的模型不在当前清单中（服务商下线或清单降级），补充展示以便管理员切回
        if (!currentPresent && current != null && !current.isEmpty()) {
            String provider = resolveProvider(current);
            Map<String, Object> stale = new LinkedHashMap<>();
            stale.put("id", current);
            stale.put("name", current);
            stale.put("provider", provider);
            stale.put("available", providerKeys.containsKey(provider));
            stale.put("current", true);
            stale.put("stale", true);
            models.add(stale);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", current);
        result.put("listSource", cachedModelsLive ? MODEL_LIST_SOURCE_LIVE : MODEL_LIST_SOURCE_FALLBACK);
        result.put("models", models);
        return result;
    }

    @Override
    public Map<String, Object> testModel(String modelId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", modelId);
        if (modelId == null || modelId.trim().isEmpty()) {
            result.put("success", false);
            result.put("error", "模型 ID 不能为空");
            return result;
        }
        String id = modelId.trim();
        if (findVisibleModel(id) == null) {
            result.put("success", false);
            result.put("error", "未知模型: " + id);
            return result;
        }
        try {
            AiClient client = getClientForModel(id);
            if (client == null) {
                result.put("success", false);
                result.put("error", "未配置该模型所属服务商的 API Key");
                return result;
            }
            AiRequest request = new AiRequest();
            request.setPrompt("ping");
            request.setMaxTokens(TEST_MAX_TOKENS);
            request.setTemperature(0);
            long start = System.currentTimeMillis();
            client.chat(request);
            result.put("success", true);
            result.put("latencyMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("AI model test failed for {}: {}", id, e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Override
    public List<AiClient> resolveChain() {
        LinkedHashMap<String, AiClient> chain = new LinkedHashMap<>();
        String current = getCurrentModelId();
        if (current != null && !current.isEmpty()) {
            AiClient active = getClientForModel(current);
            if (active != null) {
                chain.put(current, active);
            } else {
                log.warn("Current AI model {} is unavailable (missing API key), falling back", current);
            }
        }
        for (String modelId : fallbackOrder) {
            if (chain.containsKey(modelId)) {
                continue;
            }
            AiClient client = getClientForModel(modelId);
            if (client != null) {
                chain.put(modelId, client);
            }
        }
        return new ArrayList<>(chain.values());
    }

    private String loadPersistedModel() {
        try {
            SystemConfigEntity config = systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL);
            if (config != null && config.getConfigValue() != null && !config.getConfigValue().isEmpty()) {
                return config.getConfigValue();
            }
            return defaultModelId;
        } catch (Exception e) {
            // 可能是启动早期（迁移未完成），不缓存结果，下次访问重试
            log.warn("Failed to load persisted AI model, will retry: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> findVisibleModel(String modelId) {
        if (modelId == null) {
            return null;
        }
        for (Map<String, Object> model : loadModelsWithCache()) {
            if (modelId.equals(model.get("id"))) {
                return model;
            }
        }
        return null;
    }

    private synchronized List<Map<String, Object>> loadModelsWithCache() {
        long now = System.currentTimeMillis();
        List<Map<String, Object>> cached = cachedModels;
        if (cached != null && now - cachedModelsAt < MODEL_LIST_CACHE_MS) {
            return cached;
        }
        List<Map<String, Object>> models = new ArrayList<>();
        boolean allLive = true;
        for (Map.Entry<String, String> entry : providerKeys.entrySet()) {
            String provider = entry.getKey();
            List<Map<String, Object>> live = queryProviderModels(provider, entry.getValue());
            if (live != null) {
                models.addAll(live);
            } else {
                allLive = false;
                models.addAll(fallbackModels(provider));
            }
        }
        cachedModels = Collections.unmodifiableList(models);
        cachedModelsAt = now;
        cachedModelsLive = allLive;
        return cachedModels;
    }

    private List<Map<String, Object>> queryProviderModels(String provider, String apiKey) {
        String baseUrl = providerBaseUrls.get(provider);
        if (baseUrl == null) {
            return null;
        }
        Request.Builder builder = new Request.Builder().url(baseUrl + "/v1/models").get();
        if (PROVIDER_CLAUDE.equals(provider)) {
            builder.addHeader("x-api-key", apiKey);
            builder.addHeader("anthropic-version", "2023-06-01");
        } else {
            builder.addHeader("Authorization", "Bearer " + apiKey);
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("Model list query failed for {}: HTTP {}", provider, response.code());
                return null;
            }
            JsonNode root = OBJECT_MAPPER.readTree(response.body().string());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return null;
            }
            List<Map<String, Object>> models = new ArrayList<>();
            for (JsonNode node : data) {
                String id = node.path("id").asText("");
                if (id.isEmpty()) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", id);
                // DeepSeek 返回 name，Anthropic 返回 display_name
                String name = node.path("name").asText("");
                if (name.isEmpty()) {
                    name = node.path("display_name").asText("");
                }
                item.put("name", name.isEmpty() ? id : name);
                item.put("provider", provider);
                if (node.has("context_window")) {
                    item.put("contextWindow", node.path("context_window").asLong());
                }
                item.put("available", true);
                models.add(item);
            }
            return models;
        } catch (Exception e) {
            log.warn("Model list query failed for {}: {}", provider, e.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> fallbackModels(String provider) {
        Map<String, String> catalog = FALLBACK_CATALOG.get(provider);
        if (catalog == null) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> models = new ArrayList<>();
        for (Map.Entry<String, String> entry : catalog.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", entry.getKey());
            item.put("name", entry.getValue());
            item.put("provider", provider);
            item.put("available", true);
            models.add(item);
        }
        return models;
    }

    private AiClient getClientForModel(String modelId) {
        String provider = resolveProvider(modelId);
        String apiKey = providerKeys.get(provider);
        if (apiKey == null) {
            return null;
        }
        return clientCache.computeIfAbsent(modelId, id -> buildClient(id, provider, apiKey));
    }

    private AiClient buildClient(String modelId, String provider, String apiKey) {
        if (PROVIDER_CLAUDE.equals(provider)) {
            return new ClaudeClient(apiKey, modelId);
        }
        return new DeepSeekClient(apiKey, modelId);
    }

    private String resolveProvider(String modelId) {
        List<Map<String, Object>> cached = cachedModels;
        if (cached != null) {
            for (Map<String, Object> model : cached) {
                if (modelId.equals(model.get("id"))) {
                    return (String) model.get("provider");
                }
            }
        }
        return modelId != null && modelId.startsWith(PROVIDER_CLAUDE) ? PROVIDER_CLAUDE : PROVIDER_DEEPSEEK;
    }
}
