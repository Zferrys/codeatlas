package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.mapper.ChatHistoryMapper;
import com.codeatlas.server.service.ChatHistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ChatHistoryServiceImpl implements ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryServiceImpl.class);
    private static final int MAX_QUESTION_LENGTH = 2000;
    private static final Set<String> VALID_SCOPES =
            new HashSet<>(Arrays.asList(SCOPE_GLOBAL, SCOPE_CLASS, SCOPE_IMPACT));

    private final ChatHistoryMapper chatHistoryMapper;

    public ChatHistoryServiceImpl(ChatHistoryMapper chatHistoryMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
    }

    @Override
    @Transactional
    public void record(ChatHistoryEntity entity) {
        if (entity == null || entity.getProjectId() == null || entity.getUserId() == null
                || !VALID_SCOPES.contains(entity.getScope())
                || entity.getQuestion() == null || entity.getQuestion().trim().isEmpty()) {
            log.warn("Chat history record skipped: invalid params (scope={})",
                    entity != null ? entity.getScope() : null);
            return;
        }
        entity.setQuestion(truncate(entity.getQuestion().trim(), MAX_QUESTION_LENGTH));
        chatHistoryMapper.insert(entity);
        chatHistoryMapper.pruneBeyondLatest(entity.getUserId(), entity.getProjectId(),
                entity.getScope(), MAX_HISTORY_PER_SCOPE);
    }

    @Override
    public List<ChatHistoryEntity> list(Long projectId, Long userId, String scope) {
        if (!VALID_SCOPES.contains(scope)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的对话类型: " + scope);
        }
        return chatHistoryMapper.findByUserAndScope(projectId, userId, scope, MAX_HISTORY_PER_SCOPE);
    }

    @Override
    @Transactional
    public void delete(Long id, Long userId) {
        int affected = chatHistoryMapper.deleteByIdAndUser(id, userId);
        if (affected == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "历史记录不存在或无权删除");
        }
    }

    @Override
    @Transactional
    public void deleteByProjectId(Long projectId) {
        chatHistoryMapper.deleteByProjectId(projectId);
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
