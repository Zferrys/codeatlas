package com.codeatlas.server.controller;

import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scans")
public class ScanProgressController {

    private static final Logger log = LoggerFactory.getLogger(ScanProgressController.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** projectId → 该项目的 SseEmitter 列表 */
    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters = new ConcurrentHashMap<>();

    @GetMapping(value = "/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @PreAuthorize("isAuthenticated()")
    public SseEmitter streamProgress(@PathVariable Long projectId,
                                     @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 min timeout
        emitters.computeIfAbsent(projectId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(projectId, emitter));
        emitter.onTimeout(() -> removeEmitter(projectId, emitter));
        emitter.onError(e -> removeEmitter(projectId, emitter));

        // 发送连接确认
        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data("{\"projectId\":" + projectId + "}"));
        } catch (IOException e) {
            removeEmitter(projectId, emitter);
        }

        return emitter;
    }

    @EventListener
    public void onScanProgress(ScanProgressEvent event) {
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(event.getProjectId());
        if (list == null || list.isEmpty()) return;

        try {
            String json = OBJECT_MAPPER.writeValueAsString(event);
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
        CopyOnWriteArrayList<SseEmitter> list = emitters.get(projectId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                emitters.remove(projectId);
            }
        }
    }
}
