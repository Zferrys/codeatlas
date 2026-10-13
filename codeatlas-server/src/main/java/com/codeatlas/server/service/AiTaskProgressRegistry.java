package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AI 任务实时进度注册表：SSE 旁路推送 + 断开即取消。
 *
 * 数据流：前端生成 taskId → 订阅 GET /ai-progress(taskId) → 任务 POST 请求头带 X-Ai-Task-Id →
 * AI 流程在每个阶段 publish 阶段事件；SSE 连接全部断开且任务未完成时标记取消，
 * 多轮 AI 流程在检查点通过 isCancelled 感知后立即停止，结果丢弃。
 * taskId 为 null（扫描自动生成/旧客户端）时所有方法 no-op，完全向后兼容。
 */
@Component
public class AiTaskProgressRegistry {

    private static final Logger log = LoggerFactory.getLogger(AiTaskProgressRegistry.class);
    /** SSE 超时与心跳，与 ScanProgressController 保持同一口径 */
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long HEARTBEAT_SECONDS = 15;
    /** 任务状态保留时长：超时后惰性清理，防止"只订阅不发起请求"的孤儿任务泄漏 */
    private static final long TASK_TTL_MS = 10 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final Map<SseEmitter, ScheduledFuture<?>> heartbeats = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ai-sse-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public AiTaskProgressRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 订阅任务进度：建立 SSE 连接并回放最近一次阶段事件（晚订阅/重连不丢当前阶段） */
    public SseEmitter subscribe(String taskId, Long projectId) {
        cleanupExpired();
        TaskState state = tasks.compute(taskId, (k, existing) ->
                existing != null ? existing : new TaskState(taskId, projectId));
        if (!state.projectId.equals(projectId)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskId 已被其他项目占用");
        }
        state.touch();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        state.emitters.add(emitter);
        emitter.onCompletion(() -> onEmitterClosed(state, emitter));
        emitter.onTimeout(() -> onEmitterClosed(state, emitter));
        emitter.onError(e -> onEmitterClosed(state, emitter));

        try {
            emitter.send(SseEmitter.event().name("connected").data("{\"taskId\":\"" + taskId + "\"}"));
            Map<String, Object> snapshot = state.lastEvent;
            if (snapshot != null) {
                emitter.send(SseEmitter.event().name("progress").data(toJson(snapshot)));
            }
        } catch (Exception e) {
            onEmitterClosed(state, emitter);
            emitter.completeWithError(e);
            return emitter;
        }

        // 心跳防代理空闲断连；发射器已被移除时在 tick 内自行注销，避免残留定时任务
        ScheduledFuture<?> heartbeat = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!state.emitters.contains(emitter)) {
                cancelHeartbeat(emitter);
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("heartbeat"));
            } catch (IOException e) {
                onEmitterClosed(state, emitter);
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        heartbeats.put(emitter, heartbeat);
        return emitter;
    }

    /** 推送阶段事件；无订阅者时也保留快照，供晚订阅者回放当前阶段 */
    public void publish(String taskId, String message) {
        if (taskId == null) {
            return;
        }
        TaskState state = tasks.get(taskId);
        if (state == null || state.finished) {
            return;
        }
        state.touch();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("stage", "PROGRESS");
        event.put("message", message);
        state.lastEvent = event;
        sendToEmitters(state, toJson(event));
    }

    /** 任务是否已被取消（SSE 全断开或显式 cancel）；null/未知 taskId 时返回 false */
    public boolean isCancelled(String taskId) {
        if (taskId == null) {
            return false;
        }
        TaskState state = tasks.get(taskId);
        return state != null && state.cancelled.get();
    }

    /** 显式取消；已完成的任务不可再取消（避免任务收尾后又被断开回调翻成取消态） */
    public void cancel(String taskId) {
        if (taskId == null) {
            return;
        }
        TaskState state = tasks.get(taskId);
        if (state != null && !state.finished && state.cancelled.compareAndSet(false, true)) {
            log.info("AI task cancelled: taskId={}", taskId);
        }
    }

    /** 任务收尾：发送终态事件并关闭全部订阅连接（成功/失败/取消路径统一调用，避免客户端悬挂等待） */
    public void complete(String taskId) {
        if (taskId == null) {
            return;
        }
        TaskState state = tasks.get(taskId);
        if (state == null || state.finished) {
            return;
        }
        state.finished = true;
        state.touch();
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("stage", "DONE");
        done.put("message", "任务已结束");
        state.lastEvent = done;
        sendToEmitters(state, toJson(done));
        for (SseEmitter emitter : state.emitters) {
            cancelHeartbeat(emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        state.emitters.clear();
    }

    private void onEmitterClosed(TaskState state, SseEmitter emitter) {
        cancelHeartbeat(emitter);
        state.emitters.remove(emitter);
        state.touch();
        // 全部订阅连接断开且任务未完成 → 视为用户终止（断开即取消信号）
        if (state.emitters.isEmpty() && !state.finished && state.cancelled.compareAndSet(false, true)) {
            log.info("AI task cancelled by stream disconnect: taskId={}", state.taskId);
        }
    }

    private void sendToEmitters(TaskState state, String json) {
        for (SseEmitter emitter : state.emitters) {
            try {
                emitter.send(SseEmitter.event().name("progress").data(json));
            } catch (Exception e) {
                onEmitterClosed(state, emitter);
            }
        }
    }

    private void cancelHeartbeat(SseEmitter emitter) {
        ScheduledFuture<?> heartbeat = heartbeats.remove(emitter);
        if (heartbeat != null) {
            heartbeat.cancel(false);
        }
    }

    /** 惰性清理：已完成任务与超过 TTL 无活动的孤儿任务 */
    private void cleanupExpired() {
        long now = System.currentTimeMillis();
        tasks.values().removeIf(s -> s.finished || now - s.lastActiveAt > TASK_TTL_MS);
    }

    private String toJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static final class TaskState {
        final String taskId;
        final Long projectId;
        final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        volatile boolean finished = false;
        volatile long lastActiveAt = System.currentTimeMillis();
        /** 最近一次阶段事件快照，供晚订阅者回放 */
        volatile Map<String, Object> lastEvent;

        TaskState(String taskId, Long projectId) {
            this.taskId = taskId;
            this.projectId = projectId;
        }

        void touch() {
            lastActiveAt = System.currentTimeMillis();
        }
    }
}
