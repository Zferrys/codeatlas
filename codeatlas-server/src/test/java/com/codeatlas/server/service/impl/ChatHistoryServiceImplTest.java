package com.codeatlas.server.service.impl;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.mapper.ChatHistoryMapper;
import com.codeatlas.server.service.ChatHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryServiceImpl 单元测试")
class ChatHistoryServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final String SCOPE = ChatHistoryService.SCOPE_CLASS;

    @Mock
    private ChatHistoryMapper chatHistoryMapper;

    private ChatHistoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ChatHistoryServiceImpl(chatHistoryMapper);
    }

    private ChatHistoryEntity validEntity() {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setProjectId(PROJECT_ID);
        entity.setUserId(USER_ID);
        entity.setScope(SCOPE);
        entity.setQuestion("这个类的作用是什么？");
        entity.setAnswer("负责编排扫描流程。");
        return entity;
    }

    @Test
    @DisplayName("有效记录：问题去空格入库并按保留上限裁剪")
    void record_valid_shouldInsertAndPrune() {
        ChatHistoryEntity entity = validEntity();
        entity.setQuestion("  这个类的作用是什么？  ");

        service.record(entity);

        assertEquals("这个类的作用是什么？", entity.getQuestion());
        verify(chatHistoryMapper).insert(entity);
        verify(chatHistoryMapper).pruneBeyondLatest(USER_ID, PROJECT_ID, SCOPE,
                ChatHistoryService.MAX_HISTORY_PER_SCOPE);
    }

    @Test
    @DisplayName("超长问题截断为 2000 字符")
    void record_longQuestion_shouldTruncateTo2000() {
        ChatHistoryEntity entity = validEntity();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2500; i++) {
            sb.append('q');
        }
        entity.setQuestion(sb.toString());

        service.record(entity);

        assertEquals(2000, entity.getQuestion().length());
        verify(chatHistoryMapper).insert(entity);
    }

    @Test
    @DisplayName("scope 非法时静默跳过，不落库")
    void record_invalidScope_shouldSkip() {
        ChatHistoryEntity entity = validEntity();
        entity.setScope("UNKNOWN");

        service.record(entity);

        verifyNoInteractions(chatHistoryMapper);
    }

    @Test
    @DisplayName("问题为空白时静默跳过，不落库")
    void record_blankQuestion_shouldSkip() {
        ChatHistoryEntity entity = validEntity();
        entity.setQuestion("   ");

        service.record(entity);

        verifyNoInteractions(chatHistoryMapper);
    }

    @Test
    @DisplayName("list 非法 scope 抛 400")
    void list_invalidScope_shouldThrow400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.list(PROJECT_ID, USER_ID, "UNKNOWN"));

        assertEquals(400, ex.getCode());
    }

    @Test
    @DisplayName("list 合法 scope 透传查询参数与保留上限")
    void list_validScope_shouldDelegate() {
        ChatHistoryEntity entity = validEntity();
        when(chatHistoryMapper.findByUserAndScope(PROJECT_ID, USER_ID, SCOPE,
                ChatHistoryService.MAX_HISTORY_PER_SCOPE))
                .thenReturn(Collections.singletonList(entity));

        List<ChatHistoryEntity> result = service.list(PROJECT_ID, USER_ID, SCOPE);

        assertEquals(1, result.size());
        assertEquals(entity, result.get(0));
    }

    @Test
    @DisplayName("delete 未命中记录抛 404")
    void delete_notFound_shouldThrow404() {
        when(chatHistoryMapper.deleteByIdAndUser(5L, USER_ID)).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(5L, USER_ID));

        assertEquals(404, ex.getCode());
    }

    @Test
    @DisplayName("delete 命中记录正常返回")
    void delete_found_shouldSucceed() {
        when(chatHistoryMapper.deleteByIdAndUser(5L, USER_ID)).thenReturn(1);

        service.delete(5L, USER_ID);

        verify(chatHistoryMapper).deleteByIdAndUser(5L, USER_ID);
    }

    @Test
    @DisplayName("deleteByProjectId 级联清理透传")
    void deleteByProjectId_shouldDelegate() {
        service.deleteByProjectId(PROJECT_ID);

        verify(chatHistoryMapper).deleteByProjectId(PROJECT_ID);
    }
}
