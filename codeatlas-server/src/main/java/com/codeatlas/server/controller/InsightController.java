package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
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
    public ApiResponse<List<InsightVO>> getInsights(@PathVariable Long projectId,
                                                     @RequestParam(required = false) String type,
                                                     @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        List<InsightEntity> insights;
        if (type != null && !type.isEmpty()) {
            insights = insightService.getInsightsByType(projectId, type);
        } else {
            insights = insightService.getInsights(projectId);
        }
        List<InsightVO> result = insights.stream().map(InsightVO::from).collect(Collectors.toList());
        return ApiResponse.success(result);
    }
}
