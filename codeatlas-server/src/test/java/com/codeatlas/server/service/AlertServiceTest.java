package com.codeatlas.server.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.UndeclaredThrowableException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ========== 时间敏感路径、日志与连接生命周期补强 ==========

    @Test
    @DisplayName("构造日志：配置 webhook 与未配置时输出对应初始化信息")
    void constructor_shouldLogConfigurationStatePerBranch() {
        Logger alertLogger = (Logger) LoggerFactory.getLogger(AlertService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        alertLogger.addAppender(appender);
        try {
            new AlertService(webhookUrl);
            new AlertService("");

            List<String> messages = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
            assertEquals(2, messages.size(), "两次构造应各产生一条初始化日志: " + messages);
            assertTrue(messages.get(0).contains("initialized with webhook"),
                    "配置 webhook 时应输出启用日志: " + messages.get(0));
            assertTrue(messages.get(1).contains("no webhook configured"),
                    "未配置 webhook 时应输出禁用日志: " + messages.get(1));
        } finally {
            alertLogger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    @DisplayName("连续三次失败：退避等待完整的 2s+4s（约 6 秒）后才放弃")
    void sendAlertSync_threeFailedAttempts_shouldWaitFullBackoff() {
        responseSequence.add(500);
        responseSequence.add(500);
        responseSequence.add(500);

        long start = System.currentTimeMillis();
        boolean success = service.sendAlertSync(TITLE, CONTENT);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(success);
        assertEquals(3, requests.size());
        assertTrue(elapsed >= 5500, "两次退避应等待约 2s+4s，实际仅 " + elapsed + "ms");
    }

    @Test
    @DisplayName("连接异常路径：catch 分支退避等待完整的 2s+4s（约 6 秒）")
    void sendAlertSync_connectionErrors_shouldWaitFullBackoffInCatchPath() throws IOException {
        AlertService broken = new AlertService("http://127.0.0.1:" + unusedPort() + "/webhook");

        long start = System.currentTimeMillis();
        boolean success = broken.sendAlertSync(TITLE, CONTENT);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(success);
        assertTrue(elapsed >= 5500, "catch 路径两次退避应等待约 2s+4s，实际仅 " + elapsed + "ms");
    }

    @Test
    @DisplayName("退避期间被中断：返回 false 且恢复线程中断标记")
    void sendAlertSync_interruptDuringBackoff_shouldPreserveInterruptFlag() throws IOException {
        AlertService broken = new AlertService("http://127.0.0.1:" + unusedPort() + "/webhook");
        try {
            Thread.currentThread().interrupt();

            boolean success = broken.sendAlertSync(TITLE, CONTENT);

            assertFalse(success);
            assertTrue(Thread.currentThread().isInterrupted(), "中断退避后应恢复线程中断标记");
        } finally {
            Thread.interrupted(); // 清理中断标记
        }
    }

    @Test
    @DisplayName("非 2xx 重试路径：首次响应状态行无法解析（JDK 记为 -1）经 2 秒退避后重试成功")
    void sendAlertSync_exceptionThenSuccess_shouldRetryInCatchPath() throws Exception {
        // 注意：实测 JDK 行为——首次连接被 RST/FIN 关闭时会把请求透明重放到新连接；而无法
        // 解析的状态行（如 XYZXYZ）并不抛异常，HttpURLConnection 将其记为 responseCode=-1，
        // postWebhook 返回 false，走的是普通重试路径（82-86 行）而非 catch 路径。
        try (ServerSocket rawServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger accepts = new AtomicInteger();
            AtomicInteger completedRequests = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!rawServer.isClosed()) {
                        Socket socket = rawServer.accept();
                        int n = accepts.incrementAndGet();
                        Thread handler = new Thread(() -> {
                            if (n == 1) {
                                serveMalformedStatus(socket);
                                return;
                            }
                            serveOneConnection(socket, completedRequests, new AtomicInteger());
                        });
                        handler.setDaemon(true);
                        handler.start();
                    }
                } catch (IOException ignored) {
                    // 服务端关闭
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            AlertService raw = new AlertService(
                    "http://127.0.0.1:" + rawServer.getLocalPort() + "/webhook");

            long start = System.currentTimeMillis();
            boolean success = raw.sendAlertSync(TITLE, CONTENT);
            long elapsed = System.currentTimeMillis() - start;

            assertTrue(success, "首次响应异常后应退避重试并成功");
            assertEquals(2, accepts.get(), "异常后应重新建立第二条连接，实际 " + accepts.get());
            assertEquals(1, completedRequests.get(), "第二条连接应完成一次请求，实际 " + completedRequests.get());
            assertTrue(elapsed >= 1900,
                    "必须真实经过 2 秒退避才重试（证明非 JDK 透明重放），实际 " + elapsed + "ms");
        }
    }

    @Test
    @DisplayName("捕获路径递归返回透传：首次连接被拒(ConnectException)，退避期间服务重绑恢复，重试成功应返回 true")
    void sendAlertSync_connectRefusedThenServerAppears_shouldReturnTrueViaCatchRecursion() throws Exception {
        // attempt 0：端口无监听 → ConnectException（IOException）必然进入 catch 分支；
        // 其 2 秒退避窗口内把服务重绑到同一端口 → attempt 1 成功。
        // 原实现第 101 行 `return doSendWithRetry(...)` 的 true 会透传给 sendAlertSync；
        // 强制返回 false 的变异体会让 sendAlertSync 返回 false → 被本测试杀死。
        int port;
        try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reserve.getLocalPort();
        } // 关闭后该端口无监听者

        AlertService raw = new AlertService("http://127.0.0.1:" + port + "/webhook");
        AtomicBoolean result = new AtomicBoolean(false);
        long start = System.currentTimeMillis();
        Thread caller = new Thread(() -> result.set(raw.sendAlertSync(TITLE, CONTENT)), "alert-caller");
        caller.setDaemon(true);
        caller.start();

        // 本地连接拒绝是毫秒级的；留 500ms 余量后重绑端口（必须赶在 2000ms 退避结束前完成）
        Thread.sleep(500);
        ServerSocket server = null;
        for (int i = 0; i < 20 && server == null; i++) {
            try {
                server = new ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"));
            } catch (IOException retry) {
                Thread.sleep(50);
            }
        }
        assertNotNull(server, "退避窗口内应能重新绑定端口 " + port);

        AtomicInteger completedRequests = new AtomicInteger();
        try (ServerSocket bound = server) {
            bound.setSoTimeout(6000);
            Socket socket = bound.accept();
            drainRequest(socket);
            OutputStream out = socket.getOutputStream();
            out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            completedRequests.incrementAndGet();
            socket.close();
        }

        caller.join(10_000);
        long elapsed = System.currentTimeMillis() - start;

        assertFalse(caller.isAlive(), "重试完成后调用线程应已结束");
        assertTrue(result.get(), "捕获路径递归成功应透传 true（强制返回 false 的变异体会返回 false）");
        assertEquals(1, completedRequests.get(), "服务端应恰好完成一次重试请求");
        assertTrue(elapsed >= 1900, "捕获路径应等待一次完整退避(约 2s)，实际 " + elapsed + "ms");
    }

    @Test
    @DisplayName("去重窗口边界：恰好 300_000ms 仍判定为重复")
    void isDuplicate_exactlyAtDedupWindow_shouldStillBeDuplicate() {
        Map<String, Long> recent = recentAlerts();
        boolean observedDuplicate = false;
        for (int i = 0; i < 1000 && !observedDuplicate; i++) {
            recent.put(TITLE, System.currentTimeMillis() - 300_000L);
            Boolean duplicate = ReflectionTestUtils.invokeMethod(service, "isDuplicate", TITLE);
            if (Boolean.TRUE.equals(duplicate)) {
                observedDuplicate = true;
            }
        }
        assertTrue(observedDuplicate, "同一毫秒内的『恰好 5 分钟』应判定为重复告警");
    }

    @Test
    @DisplayName("清理边界：恰好等于截止时间（同毫秒）的记录应被保留")
    void cleanupDedupCache_exactlyAtCutoff_shouldKeepEntry() {
        Map<String, Long> recent = recentAlerts();
        boolean observedKept = false;
        for (int i = 0; i < 1000 && !observedKept; i++) {
            recent.put(TITLE, System.currentTimeMillis() - 300_000L);
            service.cleanupDedupCache();
            if (recent.containsKey(TITLE)) {
                observedKept = true;
            }
        }
        assertTrue(observedKept, "恰好等于截止时间的记录不应被清理");
    }

    @Test
    @DisplayName("响应码恰好 300：不属于成功区间，触发重试后再成功")
    void postWebhook_codeExactly300_shouldFailAndRetry() {
        responseSequence.add(300);

        boolean success = service.sendAlertSync(TITLE, CONTENT);

        assertTrue(success);
        assertEquals(2, requests.size(), "300 不应视为成功，应重试一次");
    }

    @Test
    @DisplayName("连接超时：黑洞地址 5 秒内未握手抛 SocketTimeoutException")
    void postWebhook_connectTimeout_shouldThrowSocketTimeout() throws Exception {
        AlertService blackhole = new AlertService("http://192.0.2.1:81/webhook");
        ExecutorService executor = newDaemonExecutor("connect-timeout-probe");
        try {
            long start = System.currentTimeMillis();
            Future<?> future = executor.submit(
                    () -> ReflectionTestUtils.invokeMethod(blackhole, "postWebhook", "{}"));

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> future.get(8, TimeUnit.SECONDS));
            long elapsed = System.currentTimeMillis() - start;

            assertInstanceOf(UndeclaredThrowableException.class, failure.getCause());
            assertInstanceOf(SocketTimeoutException.class,
                    ((UndeclaredThrowableException) failure.getCause()).getUndeclaredThrowable());
            assertTrue(elapsed >= 4500, "应等待约 5 秒连接超时，实际 " + elapsed + "ms");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("读取超时：服务端不响应时 5 秒内抛 SocketTimeoutException")
    void postWebhook_readTimeout_shouldThrowSocketTimeout() throws Exception {
        try (ServerSocket stalledServer = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread holder = new Thread(() -> {
                try (Socket acceptedSocket = stalledServer.accept()) {
                    Thread.sleep(60_000); // 接受连接但一直不响应
                } catch (Exception ignored) {
                    // 测试结束后连接被关闭
                }
            });
            holder.setDaemon(true);
            holder.start();

            AlertService stalled = new AlertService(
                    "http://127.0.0.1:" + stalledServer.getLocalPort() + "/webhook");
            ExecutorService executor = newDaemonExecutor("read-timeout-probe");
            try {
                long start = System.currentTimeMillis();
                Future<?> future = executor.submit(
                        () -> ReflectionTestUtils.invokeMethod(stalled, "postWebhook", "{}"));

                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> future.get(8, TimeUnit.SECONDS));
                long elapsed = System.currentTimeMillis() - start;

                assertInstanceOf(UndeclaredThrowableException.class, failure.getCause());
                assertInstanceOf(SocketTimeoutException.class,
                        ((UndeclaredThrowableException) failure.getCause()).getUndeclaredThrowable());
                assertTrue(elapsed >= 4500, "应等待约 5 秒读取超时，实际 " + elapsed + "ms");
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("发送完成后断开连接：本服务器完成的每条请求都必须在观测窗口内被客户端断开")
    void postWebhook_shouldCloseConnectionAfterResponse() throws Exception {
        try (ServerSocket rawServer = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            AtomicInteger accepts = new AtomicInteger();
            AtomicInteger completedRequests = new AtomicInteger();
            AtomicInteger closedConnections = new AtomicInteger();
            Thread acceptor = new Thread(() -> {
                try {
                    while (!rawServer.isClosed()) {
                        Socket socket = rawServer.accept();
                        accepts.incrementAndGet();
                        Thread handler = new Thread(() -> serveOneConnection(socket, completedRequests, closedConnections));
                        handler.setDaemon(true);
                        handler.start();
                    }
                } catch (IOException ignored) {
                    // 服务端关闭
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();

            AlertService raw = new AlertService(
                    "http://127.0.0.1:" + rawServer.getLocalPort() + "/webhook");

            // JDK 的 keep-alive 连接池按 (host, port) 全局缓存，本测试的服务端口可能回收自先前测试，
            // 池中或残留指向旧端点的陈旧连接。先发一条预热请求消耗掉残留连接（未读 body 的响应不会
            // 再次进池），随后重置计数，使观测基线尽量干净。
            raw.sendAlertSync("连接预热", "flush stale pooled connections");
            accepts.set(0);
            completedRequests.set(0);
            closedConnections.set(0);

            assertTrue(raw.sendAlertSync(TITLE, "第一条断开检测"));
            assertTrue(raw.sendAlertSync("第二条告警", CONTENT));

            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline
                    && !(completedRequests.get() >= 2 && closedConnections.get() >= 2)) {
                Thread.sleep(25);
            }

            // 不变量断言（不依赖池状态）：即使个别发送落在池中陈旧连接上（不会到达本服务器），
            // 本服务器"每完成一条请求，该连接都必须被客户端断开"仍成立——completed 与 closed
            // 成对增长；而 disconnect() 被移除的变异体会让 closed 停在 0，从而无法满足该不变量。
            assertTrue(completedRequests.get() >= 1,
                    "至少应有一次发送真实到达本测试服务器并完成，实际 " + completedRequests.get());
            assertTrue(closedConnections.get() >= completedRequests.get(),
                    "本服务器完成的每条请求都应被客户端断开，completed=" + completedRequests.get()
                            + " closed=" + closedConnections.get());
        }
    }

    private static ExecutorService newDaemonExecutor(String threadName) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** 第一次连接：读走请求后回一个无法解析的状态行（JDK 记为 responseCode=-1）并断开。 */
    private static void serveMalformedStatus(Socket socket) {
        try (socket) {
            drainRequest(socket);
            OutputStream out = socket.getOutputStream();
            out.write("XYZXYZ\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
            // 客户端提前断开时忽略
        }
    }

    /** 读取并丢弃一个完整 HTTP 请求（请求行 + 头 + Content-Length 指定的请求体）。 */
    private static void drainRequest(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                break;
            }
        }
        int requestLength = 0;
        for (String line : head.toString().split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                requestLength = Integer.parseInt(line.substring(15).trim());
            }
        }
        byte[] buffer = new byte[1024];
        int remaining = requestLength;
        while (remaining > 0) {
            int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read == -1) {
                return;
            }
            remaining -= read;
        }
    }

    /** 原始 keep-alive HTTP 服务端：读一个请求并响应，之后等待客户端主动断开。 */
    private static void serveOneConnection(Socket socket, AtomicInteger completedRequests,
                                           AtomicInteger closedConnections) {
        try (socket) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                head.append((char) b);
                if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) {
                    break;
                }
            }
            if (b == -1) {
                closedConnections.incrementAndGet();
                return;
            }
            int requestLength = 0;
            for (String line : head.toString().split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    requestLength = Integer.parseInt(line.substring(15).trim());
                }
            }
            byte[] buffer = new byte[1024];
            int remaining = requestLength;
            while (remaining > 0) {
                int n = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (n == -1) {
                    closedConnections.incrementAndGet();
                    return;
                }
                remaining -= n;
            }
            completedRequests.incrementAndGet();
            byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 200 OK\r\nContent-Length: " + ok.length + "\r\nConnection: keep-alive\r\n\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(ok);
            out.flush();
            while (in.read() != -1) {
                // 等待客户端断开（EOF 或连接重置）
            }
            closedConnections.incrementAndGet();
        } catch (Exception e) {
            closedConnections.incrementAndGet();
        }
    }

    private static int unusedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
