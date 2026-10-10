package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;

import java.util.List;
import java.util.Map;

/**
 * AI 模型管理服务 — 可用模型清单、当前模型切换（持久化）、连通性测试。
 */
public interface AiModelService {

    /** 当前选中的模型 ID */
    String getCurrentModelId();

    /** 是否至少有一个可用模型（存在已配置的 API Key） */
    boolean hasAnyModel();

    /** 管理页模型列表：可用模型清单 + 当前选中 + 清单来源(live/fallback) */
    Map<String, Object> listModels();

    /** 切换当前模型（校验可用性并持久化到 system_config） */
    void switchModel(String modelId);

    /** 连通性测试：向指定模型发送一条极短请求，不切换当前模型 */
    Map<String, Object> testModel(String modelId);

    /** 调用链：当前模型优先，其后为原有降级顺序（供 AiModelRouter 使用） */
    List<AiClient> resolveChain();
}
