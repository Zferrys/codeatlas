package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.InsightVO;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.InsightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/insights")
@Tag(name = "AI 洞察")
public class InsightController {

    private final InsightService insightService;

    public InsightController(InsightService insightService) {
        this.insightService = insightService;
    }

    @GetMapping
    @Operation(summary = "获取项目洞察列表")
    public ApiResponse<PageResult<InsightVO>> getInsights(
            @PathVariable Long projectId,
            @RequestParam(required = false) String type,
            @AuthenticationPrincipal CodeAtlasUserDetails principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResult<InsightEntity> pageResult;
        if (type != null && !type.isEmpty()) {
            pageResult = insightService.getInsightsByType(projectId, type, page, size);
        } else {
            pageResult = insightService.getInsights(projectId, page, size);
        }
        List<InsightVO> records = pageResult.getRecords().stream()
                .map(InsightVO::from).collect(Collectors.toList());
        return ApiResponse.success(new PageResult<>(records, pageResult.getTotal(), page, size));
    }
}
