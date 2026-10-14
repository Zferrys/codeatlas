package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.MapViewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ViewController 单元测试")
class ViewControllerTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long USER_ID = 12L;

    @Mock
    private MapViewService mapViewService;

    private ViewController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ViewController(mapViewService);
        principal = new CodeAtlasUserDetails(USER_ID, "grace", "ARCHITECT");
    }

    @Test
    @DisplayName("saveView — 显式 name/isPublic/viewState 全部透传")
    void saveViewPassesAllFields() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "我的视图");
        body.put("isPublic", true);
        body.put("viewState", "{\"zoom\":2}");
        Map<String, Object> saved = Map.of("id", 1L);
        when(mapViewService.saveView(PROJECT_ID, USER_ID, "我的视图", true, "{\"zoom\":2}")).thenReturn(saved);

        ApiResponse<Map<String, Object>> response = controller.saveView(PROJECT_ID, body, principal);

        assertEquals(200, response.getCode());
        assertEquals(saved, response.getData());
        verify(mapViewService).saveView(PROJECT_ID, USER_ID, "我的视图", true, "{\"zoom\":2}");
    }

    @Test
    @DisplayName("saveView — 缺省 name 使用「未命名视图」，isPublic 默认 false，viewState null 透传 null")
    void saveViewUsesDefaults() {
        Map<String, Object> body = new HashMap<>();
        when(mapViewService.saveView(PROJECT_ID, USER_ID, "未命名视图", false, null))
                .thenReturn(Map.of("ok", true));

        controller.saveView(PROJECT_ID, body, principal);

        verify(mapViewService).saveView(PROJECT_ID, USER_ID, "未命名视图", false, null);
    }

    @Test
    @DisplayName("saveView — isPublic 非 Boolean.TRUE 一律视为 false")
    void saveViewTreatsNonTrueAsFalse() {
        Map<String, Object> body = new HashMap<>();
        body.put("isPublic", "true");
        when(mapViewService.saveView(eq(PROJECT_ID), eq(USER_ID), any(), eq(false), isNull()))
                .thenReturn(Map.of());

        controller.saveView(PROJECT_ID, body, principal);

        verify(mapViewService).saveView(eq(PROJECT_ID), eq(USER_ID), any(), eq(false), isNull());
    }

    @Test
    @DisplayName("saveView — viewState 为非字符串对象时 String.valueOf 序列化")
    void saveViewStringifiesViewStateObject() {
        Map<String, Object> body = new HashMap<>();
        body.put("viewState", Map.of("zoom", 3));
        when(mapViewService.saveView(eq(PROJECT_ID), eq(USER_ID), any(), anyBoolean(), any()))
                .thenReturn(Map.of());

        controller.saveView(PROJECT_ID, body, principal);

        verify(mapViewService).saveView(eq(PROJECT_ID), eq(USER_ID), any(), eq(false), eq("{zoom=3}"));
    }

    @Test
    @DisplayName("listViews — 透传视图列表")
    void listViewsPassesThrough() {
        List<Map<String, Object>> views = List.of(Map.of("id", 5L), Map.of("id", 6L));
        when(mapViewService.listViews(PROJECT_ID, USER_ID)).thenReturn(views);

        ApiResponse<List<Map<String, Object>>> response = controller.listViews(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(views, response.getData());
        verify(mapViewService).listViews(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("deleteView — 按 viewId 与用户 id 删除，返回空数据")
    void deleteViewInvokesService() {
        ApiResponse<Void> response = controller.deleteView(PROJECT_ID, 33L, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(mapViewService).deleteView(33L, USER_ID);
    }
}
