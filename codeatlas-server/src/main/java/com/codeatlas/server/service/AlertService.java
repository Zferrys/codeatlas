package com.codeatlas.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 告警推送服务 — 通过企业微信/钉钉 Webhook 发送告警消息。
 * webhook-url 未配置时静默跳过，不阻塞主流程。
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final String webhookUrl;

    public AlertService(@Value("${codeatlas.alert.webhook-url:}") String webhookUrl) {
        this.webhookUrl = (webhookUrl != null && !webhookUrl.isEmpty()) ? webhookUrl : null;
        if (this.webhookUrl != null) {
            log.info("AlertService initialized with webhook");
        } else {
            log.info("AlertService initialized — no webhook configured, alerts are disabled");
        }
    }

    /**
     * 异步发送告警，不阻塞调用方。
     */
    @Async("auditExecutor")
    public void sendAlert(String title, String content) {
        if (webhookUrl == null) {
            return;
        }
        try {
            String payload = buildMarkdownPayload(title, content);
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
            if (code >= 200 && code < 300) {
                log.info("Alert sent: {}", title);
            } else {
                log.warn("Alert webhook returned HTTP {}: {}", code, title);
            }
            conn.disconnect();
        } catch (Exception e) {
            log.warn("Failed to send alert '{}': {}", title, e.getMessage());
        }
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
