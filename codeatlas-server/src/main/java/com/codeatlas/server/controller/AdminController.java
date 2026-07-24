package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理后台")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    @Operation(summary = "用户列表")
    public ApiResponse<PageResult<Map<String, Object>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminService.listUsers(page, size));
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "修改用户角色")
    public ApiResponse<Void> updateRole(@PathVariable Long id,
                                         @RequestParam String role) {
        try {
            adminService.updateRole(id, role);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @PutMapping("/users/{id}/status")
    @Operation(summary = "修改用户状态")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                           @RequestParam int status) {
        try {
            adminService.updateStatus(id, status);
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
    }

    @GetMapping("/audit-log")
    @Operation(summary = "审计日志列表")
    public ApiResponse<PageResult<Map<String, Object>>> auditLog(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(adminService.auditLog(page, size));
    }
}
