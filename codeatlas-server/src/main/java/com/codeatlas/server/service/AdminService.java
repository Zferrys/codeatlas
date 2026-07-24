package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;

import java.util.Map;

public interface AdminService {

    PageResult<Map<String, Object>> listUsers(int page, int size);

    void updateRole(Long id, String role);

    void updateStatus(Long id, int status);

    PageResult<Map<String, Object>> auditLog(int page, int size);
}
