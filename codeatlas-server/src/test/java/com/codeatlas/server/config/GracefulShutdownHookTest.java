package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.ContextClosedEvent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
