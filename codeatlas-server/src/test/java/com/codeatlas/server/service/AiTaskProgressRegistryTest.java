package com.codeatlas.server.service;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeatlas.common.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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

    // ==================== 以下为变异体杀灭追加（PIT 幸存者定点清除） ====================

    @Test
    @DisplayName("首次 cancel 恰好记录一条取消日志（CAS 返回真才落日志）")
    void cancel_once_shouldLogExactlyOneCancelEvent() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            registry.subscribe(TASK_ID, PROJECT_ID);

            registry.cancel(TASK_ID);

            assertEquals(1, countLogEvents(appender, "AI task cancelled: taskId=" + TASK_ID),
                    "首次取消必须落下唯一一条取消日志");
            assertTrue(registry.isCancelled(TASK_ID));
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("cancel 未知 taskId：静默 no-op 不抛异常、不落日志")
    void cancel_unknownTaskId_shouldBeSilentNoOp() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            assertDoesNotThrow(() -> registry.cancel("no-such-aitask"));

            assertFalse(registry.isCancelled("no-such-aitask"));
            assertEquals(0, countLogEvents(appender, "AI task cancelled: taskId=no-such-aitask"));
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("收尾后再 cancel：不落取消日志")
    void cancel_afterComplete_shouldLogNothing() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            registry.subscribe(TASK_ID, PROJECT_ID);
            registry.complete(TASK_ID);

            registry.cancel(TASK_ID);

            assertFalse(registry.isCancelled(TASK_ID));
            assertEquals(0, countLogEvents(appender, "AI task cancelled: taskId=" + TASK_ID));
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("complete：注销全部心跳并清空发射器注册（防残留定时任务与悬挂引用）")
    void complete_shouldCancelHeartbeatsAndClearEmitters() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);

        registry.subscribe(TASK_ID, PROJECT_ID);

        registry.complete(TASK_ID);

        verify(future, times(1)).cancel(false);
        assertTrue(heartbeatsMap().isEmpty(), "收尾后心跳注册表必须清空");
        assertTrue(stateEmitters(TASK_ID).isEmpty(), "收尾后发射器列表必须清空");
    }

    @Test
    @DisplayName("complete：收尾时刷新 lastActiveAt（touch 调用不得被移除）")
    void complete_shouldTouchLastActiveAt() {
        stubHeartbeatExecutor();
        registry.subscribe(TASK_ID, PROJECT_ID);
        setLastActiveAt(TASK_ID, 1L);   // 人为置旧：隔离 subscribe 之外唯一的刷新来源就是 complete 内的 touch

        registry.complete(TASK_ID);

        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(tasksMap().get(TASK_ID), "finished"),
                "前置条件：complete 已执行到收尾标记");
        long lastActiveAt = (Long) ReflectionTestUtils.getField(tasksMap().get(TASK_ID), "lastActiveAt");
        assertTrue(lastActiveAt > 1L,
                "complete() 必须调用 touch 刷新 lastActiveAt；移除调用时该字段保持 1L");
    }

    @Test
    @DisplayName("惰性清理边界：活跃时间恰好为 TTL 的任务必须保留（严格大于才清理）")
    void cleanupExpired_taskExactlyAtTtlBoundary_shouldBeKept() {
        stubHeartbeatExecutor();
        for (int attempt = 0; attempt < 200; attempt++) {
            String taskId = "ttl-boundary-" + attempt;
            registry.subscribe(taskId, PROJECT_ID);
            long base = System.currentTimeMillis();
            setLastActiveAt(taskId, base - 600_000L);
            invokeCleanupExpired();
            if (tasksMap().containsKey(taskId)) {
                // 唯一可能：清理时读到的 now == base，即"恰好等于 TTL"仍被保留；
                // 边界变异体（>=）在此场景必然删除任务 → 必被杀
                return;
            }
        }
        fail("200 次尝试均未在同一毫秒内完成边界判定（原实现下几乎不可能发生）");
    }

    @Test
    @DisplayName("心跳线程工厂：非 daemon 父线程下仍产出 daemon 线程且命名为 ai-sse-heartbeat")
    void heartbeatThreadFactory_shouldBuildDaemonNamedThread() throws Exception {
        Method factoryMethod = AiTaskProgressRegistry.class.getDeclaredMethod("lambda$new$0", Runnable.class);
        factoryMethod.setAccessible(true);

        // 关键：在显式非 daemon 的父线程中调用工厂，切断"daemon 状态继承"的假象。
        // 若实现未调用 setDaemon(true)，新线程会继承父线程的非 daemon 状态 → 断言失败。
        AtomicReference<Thread> created = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread parent = new Thread(() -> {
            try {
                created.set((Thread) factoryMethod.invoke(null, (Runnable) () -> {
                }));
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "non-daemon-parent");
        parent.setDaemon(false);
        parent.start();
        parent.join();

        assertNull(failure.get(), "工厂调用不得抛出异常");
        Thread thread = created.get();
        assertNotNull(thread);
        assertTrue(thread.isDaemon(), "心跳线程必须为 daemon，避免阻止 JVM 退出");
        assertEquals("ai-sse-heartbeat", thread.getName());
    }

    @Test
    @DisplayName("心跳 tick：发射器已被外部移除时自行注销定时任务")
    void heartbeatTick_emitterRemovedExternally_shouldSelfCancel() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);

        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        ArgumentCaptor<Runnable> tickCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(executor).scheduleAtFixedRate(tickCaptor.capture(), eq(15L), eq(15L), eq(TimeUnit.SECONDS));

        stateEmitters(TASK_ID).remove(emitter);          // 外部移除但定时任务尚未注销
        tickCaptor.getValue().run();                     // tick 迟来一步

        verify(future, times(1)).cancel(false);
        assertFalse(heartbeatsMap().containsKey(emitter), "tick 内必须回收残留心跳任务");
    }

    @Test
    @DisplayName("断开回调刷新活跃时间：刚过 TTL 的任务在断开后不得被清理")
    void onEmitterClosed_shouldRefreshActivityToSurviveTtlCleanup() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        setLastActiveAt(TASK_ID, System.currentTimeMillis() - 600_001L);

        Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(emitter, "completionCallback");
        completionCallback.run();                        // onEmitterClosed → touch 刷新活跃时间

        invokeCleanupExpired();

        assertTrue(tasksMap().containsKey(TASK_ID),
                "断开回调后必须刷新活跃时间，否则活跃任务会被 TTL 清理误删");
    }

    @Test
    @DisplayName("断开全部订阅：恰好记录一条流断开取消日志")
    void disconnect_shouldLogExactlyOneCancelEvent() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);

            Runnable completionCallback = (Runnable) ReflectionTestUtils.getField(emitter, "completionCallback");
            completionCallback.run();

            assertEquals(1, countLogEvents(appender,
                            "AI task cancelled by stream disconnect: taskId=" + TASK_ID),
                    "全部订阅断开必须落下唯一一条断连取消日志");
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("仅断开部分订阅者：任务不翻取消态（仍有在线连接）")
    void disconnect_oneOfTwoSubscribers_shouldNotCancel() {
        SseEmitter first = registry.subscribe(TASK_ID, PROJECT_ID);
        registry.subscribe(TASK_ID, PROJECT_ID);

        Runnable firstCompletion = (Runnable) ReflectionTestUtils.getField(first, "completionCallback");
        firstCompletion.run();

        assertFalse(registry.isCancelled(TASK_ID), "仍有在线订阅者，不得视为用户终止");
        assertEquals(1, stateEmitters(TASK_ID).size());
    }

    @Test
    @DisplayName("publish 刷新活跃时间：刚过 TTL 的任务在阶段推送后不得被清理")
    void publish_shouldRefreshActivityToSurviveTtlCleanup() {
        registry.subscribe(TASK_ID, PROJECT_ID);
        setLastActiveAt(TASK_ID, System.currentTimeMillis() - 600_001L);

        registry.publish(TASK_ID, "第 3 轮：正在整合证据");

        invokeCleanupExpired();

        assertTrue(tasksMap().containsKey(TASK_ID),
                "阶段推送必须刷新活跃时间，防止长任务被 TTL 清理误删");
    }

    @Test
    @DisplayName("subscribe 触发惰性清理：过期孤儿任务在下次订阅时被移除")
    void subscribe_shouldCleanExpiredOrphanTask() {
        registry.subscribe("orphan-task", PROJECT_ID);
        setLastActiveAt("orphan-task", System.currentTimeMillis() - 600_001L);

        registry.subscribe(TASK_ID, PROJECT_ID);

        assertFalse(tasksMap().containsKey("orphan-task"), "过期孤儿任务必须在下次订阅时被清理");
        assertTrue(tasksMap().containsKey(TASK_ID));
    }

    @Test
    @DisplayName("重订阅刷新活跃时间：临近过期的任务重连后继续保留")
    void subscribe_shouldRefreshActivityOnResubscribe() throws InterruptedException {
        registry.subscribe(TASK_ID, PROJECT_ID);
        setLastActiveAt(TASK_ID, System.currentTimeMillis() - 600_000L + 500L);  // 距过期仅 500ms

        registry.subscribe(TASK_ID, PROJECT_ID);         // 重订阅必须 touch 刷新

        Thread.sleep(600L);                              // 若未刷新，任务此刻已过期
        registry.subscribe("trigger-task", PROJECT_ID);  // 触发惰性清理

        assertTrue(tasksMap().containsKey(TASK_ID), "重订阅必须刷新活跃时间，否则重连即被误删");
    }

    @Test
    @DisplayName("SSE 超时回调注册：触发即移除订阅者并翻取消态")
    void timeoutCallback_shouldRemoveEmitterAndCancel() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        Runnable timeoutCallback = (Runnable) ReflectionTestUtils.getField(emitter, "timeoutCallback");
        assertNotNull(timeoutCallback);

        timeoutCallback.run();

        assertTrue(stateEmitters(TASK_ID).isEmpty());
        assertTrue(registry.isCancelled(TASK_ID), "超时断开唯一订阅者 → 视为用户终止");
    }

    @Test
    @DisplayName("SSE 错误回调注册：触发即移除订阅者并翻取消态")
    void errorCallback_shouldRemoveEmitterAndCancel() {
        SseEmitter emitter = registry.subscribe(TASK_ID, PROJECT_ID);
        @SuppressWarnings("unchecked")
        Consumer<Throwable> errorCallback = (Consumer<Throwable>) ReflectionTestUtils.getField(emitter, "errorCallback");
        assertNotNull(errorCallback);

        errorCallback.accept(new IOException("client reset"));

        assertTrue(stateEmitters(TASK_ID).isEmpty());
        assertTrue(registry.isCancelled(TASK_ID), "错误断开唯一订阅者 → 视为用户终止");
    }

    // ==================== 变异体杀灭辅助方法 ====================

    private void stubHeartbeatExecutor() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        doReturn(mock(ScheduledFuture.class)).when(executor)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        ReflectionTestUtils.setField(registry, "heartbeatExecutor", executor);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tasksMap() {
        return (Map<String, Object>) ReflectionTestUtils.getField(registry, "tasks");
    }

    @SuppressWarnings("unchecked")
    private Map<SseEmitter, ScheduledFuture<?>> heartbeatsMap() {
        return (Map<SseEmitter, ScheduledFuture<?>>) ReflectionTestUtils.getField(registry, "heartbeats");
    }

    @SuppressWarnings("unchecked")
    private List<SseEmitter> stateEmitters(String taskId) {
        return (List<SseEmitter>) ReflectionTestUtils.getField(tasksMap().get(taskId), "emitters");
    }

    private void setLastActiveAt(String taskId, long value) {
        ReflectionTestUtils.setField(tasksMap().get(taskId), "lastActiveAt", value);
    }

    private void invokeCleanupExpired() {
        ReflectionTestUtils.invokeMethod(registry, "cleanupExpired");
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AiTaskProgressRegistry.class))
                .addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(AiTaskProgressRegistry.class))
                .detachAppender(appender);
    }

    private long countLogEvents(ListAppender<ILoggingEvent> appender, String formattedMessage) {
        return appender.list.stream()
                .filter(event -> formattedMessage.equals(event.getFormattedMessage()))
                .count();
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
