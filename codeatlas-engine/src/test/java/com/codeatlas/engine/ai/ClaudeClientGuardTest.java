package com.codeatlas.engine.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ClaudeClient — 空正文守卫 + 健康探针预算")
class ClaudeClientGuardTest {

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

    private void stubResponse(String json) {
        server.createContext("/", exchange -> {
            lastRequestBody.set(readAll(exchange.getRequestBody()));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private ClaudeClient client() {
        return new ClaudeClient("test-key", "claude-sonnet-4-6",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages");
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

    @Test
    @DisplayName("HTTP 200 但正文为空（推理耗尽形态）→ chat 抛异常以触发多模型降级")
    void chat_emptyContent_shouldThrow() {
        stubResponse("{\"content\":[{\"type\":\"text\",\"text\":\"\"}],"
                + "\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":10,\"output_tokens\":256}}");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> client().chat(new AiRequest()));
        assertTrue(ex.getMessage().contains("empty content"),
                "异常信息必须标明空正文，供降级链路日志定位");
    }

    @Test
    @DisplayName("正文为纯空白同样视为失败")
    void chat_blankContent_shouldThrow() {
        stubResponse("{\"content\":[{\"type\":\"text\",\"text\":\"   \n \"}],"
                + "\"stop_reason\":\"max_tokens\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}");

        assertThrows(RuntimeException.class, () -> client().chat(new AiRequest()));
    }

    @Test
    @DisplayName("仅有 tool_use 无正文时不抛异常（合法工具调用轮）")
    void chat_toolUseOnly_shouldNotThrow() {
        stubResponse("{\"content\":[{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search_classes\","
                + "\"input\":{\"keyword\":\"demo\"}}],\"stop_reason\":\"tool_use\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}");

        AiResponse response = client().chat(new AiRequest());

        assertTrue(response.hasToolCalls());
    }

    @Test
    @DisplayName("健康探针：请求体强制正文回复且预留推理预算，避免推理模型被误判不可用")
    void healthCheck_shouldUseVisibleReplyProbe() {
        stubResponse("{\"content\":[{\"type\":\"text\",\"text\":\"OK\"}],"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}");

        assertTrue(client().healthCheck());

        String body = lastRequestBody.get();
        assertTrue(body.contains("Reply with the single word: OK"), "探针必须要求可见正文回复");
        assertTrue(body.contains("\"max_tokens\":256"), "探针必须留足推理预算，防止空正文误判");
    }
}
