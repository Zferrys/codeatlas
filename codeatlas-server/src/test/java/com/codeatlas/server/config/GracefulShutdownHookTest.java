package com.codeatlas.server.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("GracefulShutdownHook 优雅关闭钩子测试")
class GracefulShutdownHookTest {

    @Test
    @DisplayName("destroy 执行自定义清理逻辑且不抛异常")
    void destroyShouldNotThrow() {
        GracefulShutdownHook hook = new GracefulShutdownHook();

        assertDoesNotThrow(hook::destroy);
    }

    @Test
    @DisplayName("收到 ContextClosedEvent 时触发清理且不抛异常")
    void onApplicationEventShouldTriggerDestroy() {
        GracefulShutdownHook hook = new GracefulShutdownHook();
        ContextClosedEvent event = mock(ContextClosedEvent.class);

        assertDoesNotThrow(() -> hook.onApplicationEvent(event));
    }

    @Test
    @DisplayName("onApplicationEvent 完整透传 destroy——三行关闭日志序列全部可观测")
    void onApplicationEvent_shouldInvokeDestroyLogSequence() {
        GracefulShutdownHook hook = new GracefulShutdownHook();
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            hook.onApplicationEvent(mock(ContextClosedEvent.class));

            assertTrue(appender.list.stream().anyMatch(e ->
                            "Context closing — initiating graceful shutdown...".equals(e.getFormattedMessage())),
                    "onApplicationEvent 入口日志缺失");
            assertTrue(appender.list.stream().anyMatch(e ->
                            "CodeAtlas shutting down — flushing logs and releasing resources...".equals(e.getFormattedMessage())),
                    "destroy 首行日志缺失——onApplicationEvent 未透传调用 destroy");
            assertTrue(appender.list.stream().anyMatch(e ->
                            "CodeAtlas shutdown complete.".equals(e.getFormattedMessage())),
                    "destroy 完成日志缺失——onApplicationEvent 未完整调用 destroy");
        } finally {
            detachLogAppender(appender);
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GracefulShutdownHook.class))
                .addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GracefulShutdownHook.class))
                .detachAppender(appender);
    }
}
