package com.codeatlas.server.service;

import com.codeatlas.server.entity.ChatHistoryEntity;

import java.util.List;

/** 对话历史：服务端每人每项目每类型仅保留最近 10 条（写入时自动裁剪）。 */
public interface ChatHistoryService {

    String SCOPE_GLOBAL = "GLOBAL";
    String SCOPE_CLASS = "CLASS";
    String SCOPE_IMPACT = "IMPACT";
    int MAX_HISTORY_PER_SCOPE = 10;

    /** 写入一条 AI 问答记录并裁剪超出的历史；仅记录真实 AI 回答（降级/失败路径不应调用）。 */
    void record(ChatHistoryEntity entity);

    /** 查询当前用户在某项目某类型下的最近对话（新→旧）。 */
    List<ChatHistoryEntity> list(Long projectId, Long userId, String scope);

    /** 删除本人一条历史记录。 */
    void delete(Long id, Long userId);

    /** 项目删除时级联清理。 */
    void deleteByProjectId(Long projectId);
}
