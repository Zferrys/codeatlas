package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ChatHistoryService;
import com.codeatlas.server.service.ProjectAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatHistoryController 单元测试")
class ChatHistoryControllerTest {

    private static final Long PROJECT_ID = 2L;
    private static final Long USER_ID = 4L;

    @Mock
    private ChatHistoryService chatHistoryService;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private ChatHistoryController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ChatHistoryController(chatHistoryService, projectAccessChecker);
        principal = new CodeAtlasUserDetails(USER_ID, "dave", "DEVELOPER");
    }

    private static ChatHistoryEntity record(Long id, String scope, LocalDateTime createdAt) {
        ChatHistoryEntity entity = new ChatHistoryEntity();
        entity.setId(id);
        entity.setProjectId(PROJECT_ID);
        entity.setUserId(USER_ID);
        entity.setScope(scope);
        entity.setContextFqn("com.a.B");
        entity.setQuestion("问题 " + id);
        entity.setAnswer("回答 " + id);
        entity.setVerification("{\"ok\":true}");
        entity.setCreatedAt(createdAt);
        return entity;
    }

    @Test
    @DisplayName("list — 逐条映射为响应字段并保留顺序与精确值")
    void listMapsRecordsInOrder() {
        when(chatHistoryService.list(PROJECT_ID, USER_ID, "CLASS"))
                .thenReturn(List.of(
                        record(2L, "CLASS", LocalDateTime.of(2024, 2, 3, 4, 5, 6)),
                        record(1L, "CLASS", null)));

        ApiResponse<List<Map<String, Object>>> response = controller.list(PROJECT_ID, "CLASS", principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        List<Map<String, Object>> items = response.getData();
        assertEquals(2, items.size());
        Map<String, Object> first = items.get(0);
        assertEquals(2L, first.get("id"));
        assertEquals("CLASS", first.get("scope"));
        assertEquals("com.a.B", first.get("contextFqn"));
        assertEquals("问题 2", first.get("question"));
        assertEquals("回答 2", first.get("answer"));
        assertEquals("{\"ok\":true}", first.get("verification"));
        assertEquals("2024-02-03T04:05:06", first.get("createdAt"));
        Map<String, Object> second = items.get(1);
        assertEquals(1L, second.get("id"));
        assertTrue(second.containsKey("createdAt"));
        assertNull(second.get("createdAt"));
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(chatHistoryService).list(PROJECT_ID, USER_ID, "CLASS");
    }

    @Test
    @DisplayName("list — 空历史返回空列表（非 null）")
    void listReturnsEmptyList() {
        when(chatHistoryService.list(PROJECT_ID, USER_ID, "GLOBAL")).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> response = controller.list(PROJECT_ID, "GLOBAL", principal);

        assertEquals(200, response.getCode());
        assertEquals(0, response.getData().size());
    }

    @Test
    @DisplayName("list — scope 原样透传（IMPACT）")
    void listPassesScopeThrough() {
        when(chatHistoryService.list(PROJECT_ID, USER_ID, "IMPACT")).thenReturn(List.of());

        controller.list(PROJECT_ID, "IMPACT", principal);

        verify(chatHistoryService).list(PROJECT_ID, USER_ID, "IMPACT");
    }

    @Test
    @DisplayName("delete — 校验访问后按 id+userId 删除，返回空数据")
    void deleteInvokesService() {
        ApiResponse<Void> response = controller.delete(PROJECT_ID, 99L, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(chatHistoryService).delete(99L, USER_ID);
    }
}
