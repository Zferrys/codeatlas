package com.codeatlas.server.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AlertService 单元测试（Webhook 推送 + 去重 + 重试）")
class AlertServiceTest {

    private static final String TITLE = "磁盘空间告警";
    private static final String CONTENT = "可用空间不足 10%";

    private HttpServer server;
    private String webhookUrl;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<Integer> responseSequence = new CopyOnWriteArrayList<>();

    private AlertService service;

    private record RecordedRequest(String method, String contentType, String body) {
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", this::handle);
        server.start();
        webhookUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
        service = new AlertService(webhookUrl);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        Thread.interrupted(); // 清理可能残留的中断标记
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(new RecordedRequest(exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
        int code = responseSequence.isEmpty() ? 200 : responseSequence.remove(0);
        byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> recentAlerts() {
        return (Map<String, Long>) ReflectionTestUtils.getField(service, "recentAlerts");
    }

    // ========== 构造与禁用 ==========

    @Test
    @DisplayName("webhook 为空字符串：告警禁用，同步/异步发送均不发请求")
    void constructor_emptyWebhook_shouldDisableAlerts() {
        AlertService disabled = new AlertService("");

        assertFalse(disabled.sendAlertSync(TITLE, CONTENT));
        disabled.sendAlert(TITLE, CONTENT);

        assertTrue(requests.isEmpty(), "禁用状态下不应产生任何 HTTP 请求");
    }

    @Test
    @DisplayName("webhook 为 null：告警禁用，同步发送返回 false")
    void constructor_nullWebhook_shouldDisableAlerts() {
        AlertService disabled = new AlertService(null);

        assertFalse(disabled.sendAlertSync(TITLE, CONTENT));
    }

    // ========== 正常发送 ==========

    @Test
    @DisplayName("配置 webhook 时：同步发送成功返回 true，负载为企微 markdown 精确 JSON")
    void sendAlertSync_success_shouldPostExactMarkdownPayload() throws Exception {
        boolean success = service.sendAlertSync(TITLE, CONTENT);

        assertTrue(success);
        assertEquals(1, requests.size());
        RecordedRequest request = requests.get(0);
        assertEquals("POST", request.method());
        assertEquals("application/json; charset=utf-8", request.contentType());
        assertEquals("{\"msgtype\":\"markdown\",\"markdown\":{\"title\":\"磁盘空间告警\","
                + "\"content\":\"## 磁盘空间告警\\n\\n可用空间不足 10%\\n\\n> CodeAtlas 自动告警\"}}",
                request.body());
    }

    @Test
    @DisplayName("发送成功后写入去重缓存")
    void sendAlertSync_success_shouldRecordDedupTimestamp() {
        service.sendAlertSync(TITLE, CONTENT);

        Map<String, Long> recent = recentAlerts();
        assertNotNull(recent.get(TITLE));
    }

    // ========== 去重 ==========

    @Test
    @DisplayName("同一标题在 5 分钟窗口内：第二次异步发送被抑制，不产生请求")
    void sendAlert_withinDedupWindow_shouldSuppressDuplicate() {
        service.sendAlert(TITLE, CONTENT);
        service.sendAlert(TITLE, "第二条同标题告警");

        assertEquals(1, requests.size(), "窗口内同标题只应发送一次");
    }

    @Test
    @DisplayName("不同标题不受去重影响，各自发送")
    void sendAlert_differentTitles_shouldBothSend() {
        service.sendAlert(TITLE, CONTENT);
        service.sendAlert("JVM 内存告警", CONTENT);

        assertEquals(2, requests.size());
    }

    @Test
    @DisplayName("去重窗口过期后同标题可重新发送，并刷新时间戳")
    void sendAlert_expiredDedupWindow_shouldResend() {
        service.sendAlert(TITLE, CONTENT);
        long stale = System.currentTimeMillis() - 300_001;
        recentAlerts().put(TITLE, stale);

        service.sendAlert(TITLE, "过期后重发");

        assertEquals(2, requests.size(), "超过去重窗口应重新发送");
        assertTrue(recentAlerts().get(TITLE) > stale, "重发后时间戳应刷新");
    }

    @Test
    @DisplayName("窗口恰好 5 分钟内（未超过）仍判定为重复")
    void sendAlert_dedupWindowBoundary_shouldStillSuppress() {
        service.sendAlert(TITLE, CONTENT);
        recentAlerts().put(TITLE, System.currentTimeMillis() - 299_000);

        service.sendAlert(TITLE, "边界内重发");

        assertEquals(1, requests.size(), "未超过窗口的重复告警应被抑制");
    }

    @Test
    @DisplayName("cleanupDedupCache 只清除过期记录，保留新鲜记录")
    void cleanupDedupCache_shouldRemoveOnlyExpiredEntries() {
        Map<String, Long> recent = recentAlerts();
        recent.put("旧告警", System.currentTimeMillis() - 300_001);
        recent.put("新告警", System.currentTimeMillis());

        service.cleanupDedupCache();

        assertNull(recent.get("旧告警"));
        assertNotNull(recent.get("新告警"));
        assertEquals(1, recent.size());
    }

    @Test
    @DisplayName("scheduledCleanup 委托清理去重缓存")
    void scheduledCleanup_shouldCleanDedupCache() {
        Map<String, Long> recent = recentAlerts();
        recent.put("过期告警", System.currentTimeMillis() - 400_000);

        service.scheduledCleanup();

        assertTrue(recent.isEmpty());
    }

    // ========== 重试 ==========

    @Test
    @DisplayName("webhook 首次失败后重试成功：返回 true，共发起两次请求")
    void sendAlertSync_failThenSuccess_shouldRetryAndSucceed() {
        responseSequence.add(500);

        boolean success = service.sendAlertSync(TITLE, CONTENT);

        assertTrue(success);
        assertEquals(2, requests.size());
    }

    @Test
    @DisplayName("webhook 连续失败：重试至上限仍返回 false，共发起三次请求")
    void sendAlertSync_allAttemptsFail_shouldReturnFalseAfterThreeRequests() {
        responseSequence.add(500);
        responseSequence.add(500);
        responseSequence.add(500);

        boolean success = service.sendAlertSync(TITLE, CONTENT);

        assertFalse(success);
        assertEquals(3, requests.size(), "最多尝试 3 次");
        assertNull(recentAlerts().get(TITLE), "失败不应写入去重缓存");
    }

    @Test
    @DisplayName("连接异常路径：重试耗尽后返回 false")
    void sendAlertSync_connectionException_shouldRetryThenReturnFalse() throws IOException {
        AlertService broken = new AlertService("http://127.0.0.1:" + unusedPort() + "/webhook");

        boolean success = broken.sendAlertSync(TITLE, CONTENT);

        assertFalse(success);
    }

    @Test
    @DisplayName("异常重试等待期间线程被中断：立即返回 false（不再等待退避）")
    void sendAlertSync_interruptedDuringBackoff_shouldReturnFalseImmediately() throws IOException {
        AlertService broken = new AlertService("http://127.0.0.1:" + unusedPort() + "/webhook");
        long start = System.currentTimeMillis();

        Thread.currentThread().interrupt();
        boolean success = broken.sendAlertSync(TITLE, CONTENT);
        long elapsed = System.currentTimeMillis() - start;
        Thread.interrupted(); // 清理中断标记

        assertFalse(success);
        assertTrue(elapsed < 1900, "被中断后不应再等待 2 秒退避，实际耗时 " + elapsed + "ms");
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
