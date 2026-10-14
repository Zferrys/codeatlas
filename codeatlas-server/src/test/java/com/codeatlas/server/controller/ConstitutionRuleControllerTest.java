package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.response.ConstitutionRuleVO;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ConstitutionRuleService;
import com.codeatlas.server.service.ProjectAccessChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConstitutionRuleController 单元测试")
class ConstitutionRuleControllerTest {

    private static final Long PROJECT_ID = 6L;
    private static final Long USER_ID = 3L;
    private static final Long RULE_ID = 77L;

    @Mock
    private ConstitutionRuleService ruleService;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    private ConstitutionRuleController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ConstitutionRuleController(ruleService, projectAccessChecker);
        principal = new CodeAtlasUserDetails(USER_ID, "erin", "ARCHITECT");
    }

    private static ConstitutionRuleEntity ruleEntity(Long id) {
        ConstitutionRuleEntity entity = new ConstitutionRuleEntity();
        entity.setId(id);
        entity.setProjectId(PROJECT_ID);
        entity.setName("命名规范");
        entity.setDescription("描述");
        entity.setCategory("NAMING");
        entity.setSeverity("MAJOR");
        entity.setRuleDefinition("{\"pattern\":\"x\"}");
        entity.setIsEnabled(true);
        entity.setVersion(2);
        return entity;
    }

    @Test
    @DisplayName("getRules — enabledOnly=true 走启用规则分页并映射 VO")
    void getRulesEnabledOnly() {
        when(ruleService.getRulesPaged(PROJECT_ID, 2, 4))
                .thenReturn(new PageResult<>(List.of(ruleEntity(1L)), 9L, 2, 4));

        ApiResponse<PageResult<ConstitutionRuleVO>> response =
                controller.getRules(PROJECT_ID, true, 2, 4, principal);

        assertEquals(200, response.getCode());
        PageResult<ConstitutionRuleVO> page = response.getData();
        assertEquals(9L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(4, page.getSize());
        ConstitutionRuleVO vo = page.getRecords().get(0);
        assertEquals(1L, vo.getId());
        assertEquals(PROJECT_ID, vo.getProjectId());
        assertEquals("命名规范", vo.getName());
        assertEquals("描述", vo.getDescription());
        assertEquals("NAMING", vo.getCategory());
        assertEquals("MAJOR", vo.getSeverity());
        assertEquals("{\"pattern\":\"x\"}", vo.getRuleDefinition());
        assertEquals(Boolean.TRUE, vo.getIsEnabled());
        assertEquals(2, vo.getVersion());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(ruleService, never()).getAllRulesPaged(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("getRules — enabledOnly=false 走全量规则分页")
    void getRulesIncludeDisabled() {
        when(ruleService.getAllRulesPaged(PROJECT_ID, 1, 20))
                .thenReturn(new PageResult<>(Collections.emptyList(), 0L, 1, 20));

        ApiResponse<PageResult<ConstitutionRuleVO>> response =
                controller.getRules(PROJECT_ID, false, 1, 20, principal);

        assertEquals(0, response.getData().getRecords().size());
        verify(ruleService).getAllRulesPaged(PROJECT_ID, 1, 20);
        verify(ruleService, never()).getRulesPaged(any(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("toggleRule — 成功切换返回 VO")
    void toggleRuleSuccess() {
        ConstitutionRuleEntity entity = ruleEntity(RULE_ID);
        entity.setIsEnabled(false);
        when(ruleService.toggleRule(PROJECT_ID, RULE_ID, false, USER_ID)).thenReturn(entity);

        ApiResponse<ConstitutionRuleVO> response =
                controller.toggleRule(PROJECT_ID, RULE_ID, false, principal);

        assertEquals(200, response.getCode());
        assertEquals(RULE_ID, response.getData().getId());
        assertEquals(Boolean.FALSE, response.getData().getIsEnabled());
        verify(ruleService).toggleRule(PROJECT_ID, RULE_ID, false, USER_ID);
    }

    @Test
    @DisplayName("toggleRule — 规则不存在抛 404")
    void toggleRuleNotFound() {
        when(ruleService.toggleRule(PROJECT_ID, RULE_ID, true, USER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.toggleRule(PROJECT_ID, RULE_ID, true, principal));

        assertEquals(404, ex.getCode());
        assertEquals("规则不存在", ex.getMessage());
    }

    @Test
    @DisplayName("deleteRule — 删除成功返回空数据")
    void deleteRuleSuccess() {
        ApiResponse<Void> response = controller.deleteRule(PROJECT_ID, RULE_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(ruleService).deleteRule(PROJECT_ID, RULE_ID, USER_ID);
    }
}
