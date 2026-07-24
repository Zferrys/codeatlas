package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.response.InsightVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.InsightService;
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
    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final ClassSummaryMapper classSummaryMapper;

    public InsightController(InsightService insightService, ProjectMapper projectMapper,
                             ScanMapper scanMapper, ClassSummaryMapper classSummaryMapper) {
        this.insightService = insightService;
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.classSummaryMapper = classSummaryMapper;
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
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            return ApiResponse.error(404, "项目不存在");
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projectId", projectId);
        result.put("healthScore", project.getHealthScore());

        // 从实际扫描数据计算四维度分解
        ScanRecord latestScan = scanMapper.findLatestByProjectId(projectId);
        Map<String, Object> dims = new HashMap<>();

        if (latestScan != null && latestScan.getTotalClasses() != null && latestScan.getTotalClasses() > 0) {
            int totalClasses = latestScan.getTotalClasses();
            int violations = latestScan.getTotalViolations() != null ? latestScan.getTotalViolations() : 0;

            // 架构合规度 (40% weight): 违规越少越高
            double violationRate = Math.min(violations * 5.0 / Math.max(totalClasses, 1), 40.0);
            dims.put("architectureCompliance", Math.round(100.0 - violationRate));

            // 代码结构 (30%): 基于分层覆盖率
            List<ClassSummaryEntity> classes = classSummaryMapper.findByScanId(latestScan.getId());
            long layeredClasses = classes.stream()
                    .filter(c -> c.getLayer() != null && !"UNKNOWN".equals(c.getLayer()))
                    .count();
            double layerRate = classes.isEmpty() ? 0 : (double) layeredClasses / classes.size();
            dims.put("codeStructure", Math.round(layerRate * 100.0));

            // 代码质量 (20%): 基于平均每个类的方法数合理性
            double avgMethods = classes.stream()
                    .mapToInt(c -> c.getTotalMethods() != null ? c.getTotalMethods() : 0)
                    .average().orElse(0);
            // 理想范围每个类5-15个方法，偏离则扣分
            double qualityScore = avgMethods >= 5 && avgMethods <= 15 ? 100.0
                    : avgMethods < 5 ? 100.0 - (5.0 - avgMethods) * 10.0
                    : Math.max(0, 100.0 - (avgMethods - 15.0) * 3.0);
            dims.put("codeQuality", Math.round(Math.max(0, qualityScore)));

            // 依赖健康 (10%): 如果无循环依赖则满分
            // 简化: 基于平均依赖数评估，过高或过低都不健康
            double avgDeps = classes.stream()
                    .mapToInt(c -> countParsedDeps(c.getDependencies()))
                    .average().orElse(0);
            double depScore = avgDeps >= 1 && avgDeps <= 20 ? 100.0
                    : avgDeps < 1 ? 70.0 : Math.max(0, 100.0 - (avgDeps - 20.0) * 2.0);
            dims.put("dependencyHealth", Math.round(Math.max(0, depScore)));
        } else {
            dims.put("architectureCompliance", 0);
            dims.put("codeStructure", 0);
            dims.put("codeQuality", 0);
            dims.put("dependencyHealth", 0);
        }

        result.put("dimensions", dims);
        return ApiResponse.success(result);
    }

    private int countParsedDeps(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return 0;
        try {
            List<String> deps = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
            return deps.size();
        } catch (Exception e) {
            return 0;
        }
    }
}
