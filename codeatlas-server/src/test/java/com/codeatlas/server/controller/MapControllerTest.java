package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.dto.response.GraphVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.MapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MapController 单元测试")
class MapControllerTest {

    private static final Long PROJECT_ID = 80L;
    private static final Long USER_ID = 90L;

    @Mock
    private MapService mapService;

    private MapController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new MapController(mapService);
        principal = new CodeAtlasUserDetails(USER_ID, "lily", "DEVELOPER");
    }

    private static GraphVO graph() {
        GraphVO vo = new GraphVO();
        vo.setTotalNodes(10L);
        return vo;
    }

    @Test
    @DisplayName("getProjectMap — 项目与用户 id 透传")
    void getProjectMapPassesArgs() {
        GraphVO vo = graph();
        when(mapService.getProjectMap(PROJECT_ID, USER_ID)).thenReturn(vo);

        ApiResponse<GraphVO> response = controller.getProjectMap(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertSame(vo, response.getData());
        assertEquals(10L, response.getData().getTotalNodes());
        verify(mapService).getProjectMap(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getProjectMapPaged — level/page/size 与用户 id 精确透传")
    void getProjectMapPagedPassesArgs() {
        GraphVO vo = graph();
        when(mapService.getProjectMapPaged(PROJECT_ID, "PACKAGE", 2, 100, USER_ID)).thenReturn(vo);

        ApiResponse<GraphVO> response = controller.getProjectMapPaged(PROJECT_ID, "PACKAGE", 2, 100, principal);

        assertSame(vo, response.getData());
        verify(mapService).getProjectMapPaged(PROJECT_ID, "PACKAGE", 2, 100, USER_ID);
    }

    @Test
    @DisplayName("getSubgraph — fqn 与 depth 透传")
    void getSubgraphPassesArgs() {
        GraphVO vo = graph();
        when(mapService.getSubgraph(PROJECT_ID, "com.a.B", 3, USER_ID)).thenReturn(vo);

        ApiResponse<GraphVO> response = controller.getSubgraph(PROJECT_ID, "com.a.B", 3, principal);

        assertSame(vo, response.getData());
        verify(mapService).getSubgraph(PROJECT_ID, "com.a.B", 3, USER_ID);
    }
}
