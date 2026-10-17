package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.AiUsageQuery;
import com.codeatlas.server.service.AdminService;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminController 单元测试")
class AdminControllerTest {

    @Mock
    private AdminService adminService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService);
    }

    @Test
    @DisplayName("listUsers — 分页参数精确透传")
    void listUsersPassesPaging() {
        PageResult<Map<String, Object>> page = new PageResult<>(List.of(Map.of("id", 1L)), 6L, 2, 5);
        when(adminService.listUsers(2, 5)).thenReturn(page);

        ApiResponse<PageResult<Map<String, Object>>> response = controller.listUsers(2, 5);

        assertEquals(200, response.getCode());
        assertSame(page, response.getData());
        assertEquals(6L, response.getData().getTotal());
        verify(adminService).listUsers(2, 5);
    }

    @Test
    @DisplayName("updateRole — 用户 id 与角色透传，返回空数据")
    void updateRolePassesArgs() {
        ApiResponse<Void> response = controller.updateRole(9L, "ADMIN");

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(adminService).updateRole(9L, "ADMIN");
    }

    @Test
    @DisplayName("updateStatus — 用户 id 与状态透传，返回空数据")
    void updateStatusPassesArgs() {
        ApiResponse<Void> response = controller.updateStatus(9L, 0);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
        verify(adminService).updateStatus(9L, 0);
    }

    @Test
    @DisplayName("auditLog — 分页审计日志透传")
    void auditLogPassesPaging() {
        PageResult<Map<String, Object>> page = new PageResult<>(List.of(), 0L, 1, 20);
        when(adminService.auditLog(1, 20)).thenReturn(page);

        ApiResponse<PageResult<Map<String, Object>>> response = controller.auditLog(1, 20);

        assertSame(page, response.getData());
        verify(adminService).auditLog(1, 20);
    }

    @Test
    @DisplayName("aiUsage — 查询条件透传到服务层")
    void aiUsagePassesQuery() {
        AiUsageQuery query = new AiUsageQuery();
        query.setModel("deepseek-v4-pro");
        PageResult<Map<String, Object>> page = new PageResult<>(List.of(), 0L, 1, 20);
        when(adminService.aiUsage(query)).thenReturn(page);

        ApiResponse<PageResult<Map<String, Object>>> response = controller.aiUsage(query);

        assertEquals(200, response.getCode());
        assertSame(page, response.getData());
        verify(adminService).aiUsage(query);
    }

    @Test
    @DisplayName("aiUsageSummary — 汇总结果原样返回")
    void aiUsageSummaryPassesThrough() {
        AiUsageQuery query = new AiUsageQuery();
        Map<String, Object> summary = Map.of("totals", Map.of("calls", 1L));
        when(adminService.aiUsageSummary(query)).thenReturn(summary);

        ApiResponse<Map<String, Object>> response = controller.aiUsageSummary(query);

        assertEquals(200, response.getCode());
        assertSame(summary, response.getData());
        verify(adminService).aiUsageSummary(query);
    }

    @Test
    @DisplayName("systemStatus — 系统状态原样返回")
    void systemStatusPassesThrough() {
        Map<String, Object> status = Map.of("disk", "ok", "memory", "ok");
        when(adminService.getSystemStatus()).thenReturn(status);

        ApiResponse<Map<String, Object>> response = controller.systemStatus();

        assertEquals(200, response.getCode());
        assertEquals(status, response.getData());
        verify(adminService).getSystemStatus();
    }
}
