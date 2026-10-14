package com.codeatlas.engine.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Timeout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DeepSeekClient — OpenAI 兼容 chat/completions 全分支")
class DeepSeekClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastContentType = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    // ==================== 测试桩 ====================

    private void stubResponse(int status, String body) {
        server.createContext("/", exchange -> {
            lastRequestBody.set(readAll(exchange.getRequestBody()));
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private DeepSeekClient client() {
        return clientAt(server.getAddress().getPort());
    }

    private DeepSeekClient clientAt(int port) {
        return new DeepSeekClient("test-key", "deepseek-v4-pro",
                "http://127.0.0.1:" + port + "/v1/chat/completions");
    }

    private JsonNode capturedBody() throws IOException {
        return MAPPER.readTree(lastRequestBody.get());
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 收集流式回调事件，统计各回调次数 */
    private static final class RecordingCallback implements StreamCallback {
        final List<String> tokens = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final AtomicInteger completions = new AtomicInteger();

        @Override
        public void onToken(String token) {
            tokens.add(token);
        }

        @Override
        public void onComplete() {
            completions.incrementAndGet();
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }
    }

    // ==================== 构造器与模型名 ====================

    @Test
    @DisplayName("单参构造器：默认模型 deepseek-v4-pro")
    void constructor_defaultModel_shouldBeV4Pro() {
        assertEquals("deepseek-v4-pro", new DeepSeekClient("k").getModelName());
    }

    @Test
    @DisplayName("双参构造器：显式模型名原样保留")
    void constructor_explicitModel_shouldBeKept() {
        assertEquals("custom-model", new DeepSeekClient("k", "custom-model").getModelName());
    }

    // ==================== chat：正常路径 ====================

    @Nested
    @DisplayName("chat — 正常路径")
    class ChatSuccess {

        @Test
        @DisplayName("解析正文、token 记账（含 reasoning_tokens）、finish_reason，并携带鉴权头")
        void chat_success_shouldParseAllFields() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"答案是42\"},"
                    + "\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20,\"total_tokens\":30,"
                    + "\"completion_tokens_details\":{\"reasoning_tokens\":7}}}");
            AiRequest request = new AiRequest();
            request.setPrompt("问题");

            AiResponse response = client().chat(request);

            assertEquals("答案是42", response.getContent());
            assertEquals(30, response.getTokensUsed());
            assertEquals(10, response.getPromptTokens());
            assertEquals(20, response.getCompletionTokens());
            assertEquals(7, response.getReasoningTokens());
            assertEquals("stop", response.getFinishReason());
            assertTrue(response.getLatencyMs() >= 0, "latencyMs 应被填充");
            assertEquals(Collections.emptyList(), response.getSources());
            assertFalse(response.hasToolCalls());

            assertEquals("Bearer test-key", lastAuthorization.get());
            assertEquals("application/json; charset=utf-8", lastContentType.get());
            JsonNode body = capturedBody();
            assertEquals("deepseek-v4-pro", body.path("model").asText());
            assertEquals(4096, body.path("max_tokens").asInt());
            assertEquals(0.3, body.path("temperature").asDouble(), 1e-9);
            assertTrue(body.path("stream").isMissingNode(), "非流式请求不应携带 stream 字段");
        }

        @Test
        @DisplayName("usage 缺失时 token 全为 0、finish_reason 为 null")
        void chat_usageMissing_shouldDefaultToZero() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");

            AiResponse response = client().chat(new AiRequest());

            assertEquals("hi", response.getContent());
            assertEquals(0, response.getTokensUsed());
            assertEquals(0, response.getPromptTokens());
            assertEquals(0, response.getCompletionTokens());
            assertEquals(0, response.getReasoningTokens());
            assertNull(response.getFinishReason());
        }

        @Test
        @DisplayName("usage 仅有 total_tokens 时 prompt/completion 归零、total 保留")
        void chat_partialUsage_shouldKeepTotalOnly() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"hi\"}}],"
                    + "\"usage\":{\"total_tokens\":99}}");

            AiResponse response = client().chat(new AiRequest());

            assertEquals(99, response.getTokensUsed());
            assertEquals(0, response.getPromptTokens());
            assertEquals(0, response.getCompletionTokens());
        }

        @Test
        @DisplayName("多轮消息模式：system/user/assistant(tool_calls)/tool 全部转为 OpenAI 格式")
        void chat_multiTurnMessages_shouldSerializeOpenAiFormat() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");

            Map<String, Object> args = new HashMap<>();
            args.put("keyword", "demo");
            Message assistant = Message.assistant(Arrays.asList(
                    new ToolCall("call_1", "search_classes", args),
                    new ToolCall("call_2", "no_args", null)));
            AiRequest request = new AiRequest();
            request.setMessages(Arrays.asList(
                    Message.system("你是代码助手"), Message.user("有哪些类？"),
                    assistant, Message.tool("call_1", "结果1")));
            request.setMaxTokens(2048);
            request.setTemperature(0.1);

            client().chat(request);

            JsonNode body = capturedBody();
            assertTrue(body.path("system").isMissingNode(), "多轮模式下 system 以消息形式进入 messages");
            JsonNode messages = body.path("messages");
            assertEquals(4, messages.size());
            assertEquals("system", messages.get(0).path("role").asText());
            assertEquals("你是代码助手", messages.get(0).path("content").asText());
            assertEquals("user", messages.get(1).path("role").asText());

            JsonNode assistantMsg = messages.get(2);
            assertEquals("assistant", assistantMsg.path("role").asText());
            assertTrue(assistantMsg.path("content").isNull(), "assistant 的 content 为 null 时原样传 null");
            JsonNode toolCalls = assistantMsg.path("tool_calls");
            assertEquals(2, toolCalls.size());
            assertEquals("call_1", toolCalls.get(0).path("id").asText());
            assertEquals("function", toolCalls.get(0).path("type").asText());
            assertEquals("search_classes", toolCalls.get(0).path("function").path("name").asText());
            assertEquals("{\"keyword\":\"demo\"}",
                    toolCalls.get(0).path("function").path("arguments").asText(),
                    "arguments 必须序列化为 JSON 字符串");
            assertEquals("{}", toolCalls.get(1).path("function").path("arguments").asText(),
                    "arguments 为 null 时序列化为空对象字符串");

            JsonNode toolMsg = messages.get(3);
            assertEquals("tool", toolMsg.path("role").asText());
            assertEquals("call_1", toolMsg.path("tool_call_id").asText());
            assertEquals("结果1", toolMsg.path("content").asText());

            assertEquals(2048, body.path("max_tokens").asInt());
            assertEquals(0.1, body.path("temperature").asDouble(), 1e-9);
        }

        @Test
        @DisplayName("简单模式：非空 systemPrompt 生成 system 消息且排在 user 之前")
        void chat_simpleModeWithSystemPrompt_shouldPrependSystemMessage() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            AiRequest request = new AiRequest();
            request.setPrompt("用户问题");
            request.setSystemPrompt("系统提示");

            client().chat(request);

            JsonNode messages = capturedBody().path("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.get(0).path("role").asText());
            assertEquals("系统提示", messages.get(0).path("content").asText());
            assertEquals("user", messages.get(1).path("role").asText());
            assertEquals("用户问题", messages.get(1).path("content").asText());
        }

        @Test
        @DisplayName("简单模式：systemPrompt 为空串时省略 system 消息")
        void chat_simpleModeEmptySystemPrompt_shouldSkipSystemMessage() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            AiRequest request = new AiRequest();
            request.setPrompt("p");
            request.setSystemPrompt("");

            client().chat(request);

            JsonNode messages = capturedBody().path("messages");
            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
        }

        @Test
        @DisplayName("简单模式：systemPrompt 为 null 时省略 system 消息")
        void chat_simpleModeNullSystemPrompt_shouldSkipSystemMessage() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            AiRequest request = new AiRequest();
            request.setPrompt("p");

            client().chat(request);

            JsonNode messages = capturedBody().path("messages");
            assertEquals(1, messages.size());
            assertEquals("user", messages.get(0).path("role").asText());
        }

        @Test
        @DisplayName("声明工具时按 OpenAI function-calling 格式序列化并携带 tool_choice=auto")
        void chat_withTools_shouldSerializeFunctionCalling() throws Exception {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", Collections.singletonMap("keyword",
                    Collections.singletonMap("type", "string")));
            AiRequest request = new AiRequest();
            request.setPrompt("p");
            request.setTools(Arrays.asList(
                    new ToolDef("search_classes", "按关键词搜索类", parameters)));

            client().chat(request);

            JsonNode body = capturedBody();
            assertEquals("auto", body.path("tool_choice").asText());
            JsonNode tools = body.path("tools");
            assertEquals(1, tools.size());
            assertEquals("function", tools.get(0).path("type").asText());
            assertEquals("search_classes", tools.get(0).path("function").path("name").asText());
            assertEquals("按关键词搜索类", tools.get(0).path("function").path("description").asText());
            assertEquals("object",
                    tools.get(0).path("function").path("parameters").path("type").asText());
        }
    }

    // ==================== chat：空正文守卫 ====================

    @Nested
    @DisplayName("chat — 空正文守卫")
    class ChatEmptyContentGuard {

        @Test
        @DisplayName("正文为空串时抛异常，消息含 finishReason 与 completionTokens 供降级定位")
        void chat_emptyContent_shouldThrowWithDiagnostics() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"\"},"
                    + "\"finish_reason\":\"max_tokens\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":256,\"total_tokens\":266}}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=max_tokens, completionTokens=256)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("正文为纯空白同样视为失败")
        void chat_blankContent_shouldThrow() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"   \\n \\t \"},"
                    + "\"finish_reason\":\"length\"}]}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=length, completionTokens=0)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("content 字段缺失（视为空）但 tokens 耗尽：同样抛异常")
        void chat_missingContent_shouldThrow() {
            stubResponse(200, "{\"choices\":[{\"message\":{},\"finish_reason\":\"max_tokens\"}]}");

            assertThrows(RuntimeException.class, () -> client().chat(new AiRequest()));
        }

        @Test
        @DisplayName("响应体为 {}（无 choices）：空正文守卫触发，诊断 finishReason=null")
        void chat_emptyJsonObject_shouldThrowWithNullFinishReason() {
            stubResponse(200, "{}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=null, completionTokens=0)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("choices 为空数组：空正文守卫触发")
        void chat_emptyChoicesArray_shouldThrow() {
            stubResponse(200, "{\"choices\":[]}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=null, completionTokens=0)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("choices 不是数组（对象形态）：空正文守卫触发")
        void chat_nonArrayChoices_shouldThrow() {
            stubResponse(200, "{\"choices\":{\"not\":\"array\"}}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=null, completionTokens=0)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("仅有 tool_calls 无正文时不抛异常（合法工具调用轮）")
        void chat_toolCallsOnly_shouldNotThrow() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                    + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":"
                    + "{\"name\":\"search_classes\",\"arguments\":\"{\\\"keyword\\\":\\\"demo\\\"}\"}}]},"
                    + "\"finish_reason\":\"tool_calls\"}]}");

            AiResponse response = client().chat(new AiRequest());

            assertTrue(response.hasToolCalls());
            assertEquals(1, response.getToolCalls().size());
            assertEquals("call_1", response.getToolCalls().get(0).getId());
            assertEquals("search_classes", response.getToolCalls().get(0).getName());
            assertEquals("demo", response.getToolCalls().get(0).getStringArg("keyword"));
            assertEquals("tool_calls", response.getFinishReason());
        }

        @Test
        @DisplayName("tool_call 的 arguments 为非法 JSON 时降级为空 Map（不中断整轮调用）")
        void chat_invalidToolArguments_shouldFallbackToEmptyMap() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                    + "{\"id\":\"call_1\",\"function\":{\"name\":\"t\",\"arguments\":\"not-json\"}}]}}]}");

            AiResponse response = client().chat(new AiRequest());

            assertEquals(Collections.emptyMap(), response.getToolCalls().get(0).getArguments());
            assertNull(response.getToolCalls().get(0).getStringArg("keyword"));
        }

        @Test
        @DisplayName("tool_call 缺少 arguments 字段时参数保持 null")
        void chat_missingToolArguments_shouldStayNull() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"\",\"tool_calls\":["
                    + "{\"id\":\"call_1\",\"function\":{\"name\":\"t\"}}]}}]}");

            AiResponse response = client().chat(new AiRequest());

            assertNull(response.getToolCalls().get(0).getArguments());
        }
    }

    // ==================== chat：HTTP/网络错误 ====================

    @Nested
    @DisplayName("chat — HTTP 与网络错误")
    class ChatHttpErrors {

        @Test
        @DisplayName("HTTP 401 抛带状态码的异常且不含正文（对齐降级链路语义）")
        void chat_http401_shouldThrowWithStatusCode() {
            stubResponse(401, "{\"error\":{\"message\":\"invalid api key\"}}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek API error: HTTP 401", ex.getMessage());
        }

        @Test
        @DisplayName("HTTP 500 抛带状态码的异常")
        void chat_http500_shouldThrowWithStatusCode() {
            stubResponse(500, "internal error");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("DeepSeek API error: HTTP 500", ex.getMessage());
        }

        @Test
        @DisplayName("连接失败包装为 RuntimeException 且保留 IOException cause")
        void chat_connectionRefused_shouldWrapIOException() throws IOException {
            DeepSeekClient client = clientAt(closedPort());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.chat(new AiRequest()));

            assertTrue(ex.getMessage().startsWith("DeepSeek API call failed: "),
                    "实际消息: " + ex.getMessage());
            assertInstanceOf(IOException.class, ex.getCause());
        }

        @Test
        @DisplayName("HTTP 200 但响应体为空：Jackson readTree 返回 MissingNode → 走空正文守卫（而非 JSON 解析失败）")
        void chat_emptyResponseBody_shouldHitEmptyContentGuard() {
            stubResponse(200, "");
            DeepSeekClient client = client();

            RuntimeException ex = assertThrows(RuntimeException.class, () -> client.chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=null, completionTokens=0)",
                    ex.getMessage());
        }

        @Test
        @DisplayName("响应对象 body() 为 null（真实 HTTP 流不可构造）：错误分支降级为空串")
        void chat_errorWithNullResponseBody_shouldStillThrowStatusCode() throws Exception {
            DeepSeekClient client = clientAt(1);
            injectFakeHttpClient(client, fakeClient(nullBodyResponse(401, "Unauthorized")));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> client.chat(new AiRequest()));

            assertEquals("DeepSeek API error: HTTP 401", ex.getMessage());
            assertEquals(1, ex.getSuppressed().length,
                    "try-with-resources 关闭 null-body 响应时 OkHttp 抛出的 IllegalStateException 应作为 suppressed 附带");
            assertInstanceOf(IllegalStateException.class, ex.getSuppressed()[0]);
        }

        @Test
        @DisplayName("响应对象 body() 为 null 且 200：正文按空串处理后触发空正文守卫")
        void chat_successWithNullResponseBody_shouldHitEmptyContentGuard() throws Exception {
            DeepSeekClient client = clientAt(1);
            injectFakeHttpClient(client, fakeClient(nullBodyResponse(200, "OK")));

            RuntimeException ex = assertThrows(RuntimeException.class, () -> client.chat(new AiRequest()));

            assertEquals("DeepSeek returned empty content (finishReason=null, completionTokens=0)",
                    ex.getMessage());
        }
    }

    // ==================== chatStream ====================

    @Nested
    @DisplayName("chatStream — SSE 流式")
    class ChatStream {

        @Test
        @DisplayName("逐行解析 data: 前缀的 delta 并回调 token；[DONE] 立即停止；脏行被跳过")
        void chatStream_success_shouldEmitTokensUntilDone() throws Exception {
            stubResponse(200, String.join("\n",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}",
                    "event: ping",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}",
                    "data: {\"choices\":[]}",
                    "data: {\"choices\":{\"not\":\"array\"}}",
                    "data: {\"choices\":[{\"delta\":{}}]}",
                    "data: ",
                    "data: {invalid json",
                    "data: [DONE]",
                    "data: {\"choices\":[{\"delta\":{\"content\":\"after-done\"}}]}",
                    ""));
            RecordingCallback callback = new RecordingCallback();

            client().chatStream(new AiRequest(), callback);

            assertEquals(Arrays.asList("你", "好"), callback.tokens);
            assertEquals(1, callback.completions.get());
            assertTrue(callback.errors.isEmpty());
            assertTrue(capturedBody().path("stream").asBoolean(), "流式请求必须携带 stream=true");
        }

        @Test
        @DisplayName("HTTP 非成功状态：onError 携带状态码与响应体，onComplete 不触发")
        void chatStream_httpError_shouldReportErrorWithBody() {
            stubResponse(429, "rate limited");
            RecordingCallback callback = new RecordingCallback();

            client().chatStream(new AiRequest(), callback);

            assertEquals(1, callback.errors.size());
            assertEquals("HTTP 429: rate limited", callback.errors.get(0).getMessage());
            assertEquals(0, callback.completions.get());
            assertTrue(callback.tokens.isEmpty());
        }

        @Test
        @DisplayName("连接失败：onError 收到原始 IOException，onComplete 不触发")
        void chatStream_connectionRefused_shouldReportIOException() throws IOException {
            DeepSeekClient client = clientAt(closedPort());
            RecordingCallback callback = new RecordingCallback();

            client.chatStream(new AiRequest(), callback);

            assertEquals(1, callback.errors.size());
            assertInstanceOf(IOException.class, callback.errors.get(0));
            assertEquals(0, callback.completions.get());
        }

        @Test
        @DisplayName("响应 body() 为 null：先 onComplete，随后 try-with-resources 关闭响应时 OkHttp 抛 IllegalStateException 逸出")
        void chatStream_nullResponseBody_shouldCompleteThenCloseFails() throws Exception {
            DeepSeekClient client = clientAt(1);
            injectFakeHttpClient(client, fakeClient(nullBodyResponse(200, "OK")));
            RecordingCallback callback = new RecordingCallback();

            // 记录现状：onComplete 已回调成功，但 close() 抛出的 ISE 会逸出 chatStream（见最终报告的观察项）
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.chatStream(new AiRequest(), callback));

            assertEquals("response is not eligible for a body and must not be closed", ex.getMessage());
            assertEquals(1, callback.completions.get());
            assertTrue(callback.errors.isEmpty());
            assertTrue(callback.tokens.isEmpty());
        }

        @Test
        @DisplayName("响应 body() 为 null 且 close() 不抛异常（非 OkHttp 实现的契约边界）：onComplete 后 return 正常返回")
        void chatStream_nullBodyWithLenientClose_shouldReturnAfterComplete() throws Exception {
            DeepSeekClient client = clientAt(1);
            Response lenient = mock(Response.class);
            when(lenient.isSuccessful()).thenReturn(true);
            when(lenient.body()).thenReturn((ResponseBody) null);
            // mock 的 close() 是 no-op：验证「onComplete 后 return」分支本身（真实 OkHttp close() 必抛 ISE，
            // 该组合在生产不可达，见 chatStream_nullResponseBody_shouldCompleteThenCloseFails）
            injectFakeHttpClient(client, fakeClient(lenient));
            RecordingCallback callback = new RecordingCallback();

            assertDoesNotThrow(() -> client.chatStream(new AiRequest(), callback));

            assertEquals(1, callback.completions.get());
            assertTrue(callback.errors.isEmpty());
            assertTrue(callback.tokens.isEmpty());
        }
    }

    // ==================== healthCheck ====================

    @Nested
    @DisplayName("healthCheck — 健康探针")
    class HealthCheck {

        @Test
        @DisplayName("探针成功：返回 true，请求体强制正文回复且预留推理预算")
        void healthCheck_success_shouldReturnTrueWithProbeBody() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}");

            assertTrue(client().healthCheck());

            String body = lastRequestBody.get();
            assertTrue(body.contains("Reply with the single word: OK"), "探针必须要求可见正文回复");
            assertTrue(body.contains("\"max_tokens\":256"), "探针必须留足推理预算，防止空正文误判");
        }

        @Test
        @DisplayName("探针遇到 HTTP 错误：返回 false 而非抛异常")
        void healthCheck_httpError_shouldReturnFalse() {
            stubResponse(503, "unavailable");

            assertFalse(client().healthCheck());
        }

        @Test
        @DisplayName("探针收到空正文（推理耗尽形态）：空正文守卫触发，返回 false")
        void healthCheck_emptyContent_shouldReturnFalse() {
            stubResponse(200, "{\"choices\":[{\"message\":{\"content\":\"  \"},"
                    + "\"finish_reason\":\"max_tokens\"}]}");

            assertFalse(client().healthCheck());
        }
    }

    // ==================== 伪造 OkHttp 调用（覆盖真实网络不可达的 null body 分支） ====================

    private static Response nullBodyResponse(int code, String message) {
        return new Response.Builder()
                .request(new Request.Builder().url("http://127.0.0.1:1/").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(message)
                .body(null)
                .build();
    }

    private static OkHttpClient fakeClient(Response canned) {
        return new OkHttpClient() {
            @Override
            public Call newCall(Request request) {
                return new Call() {
                    @Override
                    public Request request() {
                        return request;
                    }

                    @Override
                    public Response execute() {
                        return canned;
                    }

                    @Override
                    public void enqueue(Callback responseCallback) {
                        throw new UnsupportedOperationException("测试桩仅支持同步调用");
                    }

                    @Override
                    public void cancel() {
                    }

                    @Override
                    public boolean isExecuted() {
                        return false;
                    }

                    @Override
                    public boolean isCanceled() {
                        return false;
                    }

                    @Override
                    public Timeout timeout() {
                        return Timeout.NONE;
                    }

                    @Override
                    public Call clone() {
                        return this;
                    }
                };
            }
        };
    }

    private static void injectFakeHttpClient(DeepSeekClient client, OkHttpClient fake) throws Exception {
        Field field = DeepSeekClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, fake);
    }

    // ==================== 保障测试桩本身可用 ====================

    @Test
    @DisplayName("测试桩自检：null body 的伪造响应确实使 body() 返回 null")
    void fakeNullBodyResponse_shouldActuallyHaveNullBody() {
        assertNull(nullBodyResponse(200, "OK").body());
        assertNotNull(nullBodyResponse(200, "OK").request());
    }
}
