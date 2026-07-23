package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.dto.response.ViolationVO;
import com.codeatlas.server.service.ViolationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/violations")
@Tag(name = "违规列表")
public class ViolationController {

    private final ViolationService violationService;

    public ViolationController(ViolationService violationService) {
        this.violationService = violationService;
    }

    @GetMapping
    @Operation(summary = "获取项目违规列表")
    public ApiResponse<List<ViolationVO>> getViolations(@PathVariable Long projectId) {
        List<ViolationVO> result = violationService.getViolations(projectId).stream()
                .map(ViolationVO::from).collect(Collectors.toList());
        return ApiResponse.success(result);
    }

    @PutMapping("/{violationId}/resolve")
    @Operation(summary = "标记违规为已解决")
    public ApiResponse<Void> resolveViolation(@PathVariable Long projectId,
                                               @PathVariable Long violationId) {
        violationService.resolveViolation(violationId);
        return ApiResponse.success(null);
    }
}
