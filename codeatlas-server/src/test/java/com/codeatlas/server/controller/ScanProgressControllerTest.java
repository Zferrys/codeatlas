package com.codeatlas.server.controller;

import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ProjectAccessChecker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScanProgressControllerTest {

    private static ScanProgressEvent event(Long scanId, String stage) {
        return new ScanProgressEvent(1L, scanId, stage, 50, "test");
    }

    @Test
    void nonTerminalSnapshotAlwaysReplays() {
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), null));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), 7L));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "PARSING"), 99L));
    }

    @Test
    void terminalSnapshotReplaysForSameScan() {
        assertTrue(ScanProgressController.shouldReplay(event(7L, "FAILED"), 7L));
        assertTrue(ScanProgressController.shouldReplay(event(7L, "COMPLETED"), 7L));
    }

    @Test
    void terminalSnapshotSkippedWithoutScanIdOrForOtherScan() {
        assertFalse(ScanProgressController.shouldReplay(event(7L, "FAILED"), null));
        assertFalse(ScanProgressController.shouldReplay(event(7L, "COMPLETED"), null));
        assertFalse(ScanProgressController.shouldReplay(event(7L, "FAILED"), 99L));
    }

    // ==================== SSE 流式端点与事件推送 ====================

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 42L;

    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private ObjectMapper objectMapper;
    private ScanProgressController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUpStreamFixtures() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        controller = new ScanProgressController(objectMapper, projectAccessChecker);
        principal = new CodeAtlasUserDetails(USER_ID, "sse", "DEVELOPER");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, CopyOnWriteArrayList<SseEmitter>> emitters() {
        return (Map<Long, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(controller, "emitters");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, ScanProgressEvent> latestEvents() {
        return (Map<Long, ScanProgressEvent>) ReflectionTestUtils.getField(controller, "latestEvents");
    }

    @SuppressWarnings("unchecked")
    private Map<SseEmitter, ScheduledFuture<?>> heartbeats() {
        return (Map<SseEmitter, ScheduledFuture<?>>) ReflectionTestUtils.getField(controller, "heartbeats");
    }

    /** 提取 emitter 缓冲区内所有已发送数据片段（未初始化的 emitter 会把数据存在 earlySendAttempts） */
    private static String bufferedText(SseEmitter emitter) {
        @SuppressWarnings("unchecked")
        Set<ResponseBodyEmitter.DataWithMediaType> attempts =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType attempt : attempts) {
            sb.append(attempt.getData());
        }
        return sb.toString();
    }

    private static SseEmitter failingEmitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("client gone")).when(emitter).send(any(SseEmitter.SseEventBuilder.class));
        return emitter;
    }

    /** 手动触发一次周期任务（scheduleAtFixedRate 返回的真实实例实现 Runnable） */
    private static void runHeartbeatOnce(ScheduledFuture<?> heartbeat) {
        assertTrue(heartbeat instanceof Runnable, "heartbeat must be runnable");
        ((Runnable) heartbeat).run();
    }

    @Test
    @DisplayName("streamProgress — 连接确认 + 非终态快照回放 + 注册心跳，emitter/心跳均可追踪")
    void streamProgressReplaysNonTerminalSnapshotAndRegistersHeartbeat() {
        controller.onScanProgress(event(7L, "PARSING"));

        SseEmitter emitter = controller.streamProgress(PROJECT_ID, null, principal);

        assertNotNull(emitter);
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        String text = bufferedText(emitter);
        assertTrue(text.contains("event:connected"), text);
        assertTrue(text.contains("{\"projectId\":1}"), text);
        assertTrue(text.contains("event:progress"), text);
        assertTrue(text.contains("\"stage\":\"PARSING\""), text);
        assertTrue(text.contains("\"progress\":50"), text);
        List<SseEmitter> list = emitters().get(PROJECT_ID);
        assertNotNull(list);
        assertEquals(List.of(emitter), list);
        ScheduledFuture<?> heartbeat = heartbeats().get(emitter);
        assertNotNull(heartbeat);
        assertFalse(heartbeat.isCancelled());
    }

    @Test
    @DisplayName("streamProgress — 终态快照同扫描回放后立即 complete 且不残留订阅与心跳")
    void streamProgressReplaysTerminalSnapshotAndCompletes() {
        controller.onScanProgress(event(7L, "COMPLETED"));

        SseEmitter emitter = controller.streamProgress(PROJECT_ID, 7L, principal);

        assertNotNull(emitter);
        String text = bufferedText(emitter);
        assertTrue(text.contains("\"stage\":\"COMPLETED\""), text);
        assertFalse(emitters().containsKey(PROJECT_ID));
        assertTrue(heartbeats().isEmpty());
    }

    @Test
    @DisplayName("streamProgress — 终态快照但 scanId 不匹配时不回放，仅建立连接并挂心跳")
    void streamProgressSkipsStaleTerminalSnapshot() {
        controller.onScanProgress(event(7L, "FAILED"));

        SseEmitter emitter = controller.streamProgress(PROJECT_ID, 99L, principal);

        String text = bufferedText(emitter);
        assertFalse(text.contains("event:progress"), text);
        assertTrue(text.contains("event:connected"), text);
        assertNotNull(heartbeats().get(emitter));
    }

    @Test
    @DisplayName("streamProgress — 无 scanId 时终态快照不回放")
    void streamProgressSkipsTerminalSnapshotWithoutScanId() {
        controller.onScanProgress(event(7L, "FAILED"));

        SseEmitter emitter = controller.streamProgress(PROJECT_ID, null, principal);

        assertFalse(bufferedText(emitter).contains("event:progress"));
        assertNotNull(heartbeats().get(emitter));
    }

    @Test
    @DisplayName("streamProgress — 无快照时仅发送 connected")
    void streamProgressWithoutSnapshotSendsOnlyConnected() {
        SseEmitter emitter = controller.streamProgress(PROJECT_ID, null, principal);

        String text = bufferedText(emitter);
        assertTrue(text.contains("event:connected"));
        assertFalse(text.contains("event:progress"));
        assertNotNull(heartbeats().get(emitter));
    }

    @Test
    @DisplayName("streamProgress — 快照序列化抛 IOException 时摘除该订阅者但仍挂心跳")
    void streamProgressRemovesEmitterWhenSnapshotSerializationFails() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        ScanProgressController failingController = new ScanProgressController(failingMapper, projectAccessChecker);
        ScanProgressEvent snapshot = event(7L, "PARSING");
        failingController.onScanProgress(snapshot);

        SseEmitter emitter = failingController.streamProgress(PROJECT_ID, null, principal);

        assertNotNull(emitter);
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(failingMapper).writeValueAsString(captor.capture());
        assertSame(snapshot, captor.getValue());
        @SuppressWarnings("unchecked")
        Map<Long, CopyOnWriteArrayList<SseEmitter>> map =
                (Map<Long, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(failingController, "emitters");
        assertFalse(map.containsKey(PROJECT_ID));
        @SuppressWarnings("unchecked")
        Map<SseEmitter, ScheduledFuture<?>> beats =
                (Map<SseEmitter, ScheduledFuture<?>>) ReflectionTestUtils.getField(failingController, "heartbeats");
        assertEquals(1, beats.size());
    }

    @Test
    @DisplayName("streamProgress — 心跳任务执行一次发送 comment 且订阅保持")
    void streamProgressHeartbeatTickSendsComment() {
        SseEmitter emitter = controller.streamProgress(PROJECT_ID, null, principal);
        ScheduledFuture<?> heartbeat = heartbeats().get(emitter);

        runHeartbeatOnce(heartbeat);

        assertTrue(bufferedText(emitter).contains(":heartbeat"), bufferedText(emitter));
        assertEquals(List.of(emitter), emitters().get(PROJECT_ID));
        assertNotNull(heartbeats().get(emitter));
    }

    @Test
    @DisplayName("removeEmitter — 心跳存在时取消定时任务并清理空列表")
    void removeEmitterCancelsHeartbeatAndDropsEmptyList() {
        SseEmitter emitter = controller.streamProgress(PROJECT_ID, null, principal);
        ScheduledFuture<?> heartbeat = heartbeats().get(emitter);
        assertNotNull(heartbeat);

        ReflectionTestUtils.invokeMethod(controller, "removeEmitter", PROJECT_ID, emitter);

        assertTrue(heartbeat.isCancelled());
        assertTrue(heartbeats().isEmpty());
        assertFalse(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("removeEmitter — 无心跳且项目无列表时为无害空操作")
    void removeEmitterToleratesMissingEntries() {
        SseEmitter stranger = new SseEmitter();

        ReflectionTestUtils.invokeMethod(controller, "removeEmitter", PROJECT_ID, stranger);

        assertTrue(heartbeats().isEmpty());
        assertTrue(emitters().isEmpty());
    }

    // ==================== onScanProgress 事件推送 ====================

    @Test
    @DisplayName("onScanProgress — 无订阅者时仅记录快照，随后订阅可回放")
    void onScanProgressStoresSnapshotWithoutSubscribers() {
        ScanProgressEvent event = event(7L, "RULES");

        controller.onScanProgress(event);

        assertSame(event, latestEvents().get(PROJECT_ID));
        assertFalse(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — 空列表视为无订阅者直接返回")
    void onScanProgressSkipsEmptyEmitterList() {
        emitters().put(PROJECT_ID, new CopyOnWriteArrayList<>());
        ScanProgressEvent event = event(7L, "RULES");

        controller.onScanProgress(event);

        assertSame(event, latestEvents().get(PROJECT_ID));
        assertTrue(emitters().get(PROJECT_ID).isEmpty());
    }

    @Test
    @DisplayName("onScanProgress — 向全部订阅者推送 JSON 且非终态不关闭连接")
    void onScanProgressBroadcastsJsonToAllSubscribers() throws Exception {
        SseEmitter first = mock(SseEmitter.class);
        SseEmitter second = mock(SseEmitter.class);
        CopyOnWriteArrayList<SseEmitter> list = new CopyOnWriteArrayList<>(List.of(first, second));
        emitters().put(PROJECT_ID, list);

        controller.onScanProgress(event(7L, "PARSING"));

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor = ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(first).send(captor.capture());
        verify(second).send(any(SseEmitter.SseEventBuilder.class));
        String payload = payloadOf(captor.getValue());
        assertTrue(payload.contains("event:progress"), payload);
        assertTrue(payload.contains("\"scanId\":7"), payload);
        assertTrue(payload.contains("\"stage\":\"PARSING\""), payload);
        assertTrue(payload.contains("\"message\":\"test\""), payload);
        verify(first, never()).complete();
        assertTrue(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — 终态事件推送后关闭全部 emitter 并移除项目订阅")
    void onScanProgressTerminalCompletesAndDropsSubscriptions() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        emitters().put(PROJECT_ID, new CopyOnWriteArrayList<>(List.of(emitter)));

        controller.onScanProgress(event(7L, "COMPLETED"));

        verify(emitter).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter).complete();
        assertFalse(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — FAILED 终态同样关闭订阅")
    void onScanProgressFailedStageClosesSubscriptions() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        emitters().put(PROJECT_ID, new CopyOnWriteArrayList<>(List.of(emitter)));

        controller.onScanProgress(event(7L, "FAILED"));

        verify(emitter).complete();
        assertFalse(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — 单订阅者发送失败时按错误收尾并取消其心跳，其他订阅者不受影响")
    void onScanProgressHandlesSendFailure() throws Exception {
        SseEmitter broken = failingEmitter();
        SseEmitter healthy = mock(SseEmitter.class);
        CopyOnWriteArrayList<SseEmitter> list = new CopyOnWriteArrayList<>(List.of(broken, healthy));
        emitters().put(PROJECT_ID, list);
        ScheduledFuture<?> heartbeat = mock(ScheduledFuture.class);
        heartbeats().put(broken, heartbeat);

        controller.onScanProgress(event(7L, "PARSING"));

        verify(broken).completeWithError(any(IOException.class));
        verify(heartbeat).cancel(false);
        assertTrue(heartbeats().isEmpty());
        assertEquals(List.of(healthy), emitters().get(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — 全部订阅者失败后项目订阅被清空")
    void onScanProgressRemovesProjectWhenAllSubscribersFail() throws Exception {
        SseEmitter broken = failingEmitter();
        emitters().put(PROJECT_ID, new CopyOnWriteArrayList<>(List.of(broken)));

        controller.onScanProgress(event(7L, "PARSING"));

        verify(broken).completeWithError(any(IOException.class));
        assertFalse(emitters().containsKey(PROJECT_ID));
    }

    @Test
    @DisplayName("onScanProgress — JSON 序列化异常被外层捕获，快照仍已记录")
    void onScanProgressSwallowsSerializationFailure() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        when(failingMapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("boom") {
        });
        ScanProgressController failingController = new ScanProgressController(failingMapper, projectAccessChecker);
        SseEmitter emitter = mock(SseEmitter.class);
        @SuppressWarnings("unchecked")
        Map<Long, CopyOnWriteArrayList<SseEmitter>> map =
                (Map<Long, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(failingController, "emitters");
        map.put(PROJECT_ID, new CopyOnWriteArrayList<>(List.of(emitter)));
        ScanProgressEvent event = event(7L, "PARSING");

        failingController.onScanProgress(event);

        @SuppressWarnings("unchecked")
        Map<Long, ScanProgressEvent> snapshots =
                (Map<Long, ScanProgressEvent>) ReflectionTestUtils.getField(failingController, "latestEvents");
        assertSame(event, snapshots.get(PROJECT_ID));
        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(emitter, never()).complete();
    }

    @Test
    @DisplayName("streamProgress — 心跳发送失败时订阅者被摘除")
    void heartbeatFailureRemovesEmitter() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ScanProgressController heartbeatController = new ScanProgressController(mapper, projectAccessChecker);
        SseEmitter emitter = heartbeatController.streamProgress(PROJECT_ID, null, principal);
        ScheduledFuture<?> heartbeat = heartbeatsOf(heartbeatController).get(emitter);
        assertNotNull(heartbeat);

        forceHandlerSendFailure(emitter);

        runHeartbeatOnce(heartbeat);

        assertTrue(heartbeatsOf(heartbeatController).isEmpty());
        assertFalse(emittersOf(heartbeatController).containsKey(PROJECT_ID));
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, CopyOnWriteArrayList<SseEmitter>> emittersOf(ScanProgressController c) {
        return (Map<Long, CopyOnWriteArrayList<SseEmitter>>) ReflectionTestUtils.getField(c, "emitters");
    }

    @SuppressWarnings("unchecked")
    private static Map<SseEmitter, ScheduledFuture<?>> heartbeatsOf(ScanProgressController c) {
        return (Map<SseEmitter, ScheduledFuture<?>>) ReflectionTestUtils.getField(c, "heartbeats");
    }

    /**
     * 通过反射给 emitter 装上"发送即抛 IOException"的 Handler，模拟客户端断开后
     * 心跳/推送写入失败的真实分支（未初始化 emitter 的 send 只会缓冲，不抛异常）。
     */
    private static void forceHandlerSendFailure(SseEmitter emitter) throws Exception {
        Class<?> handlerType = Class.forName(
                "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter$Handler");
        Object handler = Proxy.newProxyInstance(ScanProgressControllerTest.class.getClassLoader(),
                new Class<?>[]{handlerType},
                (proxy, method, args) -> {
                    if ("send".equals(method.getName())) {
                        throw new IOException("client gone");
                    }
                    return null;
                });
        // 先清空缓冲的事件，避免 initialize 回放缓冲时提前触发发送失败
        Set<?> earlyAttempts = (Set<?>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        earlyAttempts.clear();
        Method initialize = ResponseBodyEmitter.class.getDeclaredMethod("initialize", handlerType);
        initialize.setAccessible(true);
        initialize.invoke(emitter, handler);
    }

    /** 从 SseEventBuilder 取出拼接后的原始发送文本 */
    private static String payloadOf(SseEmitter.SseEventBuilder builder) {
        StringBuilder sb = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType entry : builder.build()) {
            sb.append(entry.getData());
        }
        return sb.toString();
    }
}
