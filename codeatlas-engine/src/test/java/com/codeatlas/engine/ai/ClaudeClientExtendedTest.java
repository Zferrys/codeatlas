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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ClaudeClient — 扩展分支（chatStream/工具声明/错误路径/健康探针失败）")
class ClaudeClientExtendedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void stubResponse(int status, String body) {
        server.createContext("/", exchange -> {
            lastRequestBody.set(readAll(exchange.getRequestBody()));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private ClaudeClient client() {
        return clientAt(server.getAddress().getPort());
    }

    private ClaudeClient clientAt(int port) {
        return new ClaudeClient("test-key", "claude-sonnet-4-6",
                "http://127.0.0.1:" + port + "/v1/messages");
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

    // ==================== 构造器 ====================

    @Test
    @DisplayName("单参构造器：默认模型 claude-sonnet-4-6")
    void constructor_defaultModel_shouldBeSonnet46() {
        assertEquals("claude-sonnet-4-6", new ClaudeClient("k").getModelName());
    }

    @Test
    @DisplayName("双参构造器：显式模型名原样保留")
    void constructor_explicitModel_shouldBeKept() {
        assertEquals("claude-opus-4-1", new ClaudeClient("k", "claude-opus-4-1").getModelName());
    }

    // ==================== chat 错误路径 ====================

    @Nested
    @DisplayName("chat — HTTP 错误")
    class ChatHttpErrors {

        @Test
        @DisplayName("HTTP 400：异常消息含状态码与响应体，便于降级链路定位")
        void chat_http400_shouldThrowWithStatusAndBody() {
            stubResponse(400, "bad request");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("Claude API error: HTTP 400 — bad request", ex.getMessage());
        }

        @Test
        @DisplayName("HTTP 529（过载）：异常消息同样携带状态码与响应体")
        void chat_http529_shouldThrowWithStatusAndBody() {
            stubResponse(529, "{\"error\":{\"type\":\"overloaded_error\"}}");

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client().chat(new AiRequest()));

            assertEquals("Claude API error: HTTP 529 — {\"error\":{\"type\":\"overloaded_error\"}}",
                    ex.getMessage());
        }

        @Test
        @DisplayName("连接失败：包装为 RuntimeException 且保留 IOException cause")
        void chat_connectionRefused_shouldWrapIOException() throws IOException {
            ClaudeClient client = clientAt(closedPort());

            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> client.chat(new AiRequest()));

            assertTrue(ex.getMessage().startsWith("Claude API call failed: "),
                    "实际消息: " + ex.getMessage());
            assertInstanceOf(IOException.class, ex.getCause());
        }
    }

    // ==================== chat 工具声明序列化 ====================

    @Test
    @DisplayName("声明工具时按 Anthropic 格式序列化：name/description/input_schema，且不出现 OpenAI 的 tool_choice")
    void chat_withTools_shouldSerializeAnthropicTools() throws Exception {
        stubResponse(200, "{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Collections.singletonMap("keyword",
                Collections.singletonMap("type", "string")));
        AiRequest request = new AiRequest();
        request.setPrompt("p");
        request.setTools(Arrays.asList(new ToolDef("search_classes", "按关键词搜索类", parameters)));

        client().chat(request);

        JsonNode body = MAPPER.readTree(lastRequestBody.get());
        JsonNode tools = body.path("tools");
        assertEquals(1, tools.size());
        assertEquals("search_classes", tools.get(0).path("name").asText());
        assertEquals("按关键词搜索类", tools.get(0).path("description").asText());
        assertEquals("object", tools.get(0).path("input_schema").path("type").asText());
        assertTrue(tools.get(0).path("type").isMissingNode(), "Anthropic 工具格式没有 type=function 包装");
        assertTrue(body.path("tool_choice").isMissingNode(), "Anthropic 请求不应携带 tool_choice");
    }

    // ==================== chatStream ====================

    @Nested
    @DisplayName("chatStream — SSE 流式")
    class ChatStream {

