package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.response.ConstitutionRuleVO;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.service.ConstitutionRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/rules")
@Tag(name = "宪法规则")
public class ConstitutionRuleController {

    private final ConstitutionRuleService ruleService;

    public ConstitutionRuleController(ConstitutionRuleService ruleService) {
        this.ruleService = ruleService;
    }

    @GetMapping
    @Operation(summary = "获取项目的宪法规则列表")
    public ApiResponse<PageResult<ConstitutionRuleVO>> getRules(
            @PathVariable Long projectId,
            @RequestParam(defaultValue = "true") boolean enabledOnly,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<ConstitutionRuleEntity> pageResult;
        if (enabledOnly) {
            pageResult = ruleService.getRulesPaged(projectId, page, size);
        } else {
            pageResult = ruleService.getAllRulesPaged(projectId, page, size);
        }
        List<ConstitutionRuleVO> records = pageResult.getRecords().stream()
                .map(ConstitutionRuleVO::from).collect(Collectors.toList());
        return ApiResponse.success(new PageResult<>(records, pageResult.getTotal(), page, size));
    }

    @PutMapping("/{ruleId}/toggle")
    @Operation(summary = "启用/禁用规则")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT')")
    @AuditLog(action = "TOGGLE_RULE", targetType = "RULE", targetIdExpression = "#ruleId", detail = "启用/禁用规则")
    public ApiResponse<ConstitutionRuleVO> toggleRule(@PathVariable Long projectId,
                                                       @PathVariable Long ruleId,
                                                       @RequestParam boolean enabled) {
        ConstitutionRuleEntity entity = ruleService.toggleRule(ruleId, enabled);
        if (entity == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "规则不存在");
        }
        return ApiResponse.success(ConstitutionRuleVO.from(entity));
    }
}
