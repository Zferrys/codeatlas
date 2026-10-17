package com.codeatlas.server.service.impl;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.dto.AiUsageQuery;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import com.codeatlas.server.mapper.AuditLogMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.service.AdminService;
import com.codeatlas.server.service.CleanupService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AdminServiceImpl implements AdminService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_PAGE_SIZE = 100;

    private final UserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final AiAuditLogMapper aiAuditLogMapper;
    private final CleanupService cleanupService;
    private final TokenBudgetManager tokenBudgetManager;

    public AdminServiceImpl(UserMapper userMapper, AuditLogMapper auditLogMapper,
                            AiAuditLogMapper aiAuditLogMapper, CleanupService cleanupService,
                            TokenBudgetManager tokenBudgetManager) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.aiAuditLogMapper = aiAuditLogMapper;
        this.cleanupService = cleanupService;
        this.tokenBudgetManager = tokenBudgetManager;
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
            throw new BusinessException(400, "无效角色: " + role + "，可选: " + String.join(",", validRoles));
        }
        userMapper.updateRole(id, role);
    }

    @Override
    public void updateStatus(Long id, int status) {
        if (status != 0 && status != 1) {
            throw new BusinessException(400, "状态值只能为 0(禁用) 或 1(正常)");
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

    @Override
    public PageResult<Map<String, Object>> aiUsage(AiUsageQuery query) {
        normalize(query);
        long total = aiAuditLogMapper.countUsage(query);
        long offsetLong = (long) (query.getPage() - 1) * query.getSize();
        if (offsetLong >= total) {
            // 超出末页直接返回空页，避免深分页把大 offset 的 LIMIT 下发给数据库
            return new PageResult<>(Collections.emptyList(), total, query.getPage(), query.getSize());
        }
        List<Map<String, Object>> records = aiAuditLogMapper.findUsagePaged(query, (int) offsetLong);
        return new PageResult<>(records, total, query.getPage(), query.getSize());
    }

    @Override
    public Map<String, Object> aiUsageSummary(AiUsageQuery query) {
        normalize(query);
        // 未指定区间时默认统计本自然月（容器时区为北京时间，见 Dockerfile TZ）
        if (query.getStartTime() == null || query.getStartTime().isEmpty()) {
            query.setStartTime(LocalDate.now().withDayOfMonth(1).atStartOfDay().format(TIME_FMT));
        }
        if (query.getEndTime() == null || query.getEndTime().isEmpty()) {
            query.setEndTime(LocalDate.now().withDayOfMonth(1).plusMonths(1).atStartOfDay().format(TIME_FMT));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> totals = aiAuditLogMapper.usageSummary(query);
        summary.put("totals", totals != null ? totals : Collections.emptyMap());
        summary.put("byModel", aiAuditLogMapper.usageByModel(query));
        summary.put("byStage", aiAuditLogMapper.usageByStage(query));
        summary.put("byUser", aiAuditLogMapper.usageByUser(query));

        long[] budget = tokenBudgetManager.getBudgetStatus();
        Map<String, Object> budgetInfo = new LinkedHashMap<>();
        budgetInfo.put("usedTokens", budget[0]);
        budgetInfo.put("monthlyLimit", budget[1]);
        budgetInfo.put("usagePercent", budget[1] > 0
                ? Math.round(budget[0] * 10000.0 / budget[1]) / 100.0 : 0.0);
        summary.put("budget", budgetInfo);
        return summary;
    }

    private void normalize(AiUsageQuery query) {
        if (query.getPage() < 1) {
            query.setPage(1);
        }
        if (query.getSize() < 1) {
            query.setSize(20);
        } else if (query.getSize() > MAX_PAGE_SIZE) {
            query.setSize(MAX_PAGE_SIZE);
        }
    }

    @Override
    public Map<String, Object> getSystemStatus() {
        return cleanupService.getCleanupStatus();
    }
}
