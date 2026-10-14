package com.codeatlas.server.service.impl;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.ClaudeClient;
import com.codeatlas.engine.ai.DeepSeekClient;
import com.codeatlas.server.entity.SystemConfigEntity;
import com.codeatlas.server.mapper.SystemConfigMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * AiModelServiceImpl 单元测试。
 *
 * 覆盖：当前模型加载/缓存/双重检查锁、模型切换校验与持久化、
 * 模型清单实时查询（本地 HttpServer 模拟服务商接口）/缓存 TTL/降级目录、
 * 连接测试探针请求、客户端解析链路与实例缓存。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AiModelServiceImpl 单元测试（当前模型/切换/清单缓存与降级/连接测试/客户端链）")
class AiModelServiceImplTest {

    private static final String CONFIG_KEY_CURRENT_MODEL = "ai.current.model";
    private static final String DEEPSEEK_KEY = "sk-deepseek-test";
    private static final String CLAUDE_KEY = "sk-claude-test";

    @Mock
    private SystemConfigMapper systemConfigMapper;

    private final List<HttpServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (HttpServer server : servers) {
            server.stop(0);
        }
        servers.clear();
    }

    // ===================== 辅助方法 =====================

    private AiModelServiceImpl newService(Map<String, String> keys, Map<String, String> baseUrls,
                                          List<String> fallbackOrder, String defaultModelId) {
        return new AiModelServiceImpl(keys, baseUrls, fallbackOrder, defaultModelId, systemConfigMapper);
    }

    private static Map<String, String> providers(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<String> order(String... modelIds) {
        return new ArrayList<>(Arrays.asList(modelIds));
    }

    private static SystemConfigEntity config(String value) {
        SystemConfigEntity entity = new SystemConfigEntity();
        entity.setConfigKey(CONFIG_KEY_CURRENT_MODEL);
        entity.setConfigValue(value);
        return entity;
    }

    private static Map<String, Object> fallbackItem(String id, String name, String provider, boolean current) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id);
        item.put("name", name);
        item.put("provider", provider);
        item.put("available", true);
        item.put("current", current);
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> modelsOf(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("models");
    }

    @SuppressWarnings("unchecked")
    private static void injectClient(AiModelServiceImpl service, String modelId, AiClient client) {
        Map<String, AiClient> cache = (Map<String, AiClient>) ReflectionTestUtils.getField(service, "clientCache");
        cache.put(modelId, client);
    }

    private static void injectCachedModels(AiModelServiceImpl service, List<Map<String, Object>> models) {
        ReflectionTestUtils.setField(service, "cachedModels", models);
        ReflectionTestUtils.setField(service, "cachedModelsAt", System.currentTimeMillis());
    }

    /** 请求头捕获器（服务商模拟服务端线程写入） */
    private static final class CapturedRequest {
        final AtomicReference<String> authorization = new AtomicReference<>();
        final AtomicReference<String> xApiKey = new AtomicReference<>();
        final AtomicReference<String> anthropicVersion = new AtomicReference<>();
    }

    /** 启动本地模拟服务商 /v1/models 接口（127.0.0.1 随机端口） */
    private HttpServer startProvider(String responseBody, int status, CapturedRequest captured,
                                     AtomicInteger requestCount) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            if (requestCount != null) {
                requestCount.incrementAndGet();
            }
            if (captured != null) {
                captured.authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                captured.xApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
                captured.anthropicVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        servers.add(server);
        return server;
    }

    private static String baseUrl(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ===================== hasAnyModel =====================

    @Test
    @DisplayName("hasAnyModel — 未配置任何服务商 Key 时返回 false")
    void hasAnyModel_noKeys_returnsFalse() {
        AiModelServiceImpl service = newService(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        assertFalse(service.hasAnyModel());
    }

    @Test
    @DisplayName("hasAnyModel — 配置了服务商 Key 时返回 true")
    void hasAnyModel_withKeys_returnsTrue() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        assertTrue(service.hasAnyModel());
    }

    // ===================== getCurrentModelId =====================

    @Test
    @DisplayName("getCurrentModelId — 首次调用读取数据库持久化值")
    void getCurrentModelId_firstCall_loadsPersistedValue() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("claude-sonnet-4-6"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        assertEquals("claude-sonnet-4-6", service.getCurrentModelId());
        verify(systemConfigMapper).findByKey(CONFIG_KEY_CURRENT_MODEL);
    }

    @Test
    @DisplayName("getCurrentModelId — 加载后缓存，多次调用只查一次库")
    void getCurrentModelId_afterLoad_cachedWithoutSecondQuery() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("deepseek-v4-pro"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-flash");

        assertEquals("deepseek-v4-pro", service.getCurrentModelId());
        assertEquals("deepseek-v4-pro", service.getCurrentModelId());
        assertEquals("deepseek-v4-pro", service.getCurrentModelId());
        verify(systemConfigMapper, times(1)).findByKey(CONFIG_KEY_CURRENT_MODEL);
    }

    @Test
    @DisplayName("getCurrentModelId — 数据库无记录时返回构造默认模型")
    void getCurrentModelId_noDbRecord_returnsDefaultModel() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        assertEquals("deepseek-v4-pro", service.getCurrentModelId());
    }

    @Test
    @DisplayName("getCurrentModelId — 数据库值为 null 时回退默认模型")
    void getCurrentModelId_dbValueNull_returnsDefaultModel() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config(null));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-flash");

        assertEquals("deepseek-flash", service.getCurrentModelId());
    }

    @Test
    @DisplayName("getCurrentModelId — 数据库值为空串时回退默认模型")
    void getCurrentModelId_dbValueEmpty_returnsDefaultModel() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config(""));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-flash");

        assertEquals("deepseek-flash", service.getCurrentModelId());
    }

    @Test
    @DisplayName("getCurrentModelId — 无持久化值且默认模型为 null 时返回 null")
    void getCurrentModelId_defaultModelNull_returnsNull() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), null);

        assertNull(service.getCurrentModelId());
    }

    @Test
    @DisplayName("getCurrentModelId — 数据库异常不缓存结果，下次调用重试成功")
    void getCurrentModelId_dbThrows_retriesNextCall() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL))
                .thenThrow(new IllegalStateException("迁移未完成"))
                .thenReturn(config("claude-sonnet-4-6"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        assertEquals("deepseek-v4-pro", service.getCurrentModelId());
        assertEquals("claude-sonnet-4-6", service.getCurrentModelId());
        verify(systemConfigMapper, times(2)).findByKey(CONFIG_KEY_CURRENT_MODEL);
    }

    @Test
    @DisplayName("getCurrentModelId — 并发双重检查：第二个线程进入锁后发现已加载，不再查库")
    void getCurrentModelId_secondThreadBlockedOnLock_skipsSecondQuery() throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenAnswer(invocation -> {
            firstEntered.countDown();
            releaseFirst.await(5, TimeUnit.SECONDS);
            return config("deepseek-v4-pro");
        });
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-flash");

        AtomicReference<String> firstResult = new AtomicReference<>();
        AtomicReference<String> secondResult = new AtomicReference<>();
        Thread first = new Thread(() -> firstResult.set(service.getCurrentModelId()));
        first.start();
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS), "第一个线程应已进入数据库查询");
        Thread second = new Thread(() -> secondResult.set(service.getCurrentModelId()));
        second.start();
        // 给第二个线程时间完成外层 volatile 读取（读到 null）并阻塞在 synchronized 上
        Thread.sleep(300);
        releaseFirst.countDown();
        first.join(5000);
        second.join(5000);

        assertEquals("deepseek-v4-pro", firstResult.get());
        assertEquals("deepseek-v4-pro", secondResult.get());
        verify(systemConfigMapper, times(1)).findByKey(CONFIG_KEY_CURRENT_MODEL);
    }

    // ===================== switchModel =====================

    @Test
    @DisplayName("switchModel — 合法模型：trim 后 upsert 配置并缓存当前值，不再查库")
    void switchModel_validPaddedModel_trimsUpsertsAndCaches() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        service.switchModel("  deepseek-flash  ");

        ArgumentCaptor<SystemConfigEntity> captor = ArgumentCaptor.forClass(SystemConfigEntity.class);
        verify(systemConfigMapper).upsert(captor.capture());
        SystemConfigEntity saved = captor.getValue();
        assertEquals(CONFIG_KEY_CURRENT_MODEL, saved.getConfigKey());
        assertEquals("deepseek-flash", saved.getConfigValue());
        assertEquals("管理后台切换的 AI 当前模型", saved.getDescription());
        assertEquals("deepseek-flash", service.getCurrentModelId());
        verify(systemConfigMapper, never()).findByKey(anyString());
    }

    @Test
    @DisplayName("switchModel — 模型 ID 为 null 抛 400")
    void switchModel_nullId_throws400() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.switchModel(null));
        assertEquals(400, ex.getCode());
        assertEquals("模型 ID 不能为空", ex.getMessage());
        verifyNoInteractions(systemConfigMapper);
    }

    @Test
    @DisplayName("switchModel — 模型 ID 为纯空白抛 400")
    void switchModel_blankId_throws400() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.switchModel("   "));
        assertEquals(400, ex.getCode());
        assertEquals("模型 ID 不能为空", ex.getMessage());
        verifyNoInteractions(systemConfigMapper);
    }

    @Test
    @DisplayName("switchModel — 未知模型抛 400")
    void switchModel_unknownModel_throws400() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.switchModel("gpt-4o"));
        assertEquals(400, ex.getCode());
        assertEquals("未知模型: gpt-4o", ex.getMessage());
        verifyNoInteractions(systemConfigMapper);
    }

    // ===================== listModels =====================

    @Test
    @DisplayName("listModels — 无 baseUrl 走内置目录：条目字段与 current 标记精确匹配")
    void listModels_fallbackCatalog_exactItemsWithCurrentFlag() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.listModels();

        assertEquals("deepseek-v4-pro", result.get("current"));
        assertEquals("fallback", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(2, models.size());
        assertEquals(fallbackItem("deepseek-v4-pro", "DeepSeek-V4-Pro", "deepseek", true), models.get(0));
        assertEquals(fallbackItem("deepseek-flash", "DeepSeek-V4.1-Flash", "deepseek", false), models.get(1));
    }

    @Test
    @DisplayName("listModels — 持久化模型不在目录中时补一条 stale 条目（deepseek，Key 已配置）")
    void listModels_persistedModelAbsent_appendsStaleDeepseekEntry() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("deepseek-v9-future"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.listModels();

        assertEquals("deepseek-v9-future", result.get("current"));
        assertEquals("fallback", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(3, models.size());
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("id", "deepseek-v9-future");
        stale.put("name", "deepseek-v9-future");
        stale.put("provider", "deepseek");
        stale.put("available", true);
        stale.put("current", true);
        stale.put("stale", true);
        assertEquals(stale, models.get(2));
    }

    @Test
    @DisplayName("listModels — stale 条目按 claude 前缀推断 provider 且无 Key 时 available=false")
    void listModels_persistedClaudeAbsent_staleEntryUnavailable() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("claude-future-9"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.listModels();

        assertEquals("claude-future-9", result.get("current"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(3, models.size());
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("id", "claude-future-9");
        stale.put("name", "claude-future-9");
        stale.put("provider", "claude");
        stale.put("available", false);
        stale.put("current", true);
        stale.put("stale", true);
        assertEquals(stale, models.get(2));
    }

    @Test
    @DisplayName("listModels — current 为 null 时不补 stale 条目")
    void listModels_currentNull_noStaleEntry() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), null);

        Map<String, Object> result = service.listModels();

        assertTrue(result.containsKey("current"));
        assertNull(result.get("current"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(2, models.size());
    }

    @Test
    @DisplayName("listModels — current 为空串时不补 stale 条目")
    void listModels_currentEmpty_noStaleEntry() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "");

        Map<String, Object> result = service.listModels();

        assertEquals("", result.get("current"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(2, models.size());
    }

    @Test
    @DisplayName("listModels — 未配置任何服务商时清单为空，持久化 current 仍以 stale 展示")
    void listModels_noProviders_emptyCatalogStaleCurrent() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("deepseek-v4-pro"));
        AiModelServiceImpl service = newService(Collections.emptyMap(), Collections.emptyMap(),
                Collections.emptyList(), "");

        Map<String, Object> result = service.listModels();

        assertEquals("deepseek-v4-pro", result.get("current"));
        // 没有任何服务商参与查询时 allLive 保持初始 true（现有实现行为）
        assertEquals("live", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(1, models.size());
        Map<String, Object> stale = new LinkedHashMap<>();
        stale.put("id", "deepseek-v4-pro");
        stale.put("name", "deepseek-v4-pro");
        stale.put("provider", "deepseek");
        stale.put("available", false);
        stale.put("current", true);
        stale.put("stale", true);
        assertEquals(stale, models.get(0));
    }

    @Test
    @DisplayName("listModels — 未知服务商无内置目录时条目为空")
    void listModels_unknownProvider_fallbackCatalogEmpty() {
        AiModelServiceImpl service = newService(providers("openai", "sk-x"), Collections.emptyMap(),
                Collections.emptyList(), "");

        Map<String, Object> result = service.listModels();

        assertEquals("", result.get("current"));
        assertEquals("fallback", result.get("listSource"));
        assertTrue(modelsOf(result).isEmpty());
    }

    @Test
    @DisplayName("listModels — DeepSeek 实时清单：解析字段、Bearer 头、listSource=live")
    void listModels_liveDeepseek_parsesFieldsAndSendsBearerHeader() throws IOException {
        CapturedRequest captured = new CapturedRequest();
        HttpServer server = startProvider(
                "{\"data\":[{\"id\":\"live-model-a\",\"name\":\"Live A\",\"context_window\":128000},"
                        + "{\"id\":\"live-model-b\",\"display_name\":\"Live B\"},"
                        + "{\"id\":\"live-model-c\"},"
                        + "{\"id\":\"\",\"name\":\"skip-me\"}]}",
                200, captured, null);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", baseUrl(server)), Collections.emptyList(), "live-model-a");

        Map<String, Object> result = service.listModels();

        assertEquals("live-model-a", result.get("current"));
        assertEquals("live", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(3, models.size());
        Map<String, Object> itemA = new LinkedHashMap<>();
        itemA.put("id", "live-model-a");
        itemA.put("name", "Live A");
        itemA.put("provider", "deepseek");
        itemA.put("contextWindow", 128000L);
        itemA.put("available", true);
        itemA.put("current", true);
        assertEquals(itemA, models.get(0));
        assertEquals(fallbackItem("live-model-b", "Live B", "deepseek", false), models.get(1));
        // 无 name 且无 display_name 时以 id 兜底展示
        assertEquals(fallbackItem("live-model-c", "live-model-c", "deepseek", false), models.get(2));
        assertEquals("Bearer " + DEEPSEEK_KEY, captured.authorization.get());
        assertNull(captured.xApiKey.get());
    }

    @Test
    @DisplayName("listModels — Claude 实时清单使用 x-api-key/anthropic-version 而非 Bearer")
    void listModels_liveClaude_sendsApiKeyHeaders() throws IOException {
        CapturedRequest captured = new CapturedRequest();
        HttpServer server = startProvider(
                "{\"data\":[{\"id\":\"claude-sonnet-4-6\",\"display_name\":\"Claude Sonnet 4.6\","
                        + "\"context_window\":200000}]}",
                200, captured, null);
        AiModelServiceImpl service = newService(providers("claude", CLAUDE_KEY),
                providers("claude", baseUrl(server)), Collections.emptyList(), "claude-sonnet-4-6");

        Map<String, Object> result = service.listModels();

        assertEquals("live", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(1, models.size());
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("id", "claude-sonnet-4-6");
        expected.put("name", "Claude Sonnet 4.6");
        expected.put("provider", "claude");
        expected.put("contextWindow", 200000L);
        expected.put("available", true);
        expected.put("current", true);
        assertEquals(expected, models.get(0));
        assertEquals(CLAUDE_KEY, captured.xApiKey.get());
        assertEquals("2023-06-01", captured.anthropicVersion.get());
        assertNull(captured.authorization.get());
    }

    @Test
    @DisplayName("listModels — 服务商返回 500 时降级内置目录")
    void listModels_http500_fallsBackToCatalog() throws IOException {
        HttpServer server = startProvider("{\"error\":\"boom\"}", 500, null, null);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", baseUrl(server)), Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.listModels();

        assertEquals("fallback", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(2, models.size());
        assertEquals(fallbackItem("deepseek-v4-pro", "DeepSeek-V4-Pro", "deepseek", true), models.get(0));
    }

    @Test
    @DisplayName("listModels — 响应 data 非数组时降级内置目录")
    void listModels_dataNotArray_fallsBackToCatalog() throws IOException {
        HttpServer server = startProvider("{\"data\":\"oops\"}", 200, null, null);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", baseUrl(server)), Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.listModels();

        assertEquals("fallback", result.get("listSource"));
        assertEquals(2, modelsOf(result).size());
    }

    @Test
    @DisplayName("listModels — 响应体为 null 时降级内置目录")
    void listModels_nullBody_fallsBackToCatalog() throws IOException {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        Response mockResponse = mock(Response.class);
        when(mockHttp.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(null);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", "http://127.0.0.1:1"), Collections.emptyList(), "deepseek-v4-pro");
        ReflectionTestUtils.setField(service, "httpClient", mockHttp);

        Map<String, Object> result = service.listModels();

        assertEquals("fallback", result.get("listSource"));
        assertEquals(2, modelsOf(result).size());
    }

    @Test
    @DisplayName("listModels — 响应体非法 JSON 时降级内置目录")
    void listModels_malformedJson_fallsBackToCatalog() throws IOException {
        OkHttpClient mockHttp = mock(OkHttpClient.class);
        Call mockCall = mock(Call.class);
        Response mockResponse = mock(Response.class);
        ResponseBody mockBody = mock(ResponseBody.class);
        when(mockHttp.newCall(any(Request.class))).thenReturn(mockCall);
        when(mockCall.execute()).thenReturn(mockResponse);
        when(mockResponse.isSuccessful()).thenReturn(true);
        when(mockResponse.body()).thenReturn(mockBody);
        when(mockBody.string()).thenReturn("{not-json");
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", "http://127.0.0.1:1"), Collections.emptyList(), "deepseek-v4-pro");
        ReflectionTestUtils.setField(service, "httpClient", mockHttp);

        Map<String, Object> result = service.listModels();

        assertEquals("fallback", result.get("listSource"));
        assertEquals(2, modelsOf(result).size());
    }

    @Test
    @DisplayName("listModels — 部分服务商实时+部分降级时 listSource=fallback 且条目拼接")
    void listModels_partialLive_mixedSourceAndItems() throws IOException {
        HttpServer server = startProvider("{\"data\":[{\"id\":\"live-deepseek-1\",\"name\":\"Live D1\"}]}",
                200, null, null);
        AiModelServiceImpl service = newService(
                providers("deepseek", DEEPSEEK_KEY, "claude", CLAUDE_KEY),
                providers("deepseek", baseUrl(server)),
                Collections.emptyList(), "live-deepseek-1");

        Map<String, Object> result = service.listModels();

        assertEquals("fallback", result.get("listSource"));
        List<Map<String, Object>> models = modelsOf(result);
        assertEquals(2, models.size());
        assertEquals(fallbackItem("live-deepseek-1", "Live D1", "deepseek", true), models.get(0));
        assertEquals(fallbackItem("claude-sonnet-4-6", "Claude Sonnet 4.6", "claude", false), models.get(1));
    }

    @Test
    @DisplayName("listModels — 60 秒内命中缓存不重复请求，TTL 过期后重新拉取")
    void listModels_cacheReuse_thenExpiresAfterTtl() throws IOException {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = startProvider("{\"data\":[{\"id\":\"m1\",\"name\":\"M1\"}]}", 200, null, requestCount);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY),
                providers("deepseek", baseUrl(server)), Collections.emptyList(), "m1");

        service.listModels();
        service.listModels();
        assertEquals(1, requestCount.get());

        ReflectionTestUtils.setField(service, "cachedModelsAt", System.currentTimeMillis() - 61_000L);
        Map<String, Object> result = service.listModels();
        assertEquals(2, requestCount.get());
        assertEquals("live", result.get("listSource"));
    }

    // ===================== testModel =====================

    @Test
    @DisplayName("testModel — 模型 ID 为 null 返回参数错误")
    void testModel_nullModelId_returnsEmptyIdError() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.testModel(null);

        assertTrue(result.containsKey("model"));
        assertNull(result.get("model"));
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("模型 ID 不能为空", result.get("error"));
    }

    @Test
    @DisplayName("testModel — 模型 ID 为纯空白返回参数错误")
    void testModel_blankModelId_returnsEmptyIdError() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.testModel("   ");

        assertEquals("   ", result.get("model"));
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("模型 ID 不能为空", result.get("error"));
    }

    @Test
    @DisplayName("testModel — 未知模型返回未知模型错误")
    void testModel_unknownModel_returnsUnknownModelError() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Map<String, Object> result = service.testModel("gpt-9");

        assertEquals("gpt-9", result.get("model"));
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("未知模型: gpt-9", result.get("error"));
    }

    @Test
    @DisplayName("testModel — 可见模型所属服务商无 Key 时返回未配置提示")
    void testModel_visibleModelWithoutProviderKey_returnsKeyMissingError() {
        List<Map<String, Object>> seeded = new ArrayList<>();
        Map<String, Object> claudeItem = new LinkedHashMap<>();
        claudeItem.put("id", "claude-sonnet-4-6");
        claudeItem.put("name", "Claude Sonnet 4.6");
        claudeItem.put("provider", "claude");
        claudeItem.put("available", true);
        seeded.add(claudeItem);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");
        injectCachedModels(service, Collections.unmodifiableList(seeded));

        Map<String, Object> result = service.testModel("claude-sonnet-4-6");

        assertEquals("claude-sonnet-4-6", result.get("model"));
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("未配置该模型所属服务商的 API Key", result.get("error"));
        assertFalse(result.containsKey("latencyMs"));
    }

    @Test
    @DisplayName("testModel — 成功：探针提示词/maxTokens=512/temperature=0，model 回显原始入参")
    void testModel_availableModel_sendsProbeRequestAndReturnsLatency() {
        AiClient mockClient = mock(AiClient.class);
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");
        injectClient(service, "deepseek-v4-pro", mockClient);

        Map<String, Object> result = service.testModel("  deepseek-v4-pro  ");

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(mockClient).chat(captor.capture());
        AiRequest request = captor.getValue();
        assertEquals("Reply with the single word: OK", request.getPrompt());
        assertEquals(512, request.getMaxTokens());
        assertEquals(0.0, request.getTemperature());
        assertEquals("  deepseek-v4-pro  ", result.get("model"));
        assertEquals(Boolean.TRUE, result.get("success"));
        Object latency = result.get("latencyMs");
        assertTrue(latency instanceof Long);
        assertTrue((Long) latency >= 0L);
    }

    @Test
    @DisplayName("testModel — 客户端调用异常时返回异常信息")
    void testModel_clientThrows_returnsErrorMessage() {
        AiClient mockClient = mock(AiClient.class);
        when(mockClient.chat(any(AiRequest.class))).thenThrow(new IllegalStateException("上游 502"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");
        injectClient(service, "deepseek-v4-pro", mockClient);

        Map<String, Object> result = service.testModel("deepseek-v4-pro");

        assertEquals("deepseek-v4-pro", result.get("model"));
        assertEquals(Boolean.FALSE, result.get("success"));
        assertEquals("上游 502", result.get("error"));
        assertFalse(result.containsKey("latencyMs"));
    }

    // ===================== resolveChain =====================

    @Test
    @DisplayName("resolveChain — 当前模型优先、去重、客户端实例缓存复用")
    void resolveChain_currentThenFallback_dedupAndReuseClients() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("deepseek-flash"));
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                order("deepseek-v4-pro", "deepseek-flash", "deepseek-v4-pro"), "deepseek-v4-pro");

        List<AiClient> chain = service.resolveChain();

        assertEquals(2, chain.size());
        assertTrue(chain.get(0) instanceof DeepSeekClient);
        assertEquals("deepseek-flash", chain.get(0).getModelName());
        assertEquals("deepseek-v4-pro", chain.get(1).getModelName());

        List<AiClient> again = service.resolveChain();
        assertSame(chain.get(0), again.get(0));
        assertSame(chain.get(1), again.get(1));
    }

    @Test
    @DisplayName("resolveChain — 当前模型无 Key 时跳过并降级 fallbackOrder")
    void resolveChain_currentWithoutKey_skipsToFallback() {
        when(systemConfigMapper.findByKey(CONFIG_KEY_CURRENT_MODEL)).thenReturn(config("deepseek-orphan"));
        AiModelServiceImpl service = newService(providers("claude", CLAUDE_KEY), Collections.emptyMap(),
                order("deepseek-dead", "claude-sonnet-4-6"), "");

        List<AiClient> chain = service.resolveChain();

        assertEquals(1, chain.size());
        assertTrue(chain.get(0) instanceof ClaudeClient);
        assertEquals("claude-sonnet-4-6", chain.get(0).getModelName());
    }

    @Test
    @DisplayName("resolveChain — current 为空串时直接走 fallbackOrder")
    void resolveChain_emptyCurrent_usesFallbackOrderOnly() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                order("deepseek-v4-pro"), "");

        List<AiClient> chain = service.resolveChain();

        assertEquals(1, chain.size());
        assertEquals("deepseek-v4-pro", chain.get(0).getModelName());
    }

    @Test
    @DisplayName("resolveChain — current 为 null 时直接走 fallbackOrder")
    void resolveChain_nullCurrent_usesFallbackOrderOnly() {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                order("deepseek-v4-pro"), null);

        List<AiClient> chain = service.resolveChain();

        assertEquals(1, chain.size());
        assertEquals("deepseek-v4-pro", chain.get(0).getModelName());
    }

    // ===================== 私有防御分支 =====================

    @Test
    @DisplayName("私有方法 null 入参防御 — findVisibleModel 返回 null，resolveProvider 按启发式兜底 deepseek")
    void privateGuards_nullModelId_handledDefensively() throws Exception {
        AiModelServiceImpl service = newService(providers("deepseek", DEEPSEEK_KEY), Collections.emptyMap(),
                Collections.emptyList(), "deepseek-v4-pro");

        Method findVisible = AiModelServiceImpl.class.getDeclaredMethod("findVisibleModel", String.class);
        findVisible.setAccessible(true);
        assertNull(findVisible.invoke(service, new Object[]{null}));

        Method resolveProvider = AiModelServiceImpl.class.getDeclaredMethod("resolveProvider", String.class);
        resolveProvider.setAccessible(true);
        assertEquals("deepseek", resolveProvider.invoke(service, new Object[]{null}));
    }
}
