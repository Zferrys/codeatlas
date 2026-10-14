package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.ViolationVO;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ViolationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ViolationController 单元测试")
class ViolationControllerTest {

    private static final Long PROJECT_ID = 20L;
    private static final Long USER_ID = 30L;

    @Mock
    private ViolationService violationService;

    private ViolationController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ViolationController(violationService);
        principal = new CodeAtlasUserDetails(USER_ID, "henry", "DEVELOPER");
    }

    private static ViolationEntity violation(Long id) {
        ViolationEntity entity = new ViolationEntity();
        entity.setId(id);
        entity.setScanId(99L);
        entity.setRuleId(5L);
        entity.setProjectId(PROJECT_ID);
        entity.setSeverity("MAJOR");
        entity.setClassFqn("com.a.B");
        entity.setMethodName("run");
        entity.setLineNumber(42);
        entity.setMessage("违规消息");
        entity.setSuggestion("建议");
        entity.setIsResolved(false);
        entity.setResolvedAt(null);
        entity.setCreatedAt(LocalDateTime.of(2024, 9, 9, 9, 9, 9));
        return entity;
    }

    @Test
    @DisplayName("getViolations — 分页映射 VO 全字段并保留分页元信息")
    void getViolationsMapsAllFields() {
        when(violationService.getViolations(PROJECT_ID, 2, 50, USER_ID))
                .thenReturn(new PageResult<>(List.of(violation(7L)), 21L, 2, 50));

        ApiResponse<PageResult<ViolationVO>> response = controller.getViolations(PROJECT_ID, 2, 50, principal);

        assertEquals(200, response.getCode());
        PageResult<ViolationVO> page = response.getData();
        assertEquals(21L, page.getTotal());
        assertEquals(2, page.getPage());
        assertEquals(50, page.getSize());
        ViolationVO vo = page.getRecords().get(0);
        assertEquals(7L, vo.getId());
        assertEquals(99L, vo.getScanId());
        assertEquals(5L, vo.getRuleId());
        assertEquals(PROJECT_ID, vo.getProjectId());
        assertEquals("MAJOR", vo.getSeverity());
        assertEquals("com.a.B", vo.getClassFqn());
        assertEquals("run", vo.getMethodName());
        assertEquals(42, vo.getLineNumber());
        assertEquals("违规消息", vo.getMessage());
        assertEquals("建议", vo.getSuggestion());
        assertEquals(Boolean.FALSE, vo.getIsResolved());
        assertNull(vo.getResolvedAt());
        assertEquals(LocalDateTime.of(2024, 9, 9, 9, 9, 9), vo.getCreatedAt());
        verify(violationService).getViolations(PROJECT_ID, 2, 50, USER_ID);
    }

    @Test
    @DisplayName("getViolations — 空页返回空记录列表")
    void getViolationsEmptyPage() {
        when(violationService.getViolations(PROJECT_ID, 1, 20, USER_ID))
                .thenReturn(new PageResult<>(Collections.emptyList(), 0L, 1, 20));

        ApiResponse<PageResult<ViolationVO>> response = controller.getViolations(PROJECT_ID, 1, 20, principal);

        assertEquals(0, response.getData().getRecords().size());
        assertEquals(0L, response.getData().getTotal());
    }

    @Test
    @DisplayName("resolveViolation — 项目/违规/用户三者精确透传")
    void resolveViolationPassesArgs() {
        ApiResponse<Void> response = controller.resolveViolation(PROJECT_ID, 55L, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(violationService).resolveViolation(PROJECT_ID, 55L, USER_ID);
    }
}
