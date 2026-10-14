package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ScanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanController 单元测试")
class ScanControllerTest {

    private static final Long PROJECT_ID = 40L;
    private static final Long USER_ID = 50L;

    @Mock
    private ScanService scanService;

    private ScanController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ScanController(scanService);
        principal = new CodeAtlasUserDetails(USER_ID, "jack", "DEVELOPER");
    }

    private static ScanVO scanVO(Long id) {
        ScanVO vo = new ScanVO();
        vo.setId(id);
        vo.setProjectId(PROJECT_ID);
        return vo;
    }

    @Test
    @DisplayName("triggerScan — 项目与用户 id 透传")
    void triggerScanPassesArgs() {
        ScanVO vo = scanVO(1L);
        when(scanService.triggerScan(PROJECT_ID, USER_ID)).thenReturn(vo);

        ApiResponse<ScanVO> response = controller.triggerScan(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertSame(vo, response.getData());
        verify(scanService).triggerScan(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getScanHistory — 分页参数与用户 id 精确透传")
    void getScanHistoryPassesPaging() {
        PageResult<ScanVO> page = new PageResult<>(List.of(scanVO(2L)), 3L, 1, 15);
        when(scanService.getScanHistory(PROJECT_ID, USER_ID, 1, 15)).thenReturn(page);

        ApiResponse<PageResult<ScanVO>> response = controller.getScanHistory(PROJECT_ID, principal, 1, 15);

        assertSame(page, response.getData());
        assertEquals(3L, response.getData().getTotal());
        verify(scanService).getScanHistory(PROJECT_ID, USER_ID, 1, 15);
    }

    @Test
    @DisplayName("getLatestScan — 最新扫描透传")
    void getLatestScanPassesArgs() {
        ScanVO vo = scanVO(9L);
        when(scanService.getLatestScan(PROJECT_ID, USER_ID)).thenReturn(vo);

        ApiResponse<ScanVO> response = controller.getLatestScan(PROJECT_ID, principal);

        assertEquals(9L, response.getData().getId());
        verify(scanService).getLatestScan(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("getScanStatus — 状态 Map 原样返回")
    void getScanStatusPassesThrough() {
        Map<String, Object> status = Map.of("status", "RUNNING", "aiAnalyzing", true);
        when(scanService.getScanStatus(PROJECT_ID, USER_ID)).thenReturn(status);

        ApiResponse<Map<String, Object>> response = controller.getScanStatus(PROJECT_ID, principal);

        assertEquals(status, response.getData());
        verify(scanService).getScanStatus(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("triggerIncrementalScan — 增量扫描透传")
    void triggerIncrementalScanPassesArgs() {
        ScanVO vo = scanVO(10L);
        when(scanService.triggerIncrementalScan(PROJECT_ID, USER_ID)).thenReturn(vo);

        ApiResponse<ScanVO> response = controller.triggerIncrementalScan(PROJECT_ID, principal);

        assertEquals(10L, response.getData().getId());
        verify(scanService).triggerIncrementalScan(PROJECT_ID, USER_ID);
    }
}
