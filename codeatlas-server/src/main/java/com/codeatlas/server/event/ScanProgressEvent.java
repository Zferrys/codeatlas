package com.codeatlas.server.event;

import java.time.LocalDateTime;

/**
 * 扫描进度事件 — 通过 ApplicationEventPublisher 推送给 SSE 订阅者。
 */
public class ScanProgressEvent {

    private final Long projectId;
    private final Long scanId;
    private final String stage;      // CLONING | PARSING | RULES | AI | COMPLETED | FAILED
    private final int progress;       // 0-100
    private final String message;
    private final LocalDateTime timestamp;

    public ScanProgressEvent(Long projectId, Long scanId, String stage, int progress, String message) {
        this.projectId = projectId;
        this.scanId = scanId;
        this.stage = stage;
        this.progress = progress;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public Long getProjectId() { return projectId; }
    public Long getScanId() { return scanId; }
    public String getStage() { return stage; }
    public int getProgress() { return progress; }
    public String getMessage() { return message; }
    public LocalDateTime getTimestamp() { return timestamp; }
}
