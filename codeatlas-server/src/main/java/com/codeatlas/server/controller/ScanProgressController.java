package com.codeatlas.server.controller;

import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ProjectAccessChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scans")
public class ScanProgressController {

    private static final Logger log = LoggerFactory.getLogger(ScanProgressController.class);
    private final ObjectMapper objectMapper;
    private final ProjectAccessChecker projectAccessChecker;

    public ScanProgressController(ObjectMapper objectMapper, ProjectAccessChecker projectAccessChecker) {
        this.objectMapper = objectMapper;
        this.projectAccessChecker = projectAccessChecker;
    }

    /** projectId → 该项目的 SseEmitter 列表 */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();
    /** projectId → 最近一次进度事件快照，客户端刷新/重连时用于立即恢复当前阶段 */
    private final Map<Long, ScanProgressEvent> latestEvents = new ConcurrentHashMap<>();
    /** emitter → 对应的 heartbeat 定时任务，emitter 关闭时需取消 */
    private final Map<SseEmitter, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter streamProgress(@PathVariable Long projectId,
                                     @RequestParam(required = false) Long scanId,
                                     @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min timeout
        emitters.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(projectId, emitter));
        emitter.onTimeout(() -> removeEmitter(projectId, emitter));
        emitter.onError(e -> removeEmitter(projectId, emitter));

        boolean terminalReplayed = false;
        // 发送连接确认
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"projectId\":" + projectId + "}"));
            // 回放最近一次进度快照，客户端刷新/重连后立即恢复到当前阶段；
            // 非终态一律回放；终态仅在订阅方携带同一次扫描的 scanId 时回放（见 shouldReplay），
            // 让"连接建立前就已失败/完成"的扫描也能把终态送达订阅者
            ScanProgressEvent snapshot = latestEvents.get(projectId);
            if (snapshot != null && shouldReplay(snapshot, scanId)) {
                emitter.send(SseEmitter.event()
                        .name("progress")
                        .data(objectMapper.writeValueAsString(snapshot)));
                terminalReplayed = isTerminal(snapshot.getStage());
            }
        } catch (IOException e) {
            removeEmitter(projectId, emitter);
        }

        if (terminalReplayed) {
            emitter.complete();
            removeEmitter(projectId, emitter);
            return emitter;
        }

        // SSE 心跳：每 15s 发送注释行，防止中间件/代理因空闲断开连接
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                removeEmitter(projectId, emitter);
            }
        }, 15, 15, TimeUnit.SECONDS);
        heartbeats.put(emitter, heartbeat);

        return emitter;
    }

    /**
     * 快照回放判定：非终态快照回放用于恢复当前阶段；终态快照只回放给显式携带
     * 同一次扫描 scanId 的订阅者——无 scanId 的偶然订阅者收到陈旧终态会误判为"刚完成"
     */
    static boolean shouldReplay(ScanProgressEvent snapshot, Long requestedScanId) {
        if (!isTerminal(snapshot.getStage())) {
            return true;
        }
        return requestedScanId != null && requestedScanId.equals(snapshot.getScanId());
    }

    static boolean isTerminal(String stage) {
        return "COMPLETED".equals(stage) || "FAILED".equals(stage);
    }

    @Async("scanExecutor")
    @EventListener
    public void onScanProgress(ScanProgressEvent event) {
        // 无论是否有活跃订阅者，先记录快照（无订阅者时后续重连可回放）
        latestEvents.put(event.getProjectId(), event);

        CopyOnWriteArrayList<SseEmitter> list = emitters.get(event.getProjectId());
        if (list == null || list.isEmpty()) return;

        try {
            String json = objectMapper.writeValueAsString(event);
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event()
                            .name("progress")
                            .data(json));
                    if ("COMPLETED".equals(event.getStage()) || "FAILED".equals(event.getStage())) {
                        emitter.complete();
                    }
                } catch (IOException e) {
                    emitter.completeWithError(e);
                    removeEmitter(event.getProjectId(), emitter);
                }
            }

            if ("COMPLETED".equals(event.getStage()) || "FAILED".equals(event.getStage())) {
                emitters.remove(event.getProjectId());
            }
        } catch (Exception e) {
            log.warn("Failed to send SSE event: {}", e.getMessage());
        }
    }

    private void removeEmitter(Long projectId, SseEmitter emitter) {
        ScheduledFuture<?> heartbeat = heartbeats.remove(emitter);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(projectId);
            }
        }
    }
}
