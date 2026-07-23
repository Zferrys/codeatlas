package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.mapper.AuditLogMapper;
import com.codeatlas.server.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理后台")
public class AdminController {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;

    public AdminController(UserMapper userMapper, AuditLogMapper auditLogMapper) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @GetMapping("/users")
    @Operation(summary = "用户列表")
    public ApiResponse<PageResult<Map<String, Object>>> listUsers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        long total = userMapper.countAll();
        List<Map<String, Object>> records = new ArrayList<>();
        userMapper.findAllPaged(offset, size).forEach(u -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", u.getId());
            item.put("username", u.getUsername());
            item.put("email", u.getEmail());
            item.put("role", u.getRole());
            item.put("status", u.getStatus());
            item.put("createdAt", u.getCreatedAt());
            item.put("lastLoginAt", u.getLastLoginAt());
            records.add(item);
        });
        return ApiResponse.success(new PageResult<>(records, total, page, size));
    }

    @PutMapping("/users/{id}/role")
    @Operation(summary = "修改用户角色")
    public ApiResponse<Void> updateRole(@PathVariable Long id,
                                         @RequestParam String role) {
        Set<String> validRoles = Set.of("ADMIN", "ARCHITECT", "DEVELOPER", "VIEWER");
        if (!validRoles.contains(role)) {
            return ApiResponse.error(400, "无效角色: " + role + "，可选: " + String.join(",", validRoles));
        }
        userMapper.updateRole(id, role);
        return ApiResponse.success(null);
    }

    @PutMapping("/users/{id}/status")
    @Operation(summary = "修改用户状态")
    public ApiResponse<Void> updateStatus(@PathVariable Long id,
                                           @RequestParam int status) {
        if (status != 0 && status != 1) {
            return ApiResponse.error(400, "状态值只能为 0(禁用) 或 1(正常)");
        }
        userMapper.updateStatus(id, status);
        return ApiResponse.success(null);
    }

    @GetMapping("/audit-log")
    @Operation(summary = "审计日志列表")
    public ApiResponse<PageResult<Map<String, Object>>> auditLog(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int offset = (page - 1) * size;
        long total = auditLogMapper.countAll();
        List<Map<String, Object>> records = new ArrayList<>();
        auditLogMapper.findPaged(offset, size).forEach(log -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", log.getId());
            item.put("userId", log.getUserId());
            item.put("username", log.getUsername());
            item.put("action", log.getAction());
            item.put("targetType", log.getTargetType());
            item.put("targetId", log.getTargetId());
            item.put("detail", log.getDetail());
            item.put("ipAddress", log.getIpAddress());
            item.put("createdAt", log.getCreatedAt());
            records.add(item);
        });
        return ApiResponse.success(new PageResult<>(records, total, page, size));
    }
}
