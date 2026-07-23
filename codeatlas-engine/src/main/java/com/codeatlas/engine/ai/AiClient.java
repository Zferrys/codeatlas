package com.codeatlas.engine.ai;

/**
 * 统一 AI 客户端接口 — SPI 扩展点。
 * 实现类通过 OkHttp 或其他 HTTP 客户端调用具体 AI 服务。
 */
public interface AiClient {

    /** 非流式调用 */
    AiResponse chat(AiRequest request);

    /** 流式调用（SSE），每收到一个 token 回调一次 */
    void chatStream(AiRequest request, StreamCallback callback);

    /** 获取当前模型名称 */
    String getModelName();

    /** 健康检查（验证 API key 是否有效） */
    boolean healthCheck();
}
