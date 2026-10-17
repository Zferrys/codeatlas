package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.AiUsageQuery;

import java.util.Map;

public interface AdminService {

    PageResult<Map<String, Object>> listUsers(int page, int size);

    void updateRole(Long id, String role);

    void updateStatus(Long id, int status);

    PageResult<Map<String, Object>> auditLog(int page, int size);

    /** AI Token 流水分页明细（支持模型/阶段/使用者/成功状态/时间范围筛选） */
    PageResult<Map<String, Object>> aiUsage(AiUsageQuery query);

    /** AI Token 用量汇总：区间合计、按模型/阶段/使用者分布、Redis 月预算状态 */
    Map<String, Object> aiUsageSummary(AiUsageQuery query);

    /** 获取系统状态（磁盘、内存、工作空间） */
    Map<String, Object> getSystemStatus();
}