        @Test
        @DisplayName("逐行解析 content_block_delta 的 text 并回调；其他事件类型与脏行被跳过；[DONE] 停止")
        void chatStream_success_shouldEmitTextDeltasUntilDone() throws Exception {
            stubResponse(200, String.join("\n",
                    "event: message_start",
                    "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m1\"}}",
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"你\"}}",
                    "data: {\"type\":\"ping\"}",
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                    "data: {\"type\":\"content_block_delta\",\"delta\":{}}",
                    "data: ",
                    "data: {invalid json",
                    "data: [DONE]",
                    "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"after-done\"}}",
                    ""));
            RecordingCallback callback = new RecordingCallback();

            client().chatStream(new AiRequest(), callback);

            assertEquals(Arrays.asList("你", "好"), callback.tokens);
            assertEquals(1, callback.completions.get());
            assertTrue(callback.errors.isEmpty());
            JsonNode body = MAPPER.readTree(lastRequestBody.get());
            assertTrue(body.path("stream").asBoolean(), "流式请求必须携带 stream=true");
        }

        @Test
        @DisplayName("HTTP 非成功状态：onError 携带状态码与响应体，onComplete 不触发")
        void chatStream_httpError_shouldReportErrorWithBody() {
            stubResponse(500, "oops");
            RecordingCallback callback = new RecordingCallback();

            client().chatStream(new AiRequest(), callback);

            assertEquals(1, callback.errors.size());
            assertEquals("HTTP 500: oops", callback.errors.get(0).getMessage());
            assertEquals(0, callback.completions.get());
            assertTrue(callback.tokens.isEmpty());
        }

        @Test
        @DisplayName("连接失败：onError 收到原始 IOException，onComplete 不触发")
        void chatStream_connectionRefused_shouldReportIOException() throws IOException {
            ClaudeClient client = clientAt(closedPort());
            RecordingCallback callback = new RecordingCallback();

            client.chatStream(new AiRequest(), callback);

            assertEquals(1, callback.errors.size());
            assertInstanceOf(IOException.class, callback.errors.get(0));
            assertEquals(0, callback.completions.get());
        }

        @Test
        @DisplayName("响应 body() 为 null：先 onComplete，随后 try-with-resources 关闭响应时 OkHttp 抛 IllegalStateException 逸出")
        void chatStream_nullResponseBody_shouldCompleteThenCloseFails() throws Exception {
            ClaudeClient client = clientAt(1);
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
            ClaudeClient client = clientAt(1);
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

        @Test
        @DisplayName("多轮消息 + 流式：system 提炼到顶层、stream=true、tool_use/tool_result 合法配对")
        void chatStream_multiTurn_shouldSerializeLegalRoles() throws Exception {
            stubResponse(200, "data: [DONE]\n");
            AiRequest request = new AiRequest();
            request.setMessages(Arrays.asList(
                    Message.system("sys"),
                    Message.user("q"),
                    Message.assistant(Collections.singletonList(
                            new ToolCall("call_1", "search_classes", Collections.singletonMap("k", "v")))),
                    Message.tool("call_1", "r")));
            RecordingCallback callback = new RecordingCallback();

            client().chatStream(request, callback);

            assertEquals(1, callback.completions.get());
            JsonNode body = MAPPER.readTree(lastRequestBody.get());
            assertEquals("sys", body.path("system").asText());
            assertTrue(body.path("stream").asBoolean());
            assertEquals("tool_use", body.path("messages").get(1).path("content").get(0).path("type").asText());
            JsonNode toolResult = body.path("messages").get(2);
            assertEquals("user", toolResult.path("role").asText());
            assertEquals("tool_result", toolResult.path("content").get(0).path("type").asText());
            assertEquals("call_1", toolResult.path("content").get(0).path("tool_use_id").asText());
        }
    }

    // ==================== healthCheck ====================

    @Test
    @DisplayName("探针遇到 HTTP 错误：返回 false 而非抛异常")
    void healthCheck_httpError_shouldReturnFalse() {
        stubResponse(503, "unavailable");

        assertFalse(client().healthCheck());
    }

    @Test
    @DisplayName("探针收到空正文：空正文守卫触发，返回 false")
    void healthCheck_emptyContent_shouldReturnFalse() {
        stubResponse(200, "{\"content\":[{\"type\":\"text\",\"text\":\"  \"}],"
                + "\"stop_reason\":\"max_tokens\"}");

        assertFalse(client().healthCheck());
    }

    // ==================== 伪造 OkHttp 调用 ====================

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

    private static void injectFakeHttpClient(ClaudeClient client, OkHttpClient fake) throws Exception {
        Field field = ClaudeClient.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        field.set(client, fake);
    }
}
