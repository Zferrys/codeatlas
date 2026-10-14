package com.codeatlas.engine.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StreamCallback — 流式回调接口默认方法")
class StreamCallbackTest {

    @Test
    @DisplayName("默认 onComplete/onError 为空实现：只实现 onToken 也可安全调用")
    void defaultMethods_shouldBeNoOp() {
        List<String> tokens = new ArrayList<>();
        StreamCallback callback = tokens::add;

        callback.onToken("a");
        callback.onToken("b");
        callback.onComplete();
        callback.onError(new RuntimeException("boom"));

        assertEquals(List.of("a", "b"), tokens);
    }

    @Test
    @DisplayName("匿名类覆写三个方法：回调顺序与参数原样传递")
    void overriddenCallbacks_shouldReceiveEventsInOrder() {
        List<String> events = new ArrayList<>();
        RuntimeException failure = new RuntimeException("stream broken");

        StreamCallback callback = new StreamCallback() {
            @Override
            public void onToken(String token) {
                events.add("token:" + token);
            }

            @Override
            public void onComplete() {
                events.add("complete");
            }

            @Override
            public void onError(Throwable error) {
                assertSame(failure, error);
                events.add("error:" + error.getMessage());
            }
        };

        callback.onToken("你");
        callback.onToken("好");
        callback.onComplete();

        assertEquals(List.of("token:你", "token:好", "complete"), events);

        callback.onError(failure);
        assertEquals("error:stream broken", events.get(3));
    }

    @Test
    @DisplayName("是函数式接口：lambda 直接赋值可用")
    void isFunctionalInterface() {
        List<String> tokens = new ArrayList<>();
        StreamCallback callback = token -> tokens.add(token.toUpperCase());

        callback.onToken("ok");

        assertEquals(1, tokens.size());
        assertEquals("OK", tokens.get(0));
        assertTrue(StreamCallback.class.isInterface());
    }
}
