package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ClassService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClassController 单元测试")
class ClassControllerTest {

    private static final Long PROJECT_ID = 60L;
    private static final Long USER_ID = 70L;

    @Mock
    private ClassService classService;

    private ClassController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ClassController(classService);
        principal = new CodeAtlasUserDetails(USER_ID, "kate", "VIEWER");
    }

    @Test
    @DisplayName("getClassDetail — 命中类详情原样返回")
    void getClassDetailFound() {
        Map<String, Object> detail = Map.of("fqn", "com.a.B", "methods", 5);
        when(classService.getClassDetail(PROJECT_ID, "com.a.B", USER_ID)).thenReturn(detail);

        ApiResponse<Map<String, Object>> response = controller.getClassDetail(PROJECT_ID, "com.a.B", principal);

        assertEquals(200, response.getCode());
        assertEquals(detail, response.getData());
        verify(classService).getClassDetail(PROJECT_ID, "com.a.B", USER_ID);
    }

    @Test
    @DisplayName("getClassDetail — 类不存在返回 404 与精确文案")
    void getClassDetailNotFound() {
        when(classService.getClassDetail(PROJECT_ID, "com.missing.X", USER_ID)).thenReturn(null);

        ApiResponse<Map<String, Object>> response = controller.getClassDetail(PROJECT_ID, "com.missing.X", principal);

        assertEquals(404, response.getCode());
        assertEquals("类不存在: com.missing.X", response.getMessage());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("searchClasses — 关键字与 limit 精确透传")
    void searchClassesPassesArgs() {
        List<Map<String, Object>> results = List.of(Map.of("fqn", "com.a.B"));
        when(classService.searchClasses(PROJECT_ID, "B", 30, USER_ID)).thenReturn(results);

        ApiResponse<List<Map<String, Object>>> response = controller.searchClasses(PROJECT_ID, "B", 30, principal);

        assertEquals(200, response.getCode());
        assertEquals(results, response.getData());
        verify(classService).searchClasses(PROJECT_ID, "B", 30, USER_ID);
    }

    @Test
    @DisplayName("searchClasses — 空结果返回空列表")
    void searchClassesEmptyResult() {
        when(classService.searchClasses(PROJECT_ID, "zzz", 20, USER_ID)).thenReturn(List.of());

        ApiResponse<List<Map<String, Object>>> response = controller.searchClasses(PROJECT_ID, "zzz", 20, principal);

        assertEquals(0, response.getData().size());
    }
}
