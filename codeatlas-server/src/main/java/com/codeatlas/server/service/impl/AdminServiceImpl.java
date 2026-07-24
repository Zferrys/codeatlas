package com.codeatlas.server.service.impl;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.mapper.AuditLogMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.service.AdminService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AdminServiceImpl implements AdminService {

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;

    public AdminServiceImpl(UserMapper userMapper, AuditLogMapper auditLogMapper) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
    }

    @Override
    public PageResult<Map<String, Object>> listUsers(int page, int size) {
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
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public void updateRole(Long id, String role) {
        Set<String> validRoles = Set.of("ADMIN", "ARCHITECT", "DEVELOPER", "VIEWER");
        if (!validRoles.contains(role)) {
            throw new IllegalArgumentException("无效角色: " + role + "，可选: " + String.join(",", validRoles));
        }
        userMapper.updateRole(id, role);
    }

    @Override
    public void updateStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new IllegalArgumentException("状态值只能为 0(禁用) 或 1(正常)");
        }
        userMapper.updateStatus(id, status);
    }

    @Override
    public PageResult<Map<String, Object>> auditLog(int page, int size) {
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
        return new PageResult<>(records, total, page, size);
    }
}
