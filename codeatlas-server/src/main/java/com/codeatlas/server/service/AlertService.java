package com.codeatlas.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 告警推送服务 — 通过企业微信/钉钉 Webhook 发送告警消息。
 * webhook-url 未配置时静默跳过，不阻塞主流程。
 * 包含去重（同标题 5 分钟内不重复发送）和重试（最多 3 次指数退避）。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 2000;
    private static final long DEDUP_WINDOW_MS = 300_000; // 5 minutes

    private final String webhookUrl;
    private final ConcurrentHashMap<String, Long> recentAlerts = new ConcurrentHashMap<>();

    public AlertService(@Value("${codeatlas.alert.webhook-url:}") String webhookUrl) {
        this.webhookUrl = (webhookUrl != null && !webhookUrl.isEmpty()) ? webhookUrl : null;
        if (this.webhookUrl != null) {
            log.info("AlertService initialized with webhook");
        } else {
            log.info("AlertService initialized — no webhook configured, alerts are disabled");
        }
    }

    /**
     * 异步发送告警。相同标题在 5 分钟内不会重复发送。
     */
    @Async("auditExecutor")
    public void sendAlert(String title, String content) {
        if (webhookUrl == null) {
            return;
        }
        if (isDuplicate(title)) {
            log.debug("Alert suppressed (duplicate within {}s): {}", DEDUP_WINDOW_MS / 1000, title);
            return;
        }
        doSendWithRetry(title, content, 0);
    }

    /**
     * 同步发送告警（用于测试或必须立即发送的场景）。
     */
    public boolean sendAlertSync(String title, String content) {
        if (webhookUrl == null) {
            return false;
        }
        return doSendWithRetry(title, content, 0);
    }

    private boolean doSendWithRetry(String title, String content, int attempt) {
        try {
            String payload = buildMarkdownPayload(title, content);
            boolean success = postWebhook(payload);

            if (success) {
                recentAlerts.put(title, System.currentTimeMillis());
                log.info("Alert sent: {}", title);
                return true;
            }

            if (attempt < MAX_RETRIES - 1) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                log.warn("Alert webhook failed (attempt {}), retrying in {}ms: {}", attempt + 1, delay, title);
                Thread.sleep(delay);
                return doSendWithRetry(title, content, attempt + 1);
            }

            log.error("Alert webhook failed after {} attempts: {}", MAX_RETRIES, title);
            return false;
        } catch (Exception e) {
            if (attempt < MAX_RETRIES - 1) {
                long delay = RETRY_BASE_DELAY_MS * (1L << attempt);
                log.warn("Alert webhook error (attempt {}), retrying in {}ms: {} — {}", attempt + 1, delay, title, e.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return doSendWithRetry(title, content, attempt + 1);
            }
            log.error("Alert webhook failed after {} attempts: {} — {}", MAX_RETRIES, title, e.getMessage());
            return false;
        }
    }

    private boolean postWebhook(String payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(webhookUrl).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload.getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        conn.disconnect();
        return code >= 200 && code < 300;
    }

    /**
     * 检查是否为重复告警。同一标题在去重窗口内只发一次。
     */
    private boolean isDuplicate(String title) {
        Long lastSent = recentAlerts.get(title);
        if (lastSent == null) {
            return false;
        }
        if (System.currentTimeMillis() - lastSent > DEDUP_WINDOW_MS) {
            recentAlerts.remove(title);
            return false;
        }
        return true;
    }

    /**
     * 清理过期的去重记录。可定期调用避免内存泄漏。
     */
    public void cleanupDedupCache() {
        long cutoff = System.currentTimeMillis() - DEDUP_WINDOW_MS;
        recentAlerts.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * 定期清理过期的去重记录，防止内存泄漏。
     */
    @Scheduled(fixedRate = 600_000) // 每 10 分钟
    public void scheduledCleanup() {
        cleanupDedupCache();
    }

    private String buildMarkdownPayload(String title, String content) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("msgtype", "markdown");
        Map<String, String> markdown = new LinkedHashMap<>();
        markdown.put("title", title);
        markdown.put("content", "## " + title + "\n\n" + content + "\n\n> CodeAtlas 自动告警");
        payload.put("markdown", markdown);
        return OBJECT_MAPPER.writeValueAsString(payload);
    }
}
