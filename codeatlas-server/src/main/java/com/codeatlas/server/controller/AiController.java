package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.AgenticSearchService;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.ImpactAnalysisService;
import com.codeatlas.server.service.InsightService;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai")
@Tag(name = "AI 分析")
public class AiController {

    private final AiAnalysisService aiAnalysisService;
    private final ImpactAnalysisService impactAnalysisService;
    private final InsightService insightService;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final AgenticSearchService agenticSearchService;

    public AiController(AiAnalysisService aiAnalysisService,
                        ImpactAnalysisService impactAnalysisService,
                        InsightService insightService,
                        ProjectService projectService,
                        ScanService scanService,
                        AgenticSearchService agenticSearchService) {
        this.aiAnalysisService = aiAnalysisService;
        this.impactAnalysisService = impactAnalysisService;
        this.insightService = insightService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.agenticSearchService = agenticSearchService;
    }

    @GetMapping("/story")
    @Operation(summary = "获取最新架构叙事")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getStory(@PathVariable Long projectId,
                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        InsightEntity story = insightService.getLatestArchStory(projectId);
        if (story == null) {
            return ApiResponse.success(null);
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", story.getId());
        result.put("title", story.getTitle());
        result.put("content", story.getContent());
        result.put("confidence", story.getConfidence());
        result.put("createdAt", story.getCreatedAt() != null ? story.getCreatedAt().toString() : null);
        return ApiResponse.success(result);
    }

    @PostMapping("/story")
    @Operation(summary = "生成/刷新架构叙事")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> generateStory(@PathVariable Long projectId,
                                                          @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            return ApiResponse.error(404, "项目不存在");
        }
        ScanRecord scan = scanService.getLatestScanEntity(projectId);
        if (scan == null) {
            return ApiResponse.error(400, "请先触发一次扫描");
        }
        InsightEntity insight = aiAnalysisService.analyzeArchitecture(projectId, scan.getId());
        if (insight == null) {
            return ApiResponse.error(500, "AI 分析失败，请确认已配置 AI API Key");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", insight.getId());
        result.put("title", insight.getTitle());
        result.put("confidence", insight.getConfidence());
        return ApiResponse.success(result);
    }

    @PostMapping("/impact-simulate")
    @Operation(summary = "变更影响模拟")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> impactSimulate(@PathVariable Long projectId,
                                                           @RequestBody Map<String, Object> body,
                                                           @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        String targetClass = (String) body.get("targetClass");
        String changeType = (String) body.getOrDefault("changeType", "MODIFY_METHOD");
        String targetMethod = (String) body.get("targetMethod");
        String description = (String) body.getOrDefault("description", "");

        if (targetClass == null || targetClass.isEmpty()) {
            return ApiResponse.error(400, "targetClass 不能为空");
        }

        Map<String, Object> result = impactAnalysisService.analyzeImpact(
                projectId, targetClass, changeType, targetMethod, description);
        return ApiResponse.success(result);
    }

    @PostMapping("/context-qa")
    @Operation(summary = "上下文问答（支持 Agentic Search）")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> contextQa(@PathVariable Long projectId,
                                                       @RequestBody Map<String, Object> body,
                                                       @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        String question = (String) body.get("question");
        String classFqn = (String) body.get("classFqn");

        if (question == null || question.trim().isEmpty()) {
            return ApiResponse.error(400, "question 不能为空");
        }

        // 未指定类名 → Agentic Search（AI 自动搜索代码库）
        if (classFqn == null || classFqn.trim().isEmpty()) {
            Map<String, Object> result = agenticSearchService.search(projectId, question.trim(), principal.getUserId());
            return ApiResponse.success(result);
        }

        // 指定了类名 → 精确上下文匹配（兼容原有行为）
        Map<String, Object> result = impactAnalysisService.contextualQa(projectId, question, classFqn);
        return ApiResponse.success(result);
    }
}
