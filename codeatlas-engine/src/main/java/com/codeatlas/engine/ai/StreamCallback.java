package com.codeatlas.engine.ai;

/**
 * 流式回调接口 — 用于 SSE 流式返回场景。
 */
@FunctionalInterface
public interface StreamCallback {

    /** 收到一个 token 片段 */
    void onToken(String token);

    /** 流正常结束 */
    default void onComplete() {}

    /** 流出错 */
    default void onError(Throwable error) {}
}
