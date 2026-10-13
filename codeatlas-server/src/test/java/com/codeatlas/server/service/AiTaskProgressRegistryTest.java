package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AiTaskProgressRegistry 单元测试（SSE 进度推送 + 断开即取消）")
class AiTaskProgressRegistryTest {

    private static final String TASK_ID = "aitask-abc12345";
    private static final Long PROJECT_ID = 1L;

    private AiTaskProgressRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AiTaskProgressRegistry(new ObjectMapper());
    }

    @Test
    @DisplayName("taskId 为 null 或未知时 isCancelled 返回 false，一切写操作 no-op")
    void isCancelled_nullOrUnknownTaskId_shouldReturnFalse() {
        assertFalse(registry.isCancelled(null));
        assertFalse(registry.isCancelled("unknown-task-id"));

        // 旧客户端/扫描自动生成路径：所有方法必须静默兼容
        registry.publish(null, "阶段");
        registry.cancel(null);
        registry.complete(null);
        assertFalse(registry.isCancelled(null));
    }

    @Test
    @DisplayName("publish 对未订阅任务不落快照：晚订阅只收到 connected，不回放幽灵阶段")
    void publish_unknownTaskId_shouldNotCreateSnapshot() {
        registry.publish("unknown-task-id", "不应被保存的阶段");

        SseEmitter emitter = registry.subscribe("unknown-task-id", PROJECT_ID);

        Set<String> payloads = bufferedPayloads(emitter);
        assertTrue(payloads.stream().anyMatch(p -> p.contains("connected")));
        assertFalse(payloads.stream().anyMatch(p -> p.contains("不应被保存的阶段")));
    }

    @Test
    @DisplayName("显式 cancel 后 isCancelled 翻转为 true")
    void cancel_afterSubscribe_shouldFlipCancelled() {
        registry.subscribe(TASK_ID, PROJECT_ID);

        registry.cancel(TASK_ID);

        assertTrue(registry.isCancelled(TASK_ID));
    }

    @Test
    @DisplayName("任务收尾后再 cancel 不生效（防收尾回调翻态）")
    void cancel_afterComplete_shouldBeIgnored() {
        registry.subscribe(TASK_ID, PROJECT_ID);
        registry.complete(TASK_ID);

        registry.cancel(TASK_ID);

        assertFalse(registry.isCancelled(TASK_ID));
    }

    @Test
    @DisplayName("SSE 连接断开且任务未完成：执行 Spring 断连回调即翻取消态")
    void disconnect_beforeFinish_shouldMarkCancelled() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        // 取出的对象即 Spring 在连接断开/超时/完成时回调的同一 Runnable
        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(emitter, "completionCallback");
        assertNotNull(completionCallback);

        completionCallback.run();

        assertTrue(registry.isCancelled(TASK_ID), "全部订阅断开且任务未完成 → 视为用户终止");
    }

    @Test
    @DisplayName("任务收尾后连接断开：不再翻取消态")
    void disconnect_afterComplete_shouldNotFlipCancelled() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        registry.complete(TASK_ID);
        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(emitter, "completionCallback");

        completionCallback.run();

        assertFalse(registry.isCancelled(TASK_ID), "任务已收尾，断开回调不得再翻取消态");
    }

    @Test
    @DisplayName("收尾时向订阅者推送 DONE 终态事件（前端据此停转圈）")
    void complete_shouldSendDoneEventToSubscribers() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        registry.publish(TASK_ID, "AI 正在生成报告…");

        registry.complete(TASK_ID);

        Set<String> payloads = bufferedPayloads(emitter);
        assertTrue(payloads.stream().anyMatch(p -> p.contains("DONE") && p.contains("任务已结束")),
                "收尾必须发 DONE 终态事件，避免客户端悬挂等待");
    }

    @Test
    @DisplayName("晚订阅者回放最近一次阶段快照（切页/重连不丢当前阶段）")
    void lateSubscribe_shouldReplayLastStageSnapshot() {
        registry.subscribe(TASK_ID, PROJECT_ID);
        registry.publish(TASK_ID, "第 2 轮：已检索到 3 项代码证据");

        SseEmitter lateEmitter = registry.subscribe(TASK_ID, PROJECT_ID);

        assertTrue(bufferedPayloads(lateEmitter).stream()
                        .anyMatch(p -> p.contains("第 2 轮：已检索到 3 项代码证据")),
                "晚订阅必须回放当前阶段，否则进度条会一直空白");
    }

    @Test
    @DisplayName("同一 taskId 被其他项目订阅时抛 400")
    void subscribe_differentProjectId_shouldThrowBadRequest() {
        registry.subscribe(TASK_ID, PROJECT_ID);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> registry.subscribe(TASK_ID, 99L));

        assertEquals(400, ex.getCode());
        assertEquals("taskId 已被其他项目占用", ex.getMessage());
    }

    /** 读取未初始化 SseEmitter 中已缓冲的事件载荷（subscribe/publish 在建立连接前的直发内容） */
    private Set<String> bufferedPayloads(SseEmitter emitter) {
        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> attempts = (Set<ResponseBodyEmitter.DataWithMediaType>)
                ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        return attempts.stream()
                .map(attempt -> String.valueOf(attempt.getData()))
                .collect(Collectors.toSet());
    }
}
