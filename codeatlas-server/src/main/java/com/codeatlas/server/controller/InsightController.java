package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.InsightVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.InsightService;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import com.codeatlas.server.util.HealthScoreCalculator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "AI 洞察")
public class InsightController {

    private final InsightService insightService;
    private final ProjectService projectService;
    private final ScanService scanService;

    public InsightController(InsightService insightService, ProjectService projectService,
                             ScanService scanService) {
        this.insightService = insightService;
        this.projectService = projectService;
        this.scanService = scanService;
    }

    @GetMapping("/insights")
    @PreAuthorize("isAuthenticated()")
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

    @GetMapping("/health-score")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "获取架构健康度评分")
    public ApiResponse<Map<String, Object>> getHealthScore(@PathVariable Long projectId,
                                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            return ApiResponse.error(404, "项目不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projectId", projectId);

        // 四维度与总分统一由 HealthScoreCalculator 计算（与扫描落库口径一致）
        ScanRecord latestScan = scanService.getLatestScanEntity(projectId);
        int totalClasses = (latestScan != null && latestScan.getTotalClasses() != null)
                ? latestScan.getTotalClasses() : 0;
        int violations = (latestScan != null && latestScan.getTotalViolations() != null)
                ? latestScan.getTotalViolations() : 0;
        List<ClassSummaryEntity> classes = totalClasses > 0
                ? scanService.getClassSummaries(projectId)
                : Collections.emptyList();
        HealthScoreCalculator.Result score = HealthScoreCalculator.compute(totalClasses, violations, classes);

        result.put("healthScore", score.getTotal());

        Map<String, Object> dims = new LinkedHashMap<>();
        dims.put("architectureCompliance", score.getArchitecture());
        dims.put("codeStructure", score.getStructure());
        dims.put("codeQuality", score.getQuality());
        dims.put("dependencyHealth", score.getDependency());
        result.put("dimensions", dims);
        return ApiResponse.success(result);
    }
}
