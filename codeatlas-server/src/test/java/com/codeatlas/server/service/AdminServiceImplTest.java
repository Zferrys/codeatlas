package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.dto.AiUsageQuery;
import com.codeatlas.server.entity.AuditLogEntity;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import com.codeatlas.server.mapper.AuditLogMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.service.impl.AdminServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminServiceImpl 单元测试")
class AdminServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private AuditLogMapper auditLogMapper;
    @Mock
    private AiAuditLogMapper aiAuditLogMapper;
    @Mock
    private CleanupService cleanupService;
    @Mock
    private TokenBudgetManager tokenBudgetManager;

    @InjectMocks
    private AdminServiceImpl adminService;

    private User user(Long id, String username) {
        User u = new User();
        u.setId(id);
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setRole("DEVELOPER");
        u.setStatus(1);
        u.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        u.setLastLoginAt(LocalDateTime.of(2026, 2, 3, 4, 5, 6));
        return u;
    }

    private AuditLogEntity auditLog(Long id) {
        AuditLogEntity log = new AuditLogEntity();
        log.setId(id);
        log.setUserId(7L);
        log.setUsername("alice");
        log.setAction("CREATE_PROJECT");
        log.setTargetType("PROJECT");
        log.setTargetId(100L);
        log.setDetail("detail-" + id);
        log.setIpAddress("10.0.0.1");
        log.setCreatedAt(LocalDateTime.of(2026, 3, 4, 5, 6, 7));
        return log;
    }

    @Test
    @DisplayName("用户列表：offset=(page-1)*size，逐条映射 7 个字段")
    void listUsers_shouldPageAndMapSevenFields() {
        when(userMapper.countAll()).thenReturn(12L);
        when(userMapper.findAllPaged(10, 10)).thenReturn(Arrays.asList(user(1L, "alice"), user(2L, "bob")));

        PageResult<Map<String, Object>> result = adminService.listUsers(2, 10);

        assertEquals(12L, result.getTotal());
        assertEquals(2, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(2, result.getRecords().size());

        Map<String, Object> first = result.getRecords().get(0);
        assertEquals(1L, first.get("id"));
        assertEquals("alice", first.get("username"));
        assertEquals("alice@example.com", first.get("email"));
        assertEquals("DEVELOPER", first.get("role"));
        assertEquals(1, first.get("status"));
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5), first.get("createdAt"));
        assertEquals(LocalDateTime.of(2026, 2, 3, 4, 5, 6), first.get("lastLoginAt"));
        assertEquals(7, first.size());

        assertEquals("bob", result.getRecords().get(1).get("username"));
    }

    @Test
    @DisplayName("用户列表：无数据返回空记录")
    void listUsers_empty_shouldReturnEmptyRecords() {
        when(userMapper.countAll()).thenReturn(0L);
        when(userMapper.findAllPaged(0, 20)).thenReturn(List.of());

        PageResult<Map<String, Object>> result = adminService.listUsers(1, 20);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    @Test
    @DisplayName("更新角色：非法角色抛 400，消息含非法值与全部可选角色")
    void updateRole_invalid_shouldThrow400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.updateRole(5L, "GUEST"));

        assertEquals(400, ex.getCode());
        assertTrue(ex.getMessage().startsWith("无效角色: GUEST，可选: "),
                "实际消息: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("ADMIN"));
        assertTrue(ex.getMessage().contains("ARCHITECT"));
        assertTrue(ex.getMessage().contains("DEVELOPER"));
        assertTrue(ex.getMessage().contains("VIEWER"));
        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("更新角色：role 为 null 时 Set.of().contains(null) 抛 NPE（主代码隐患，见报告）")
    void updateRole_null_shouldThrowNpe() {
        // 记录当前实现行为：Set.of(...).contains(null) 会抛 NPE 而不是 400 BusinessException。
        // 经 HTTP 入口 @RequestParam String role 无法传入 null（缺参会先 400），属服务层调用隐患。
        assertThrows(NullPointerException.class, () -> adminService.updateRole(5L, null));

        verifyNoInteractions(userMapper);
    }

    @Test
    @DisplayName("更新角色：合法角色委托 mapper")
    void updateRole_valid_shouldDelegate() {
        adminService.updateRole(5L, "ADMIN");

        verify(userMapper).updateRole(5L, "ADMIN");
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    @DisplayName("更新状态：非 0/1 抛 400")
    void updateStatus_invalidValue_shouldThrow400() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.updateStatus(5L, 2));

        assertEquals(400, ex.getCode());
        assertEquals("状态值只能为 0(禁用) 或 1(正常)", ex.getMessage());
        verifyNoInteractions(userMapper);

        assertThrows(BusinessException.class, () -> adminService.updateStatus(5L, -1));
    }

    @Test
    @DisplayName("更新状态：0 和 1 均合法")
    void updateStatus_validValues_shouldDelegate() {
        adminService.updateStatus(5L, 0);
        adminService.updateStatus(6L, 1);

        verify(userMapper).updateStatus(5L, 0);
        verify(userMapper).updateStatus(6L, 1);
        verifyNoMoreInteractions(userMapper);
    }

    @Test
    @DisplayName("审计日志：offset=(page-1)*size，逐条映射 9 个字段")
    void auditLog_shouldPageAndMapNineFields() {
        when(auditLogMapper.countAll()).thenReturn(3L);
        when(auditLogMapper.findPaged(20, 20)).thenReturn(List.of(auditLog(9L)));

        PageResult<Map<String, Object>> result = adminService.auditLog(2, 20);

        assertEquals(3L, result.getTotal());
        assertEquals(1, result.getRecords().size());
        Map<String, Object> item = result.getRecords().get(0);
        assertEquals(9L, item.get("id"));
        assertEquals(7L, item.get("userId"));
        assertEquals("alice", item.get("username"));
        assertEquals("CREATE_PROJECT", item.get("action"));
        assertEquals("PROJECT", item.get("targetType"));
        assertEquals(100L, item.get("targetId"));
        assertEquals("detail-9", item.get("detail"));
        assertEquals("10.0.0.1", item.get("ipAddress"));
        assertEquals(LocalDateTime.of(2026, 3, 4, 5, 6, 7), item.get("createdAt"));
        assertEquals(9, item.size());
    }

    @Test
    @DisplayName("审计日志：无数据返回空记录")
    void auditLog_empty_shouldReturnEmptyRecords() {
        when(auditLogMapper.countAll()).thenReturn(0L);
        when(auditLogMapper.findPaged(0, 10)).thenReturn(List.of());

        PageResult<Map<String, Object>> result = adminService.auditLog(1, 10);

        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
    }

    @Test
    @DisplayName("Token 流水：分页参数归一化后按 offset 查询并返回总数")
    void aiUsage_shouldNormalizePagingAndQueryMapper() {
        AiUsageQuery query = new AiUsageQuery();
        query.setPage(0);
        query.setSize(500);
        query.setModel("deepseek-v4-pro");
        when(aiAuditLogMapper.countUsage(query)).thenReturn(42L);
        when(aiAuditLogMapper.findUsagePaged(query, 0))
                .thenReturn(List.of(Map.of("model", "deepseek-v4-pro")));

        PageResult<Map<String, Object>> result = adminService.aiUsage(query);

        assertEquals(42L, result.getTotal());
        assertEquals(1, result.getPage());
        assertEquals(100, result.getSize());
        assertEquals(1, result.getRecords().size());
        verify(aiAuditLogMapper).countUsage(query);
        verify(aiAuditLogMapper).findUsagePaged(query, 0);
    }

    @Test
    @DisplayName("Token 流水：第 2 页 offset=(page-1)*size")
    void aiUsage_secondPageShouldOffset() {
        AiUsageQuery query = new AiUsageQuery();
        query.setPage(2);
        query.setSize(20);
        when(aiAuditLogMapper.countUsage(query)).thenReturn(21L);
        when(aiAuditLogMapper.findUsagePaged(query, 20)).thenReturn(List.of());

        PageResult<Map<String, Object>> result = adminService.aiUsage(query);

        assertEquals(21L, result.getTotal());
        assertEquals(2, result.getPage());
        verify(aiAuditLogMapper).findUsagePaged(query, 20);
    }

    @Test
    @DisplayName("Token 用量汇总：未传区间默认本自然月，预算按 Redis 状态换算百分比")
    void aiUsageSummary_shouldDefaultCurrentMonthAndIncludeBudget() {
        AiUsageQuery query = new AiUsageQuery();
        when(aiAuditLogMapper.usageSummary(query)).thenReturn(Map.of("calls", 3L, "totalTokens", 1500L));
        when(aiAuditLogMapper.usageByModel(query)).thenReturn(List.of(Map.of("model", "deepseek-v4-pro")));
        when(aiAuditLogMapper.usageByStage(query)).thenReturn(List.of(Map.of("stage", "CONTEXT_QA")));
        when(aiAuditLogMapper.usageByUser(query)).thenReturn(List.of(Map.of("username", "zferry")));
        when(tokenBudgetManager.getBudgetStatus()).thenReturn(new long[]{1_000_000L, 10_000_000L});

        Map<String, Object> result = adminService.aiUsageSummary(query);

        LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
        assertEquals(firstDay.atStartOfDay().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                query.getStartTime());
        assertEquals(firstDay.plusMonths(1).atStartOfDay()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                query.getEndTime());
        assertEquals(3L, ((Map<?, ?>) result.get("totals")).get("calls"));
        assertEquals(1, ((List<?>) result.get("byModel")).size());
        assertEquals(1, ((List<?>) result.get("byStage")).size());
        assertEquals(1, ((List<?>) result.get("byUser")).size());
        Map<?, ?> budget = (Map<?, ?>) result.get("budget");
        assertEquals(1_000_000L, budget.get("usedTokens"));
        assertEquals(10_000_000L, budget.get("monthlyLimit"));
        assertEquals(10.0, budget.get("usagePercent"));
    }

    @Test
    @DisplayName("Token 用量汇总：空区间结果 totals 回退为空 Map")
    void aiUsageSummary_nullTotals_shouldFallbackToEmptyMap() {
        AiUsageQuery query = new AiUsageQuery();
        query.setStartTime("2026-10-01 00:00:00");
        query.setEndTime("2026-11-01 00:00:00");
        when(aiAuditLogMapper.usageSummary(query)).thenReturn(null);
        when(aiAuditLogMapper.usageByModel(query)).thenReturn(List.of());
        when(aiAuditLogMapper.usageByStage(query)).thenReturn(List.of());
        when(aiAuditLogMapper.usageByUser(query)).thenReturn(List.of());
        when(tokenBudgetManager.getBudgetStatus()).thenReturn(new long[]{0, 0});

        Map<String, Object> result = adminService.aiUsageSummary(query);

        assertTrue(((Map<?, ?>) result.get("totals")).isEmpty());
        assertEquals(0.0, ((Map<?, ?>) result.get("budget")).get("usagePercent"));
    }

    @Test
    @DisplayName("系统状态：直接返回清理服务的状态")
    void getSystemStatus_shouldReturnCleanupStatus() {
        Map<String, Object> status = Map.of("diskUsage", "10%");
        when(cleanupService.getCleanupStatus()).thenReturn(status);

        Map<String, Object> result = adminService.getSystemStatus();

        assertSame(status, result);
    }
}
