package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.mapper.ConstitutionRuleMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.impl.ConstitutionRuleServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConstitutionRuleServiceImpl 单元测试")
class ConstitutionRuleServiceImplTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long USER_ID = 7L;
    private static final Long RULE_ID = 10L;

    @Mock
    private ConstitutionRuleMapper mapper;
    @Mock
    private ViolationMapper violationMapper;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    @InjectMocks
    private ConstitutionRuleServiceImpl ruleService;

    private ConstitutionRuleEntity rule(Long projectId, Integer version) {
        ConstitutionRuleEntity entity = new ConstitutionRuleEntity();
        entity.setId(RULE_ID);
        entity.setProjectId(projectId);
        entity.setName("分层依赖规则");
        entity.setDescription("禁止跨层调用");
        entity.setCategory("ARCHITECTURE");
        entity.setSeverity("MAJOR");
        entity.setRuleDefinition("controller 不得直接调用 dao");
        entity.setIsEnabled(true);
        entity.setVersion(version);
        return entity;
    }

    @Test
    @DisplayName("启用规则列表：直接返回 mapper 结果")
    void getRules_shouldReturnMapperResult() {
        List<ConstitutionRuleEntity> rules = List.of(rule(PROJECT_ID, 1));
        when(mapper.findByProjectId(PROJECT_ID)).thenReturn(rules);

        assertSame(rules, ruleService.getRules(PROJECT_ID));
    }

    @Test
    @DisplayName("全量规则列表：直接返回 mapper 结果")
    void getAllRules_shouldReturnMapperResult() {
        List<ConstitutionRuleEntity> rules = List.of(rule(PROJECT_ID, 1));
        when(mapper.findAllByProjectId(PROJECT_ID)).thenReturn(rules);

        assertSame(rules, ruleService.getAllRules(PROJECT_ID));
    }

    @Test
    @DisplayName("启用规则分页：offset=(page-1)*size")
    void getRulesPaged_shouldPageWithOffset() {
        List<ConstitutionRuleEntity> records = List.of(rule(PROJECT_ID, 1));
        when(mapper.countByProjectId(PROJECT_ID)).thenReturn(6L);
        when(mapper.findByProjectIdPaged(PROJECT_ID, 4, 2)).thenReturn(records);

        PageResult<ConstitutionRuleEntity> result = ruleService.getRulesPaged(PROJECT_ID, 3, 2);

        assertEquals(6L, result.getTotal());
        assertSame(records, result.getRecords());
        assertEquals(3, result.getPage());
        assertEquals(2, result.getSize());
    }

    @Test
    @DisplayName("全量规则分页：offset=(page-1)*size")
    void getAllRulesPaged_shouldPageWithOffset() {
        List<ConstitutionRuleEntity> records = List.of(rule(PROJECT_ID, 1));
        when(mapper.countAllByProjectId(PROJECT_ID)).thenReturn(9L);
        when(mapper.findAllByProjectIdPaged(PROJECT_ID, 10, 10)).thenReturn(records);

        PageResult<ConstitutionRuleEntity> result = ruleService.getAllRulesPaged(PROJECT_ID, 2, 10);

        assertEquals(9L, result.getTotal());
        assertSame(records, result.getRecords());
    }

    @Test
    @DisplayName("切换规则：规则不存在返回 null，不写库")
    void toggleRule_missingRule_shouldReturnNull() {
        when(mapper.findById(RULE_ID)).thenReturn(null);

        assertNull(ruleService.toggleRule(PROJECT_ID, RULE_ID, true, USER_ID));

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(mapper, never()).toggleEnabled(any());
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("切换规则：项目级规则归属项目不匹配抛 404")
    void toggleRule_projectMismatch_shouldThrow404() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(2L, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.toggleRule(PROJECT_ID, RULE_ID, true, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("规则不存在", ex.getMessage());
        verify(mapper, never()).toggleEnabled(any());
    }

    @Test
    @DisplayName("切换规则：全局规则（projectId 为空）允许切换，启用时不解决违规")
    void toggleRule_globalRuleEnable_shouldToggleAndKeepViolations() {
        ConstitutionRuleEntity globalRule = rule(null, 1);
        when(mapper.findById(RULE_ID)).thenReturn(globalRule);

        ConstitutionRuleEntity result = ruleService.toggleRule(PROJECT_ID, RULE_ID, true, USER_ID);

        ArgumentCaptor<ConstitutionRuleEntity> captor = ArgumentCaptor.forClass(ConstitutionRuleEntity.class);
        verify(mapper).toggleEnabled(captor.capture());
        assertTrue(captor.getValue().getIsEnabled());
        assertEquals(RULE_ID, captor.getValue().getId());
        verify(violationMapper, never()).resolveAllByRuleId(anyLong());
        // toggle 后重新查询一次返回最新状态
        verify(mapper, times(2)).findById(RULE_ID);
        assertSame(globalRule, result);
    }

    @Test
    @DisplayName("切换规则：禁用时自动解决该规则全部未解决违规")
    void toggleRule_disable_shouldResolveAllViolations() {
        ConstitutionRuleEntity projectRule = rule(PROJECT_ID, 1);
        when(mapper.findById(RULE_ID)).thenReturn(projectRule);
        when(violationMapper.resolveAllByRuleId(RULE_ID)).thenReturn(3);

        ruleService.toggleRule(PROJECT_ID, RULE_ID, false, USER_ID);

        ArgumentCaptor<ConstitutionRuleEntity> captor = ArgumentCaptor.forClass(ConstitutionRuleEntity.class);
        verify(mapper).toggleEnabled(captor.capture());
        assertFalse(captor.getValue().getIsEnabled());
        verify(violationMapper).resolveAllByRuleId(RULE_ID);
    }

    @Test
    @DisplayName("更新规则定义：规则不存在直接返回，不写库")
    void updateRuleDefinition_missingRule_shouldNoOp() {
        when(mapper.findById(RULE_ID)).thenReturn(null);

        assertDoesNotThrow(() -> ruleService.updateRuleDefinition(RULE_ID, "新定义"));

        verify(mapper, never()).updateDefinition(any());
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("更新规则定义：版本为空从 2 起算")
    void updateRuleDefinition_nullVersion_shouldSetVersion2() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(PROJECT_ID, null));
        when(violationMapper.resolveAllByRuleId(RULE_ID)).thenReturn(0);

        ruleService.updateRuleDefinition(RULE_ID, "新定义");

        ArgumentCaptor<ConstitutionRuleEntity> captor = ArgumentCaptor.forClass(ConstitutionRuleEntity.class);
        verify(mapper).updateDefinition(captor.capture());
        assertEquals(2, captor.getValue().getVersion());
        assertEquals("新定义", captor.getValue().getRuleDefinition());
    }

    @Test
    @DisplayName("更新规则定义：已有版本号加 1，并自动解决旧违规")
    void updateRuleDefinition_existingVersion_shouldIncrementAndResolve() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(PROJECT_ID, 3));
        when(violationMapper.resolveAllByRuleId(RULE_ID)).thenReturn(5);

        ruleService.updateRuleDefinition(RULE_ID, "v4 定义");

        ArgumentCaptor<ConstitutionRuleEntity> captor = ArgumentCaptor.forClass(ConstitutionRuleEntity.class);
        verify(mapper).updateDefinition(captor.capture());
        assertEquals(4, captor.getValue().getVersion());
        assertEquals("v4 定义", captor.getValue().getRuleDefinition());
        verify(violationMapper).resolveAllByRuleId(RULE_ID);
    }

    @Test
    @DisplayName("删除规则：规则不存在抛 404")
    void deleteRule_missingRule_shouldThrow404() {
        when(mapper.findById(RULE_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.deleteRule(PROJECT_ID, RULE_ID, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("规则不存在", ex.getMessage());
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("删除规则：内置规则（projectId 为空）抛 400")
    void deleteRule_builtinRule_shouldThrow400() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(null, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.deleteRule(PROJECT_ID, RULE_ID, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("内置规则不可删除", ex.getMessage());
        verify(mapper, never()).delete(anyLong());
    }

    @Test
    @DisplayName("删除规则：规则归属其他项目抛 404")
    void deleteRule_projectMismatch_shouldThrow404() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(2L, 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> ruleService.deleteRule(PROJECT_ID, RULE_ID, USER_ID));

        assertEquals(404, ex.getCode());
        verify(mapper, never()).delete(anyLong());
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("删除规则：成功时先解决违规再删除")
    void deleteRule_success_shouldResolveThenDelete() {
        when(mapper.findById(RULE_ID)).thenReturn(rule(PROJECT_ID, 1));
        when(violationMapper.resolveAllByRuleId(RULE_ID)).thenReturn(2);

        ruleService.deleteRule(PROJECT_ID, RULE_ID, USER_ID);

        InOrder inOrder = inOrder(violationMapper, mapper);
        inOrder.verify(violationMapper).resolveAllByRuleId(RULE_ID);
        inOrder.verify(mapper).delete(RULE_ID);
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }
}
