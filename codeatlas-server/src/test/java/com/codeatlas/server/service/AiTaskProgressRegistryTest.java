package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    @Test
    @DisplayName("订阅握手发送失败：连接立即移除并补发错误终态，任务视为已取消")
    void subscribe_sendThrows_shouldCompleteWithErrorAndCancel() {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (mock, context) -> doThrow(new IOException("client gone"))
                        .when(mock).send(any(SseEmitter.SseEventBuilder.class)))) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);

            SseEmitter constructed = construction.constructed().get(0);
            assertSame(constructed, emitter);
            ArgumentCaptor<Throwable> errorCaptor = ArgumentCaptor.forClass(Throwable.class);
            verify(constructed).completeWithError(errorCaptor.capture());
            assertTrue(errorCaptor.getValue() instanceof IOException, "错误终态应携带原始 IOException");
            assertEquals("client gone", errorCaptor.getValue().getMessage());
            assertTrue(registry.isCancelled(TASK_ID), "握手失败且无其他订阅者 → 视为用户终止");
        }
    }

    @Test
    @DisplayName("心跳 tick 发现订阅已断开：自行注销定时任务且不再发送心跳")
    void heartbeatTick_emitterAlreadyGone_shouldCancelSelf() throws IOException {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);

        AtomicReference<Runnable> disconnectCallback = new AtomicReference<>();
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (mock, context) -> doAnswer(invocation -> {
                    disconnectCallback.set(invocation.getArgument(0));
                    return null;
                }).when(mock).onCompletion(any(Runnable.class)))) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
            ArgumentCaptor<Runnable> tickCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).scheduleAtFixedRate(tickCaptor.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
            assertNotNull(disconnectCallback.get());

            disconnectCallback.get().run();              // 模拟连接断开 → 发射器移除、心跳注销
            verify(future, times(1)).cancel(false);

            tickCaptor.getValue().run();                 // 心跳 tick 迟来一步

            verify(future, times(1)).cancel(false);      // tick 不得重复注销
            verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class)); // 仅握手一次，心跳未发出
            assertTrue(registry.isCancelled(TASK_ID));
        }
    }

    @Test
    @DisplayName("心跳正常执行：tick 向在线订阅者发送心跳注释且不注销")
    void heartbeatTick_emitterOnline_shouldSendHeartbeat() throws IOException {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);

        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class)) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
            ArgumentCaptor<Runnable> tickCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).scheduleAtFixedRate(tickCaptor.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

            tickCaptor.getValue().run();

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class)); // 握手 + 心跳
            verify(future, never()).cancel(false);                                 // 在线订阅者不注销心跳
            assertFalse(registry.isCancelled(TASK_ID));
        }
    }

    @Test
    @DisplayName("心跳发送失败：捕获 IOException 并移除订阅者，任务翻为取消态")
    void heartbeatSendFails_shouldRemoveEmitterAndCancel() throws IOException {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);

        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (mock, context) -> doNothing().doThrow(new IOException("heartbeat broken"))
                        .when(mock).send(any(SseEmitter.SseEventBuilder.class)))) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
            ArgumentCaptor<Runnable> tickCaptor = ArgumentCaptor.forClass(Runnable.class);
            verify(executor).scheduleAtFixedRate(tickCaptor.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

            tickCaptor.getValue().run();

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class)); // 握手 + 一次失败的心跳
            verify(future).cancel(false);
            assertTrue(registry.isCancelled(TASK_ID), "心跳失败移除订阅者后无连接 → 视为用户终止");
        }
    }

    @Test
    @DisplayName("complete 对未知 taskId 与已收尾任务均为 no-op，DONE 只推一次")
    void complete_unknownOrFinishedTaskId_shouldBeNoOp() throws IOException {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class)) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
            registry.complete(TASK_ID);
            registry.complete(TASK_ID);              // 已收尾 → 直接返回
            registry.complete("unknown-task-id");    // 未知 → 直接返回

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class)); // 握手 + 单次 DONE
            assertFalse(registry.isCancelled(TASK_ID), "正常收尾不是取消");
        }
    }

    @Test
    @DisplayName("收尾时 emitter.complete 抛异常：被吞掉且不影响收尾推送")
    void complete_emitterCompleteThrows_shouldBeIgnored() throws IOException {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (mock, context) -> doThrow(new RuntimeException("already completed")).when(mock).complete())) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);

            assertDoesNotThrow(() -> registry.complete(TASK_ID));

            verify(emitter).complete();
            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class)); // 握手 + DONE
            assertFalse(registry.isCancelled(TASK_ID));
        }
    }

    @Test
    @DisplayName("推送阶段事件时连接发送失败：该订阅者被移除且任务翻取消态")
    void publish_sendFails_shouldRemoveEmitterAndCancel() throws IOException {
        try (MockedConstruction<SseEmitter> construction = mockConstruction(SseEmitter.class,
                (mock, context) -> doNothing().doThrow(new IOException("pipe broken"))
                        .when(mock).send(any(SseEmitter.SseEventBuilder.class)))) {

            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);

            registry.publish(TASK_ID, "阶段一");      // 第 2 次 send → IOException → onEmitterClosed

            verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
            verify(emitter, never()).completeWithError(any(Throwable.class));
            assertTrue(registry.isCancelled(TASK_ID));
        }
    }

    @Test
    @DisplayName("事件序列化失败：toJson 降级为 {} 且不中断推送")
    void toJson_serializationFails_shouldFallbackToEmptyJson() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialize fail"));
        AiTaskProgressRegistry failingRegistry = new AiTaskProgressRegistry(failingMapper);

        SseEmitter emitter = failingRegistry.subscribe(TASK_ID, PROJECT_ID);
        failingRegistry.publish(TASK_ID, "阶段一");

        verify(failingMapper).writeValueAsString(any());
        Set<String> payloads = bufferedPayloads(emitter);
        assertTrue(payloads.stream().anyMatch(p -> p.contains("{}")), "序列化失败必须降级为 {}");
        assertFalse(payloads.stream().anyMatch(p -> p.contains("阶段一")), "失败时不得推送原始消息");
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
