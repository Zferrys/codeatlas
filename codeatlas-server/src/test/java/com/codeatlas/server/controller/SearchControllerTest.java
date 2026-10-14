package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.SearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SearchController 单元测试")
class SearchControllerTest {

    private static final Long USER_ID = 100L;

    @Mock
    private SearchService searchService;

    private SearchController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new SearchController(searchService);
        principal = new CodeAtlasUserDetails(USER_ID, "mona", "DEVELOPER");
    }

    @Test
    @DisplayName("search — 关键字/类型/当前用户 id 三者精确透传")
    void searchPassesAllArgs() {
        Map<String, Object> result = Map.of("projects", java.util.List.of(), "classes", java.util.List.of());
        when(searchService.search("订单", "class", USER_ID)).thenReturn(result);

        ApiResponse<Map<String, Object>> response = controller.search("订单", "class", principal);

        assertEquals(200, response.getCode());
        assertEquals(result, response.getData());
        verify(searchService).search("订单", "class", USER_ID);
    }

    @Test
    @DisplayName("search — type 缺省值 all 由路由层提供，直调时透传 all")
    void searchWithAllType() {
        when(searchService.search("q", "all", USER_ID)).thenReturn(Map.of("total", 0));

        ApiResponse<Map<String, Object>> response = controller.search("q", "all", principal);

        assertEquals(Map.of("total", 0), response.getData());
        verify(searchService).search("q", "all", USER_ID);
    }
}
