package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.AgenticSearchService;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.AiTaskProgressRegistry;
import com.codeatlas.server.service.AntiPatternService;
import com.codeatlas.server.service.ArchitecturePatternService;
import com.codeatlas.server.service.ConstitutionCheckService;
import com.codeatlas.server.service.ImpactAnalysisService;
import com.codeatlas.server.service.InsightService;
import com.codeatlas.server.service.ProjectAccessChecker;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

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
    private final ArchitecturePatternService architecturePatternService;
    private final AntiPatternService antiPatternService;
    private final ConstitutionCheckService constitutionCheckService;
    private final ProjectAccessChecker projectAccessChecker;
    private final AiTaskProgressRegistry progressRegistry;

    public AiController(AiAnalysisService aiAnalysisService,
                        ImpactAnalysisService impactAnalysisService,
                        InsightService insightService,
                        ProjectService projectService,
                        ScanService scanService,
                        AgenticSearchService agenticSearchService,
                        ArchitecturePatternService architecturePatternService,
                        AntiPatternService antiPatternService,
                        ConstitutionCheckService constitutionCheckService,
                        ProjectAccessChecker projectAccessChecker,
                        AiTaskProgressRegistry progressRegistry) {
        this.aiAnalysisService = aiAnalysisService;
        this.impactAnalysisService = impactAnalysisService;
        this.insightService = insightService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.agenticSearchService = agenticSearchService;
        this.architecturePatternService = architecturePatternService;
        this.antiPatternService = antiPatternService;
        this.constitutionCheckService = constitutionCheckService;
        this.projectAccessChecker = projectAccessChecker;
        this.progressRegistry = progressRegistry;
    }

    @GetMapping("/story")
    @Operation(summary = "获取最新架构叙事")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getStory(@PathVariable Long projectId,
                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
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
                                                          @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                          @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        try {
            Project project = projectService.getProjectEntity(projectId);
            projectAccessChecker.check(project, projectId, principal.getUserId());
            ScanRecord scan = scanService.getLatestScanEntity(projectId);
            if (scan == null) {
                return ApiResponse.error(400, "请先触发一次扫描");
            }
            InsightEntity insight = aiAnalysisService.analyzeArchitecture(projectId, scan.getId(), taskId,
                    principal.getUserId());
            if (insight == null) {
                return ApiResponse.error(500, "AI 分析失败：模型未返回有效内容或服务暂不可用，请稍后重试");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("id", insight.getId());
            result.put("title", insight.getTitle());
            result.put("confidence", insight.getConfidence());
            return ApiResponse.success(result);
        } finally {
            progressRegistry.complete(taskId);
        }
    }

    @PostMapping("/impact-simulate")
    @Operation(summary = "变更影响模拟")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> impactSimulate(@PathVariable Long projectId,
                                                           @RequestBody Map<String, Object> body,
                                                           @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                           @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        try {
            String targetClass = (String) body.get("targetClass");
            String changeType = (String) body.getOrDefault("changeType", "MODIFY_METHOD");
            String targetMethod = (String) body.get("targetMethod");
            String description = (String) body.getOrDefault("description", "");

            if (targetClass == null || targetClass.isEmpty()) {
                return ApiResponse.error(400, "targetClass 不能为空");
            }

            projectAccessChecker.check(projectId, principal.getUserId());
            Map<String, Object> result = impactAnalysisService.analyzeImpact(
                    projectId, targetClass, changeType, targetMethod, description, principal.getUserId(), taskId);
            return ApiResponse.success(result);
        } finally {
            progressRegistry.complete(taskId);
        }
    }

    @PostMapping("/context-qa")
    @Operation(summary = "上下文问答（支持 Agentic Search）")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> contextQa(@PathVariable Long projectId,
                                                       @RequestBody Map<String, Object> body,
                                                       @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                       @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        try {
            String question = (String) body.get("question");
            String classFqn = (String) body.get("classFqn");

            if (question == null || question.trim().isEmpty()) {
                return ApiResponse.error(400, "question 不能为空");
            }

            projectAccessChecker.check(projectId, principal.getUserId());

            // 未指定类名 → Agentic Search（AI 自动搜索代码库）
            if (classFqn == null || classFqn.trim().isEmpty()) {
                Map<String, Object> result = agenticSearchService.search(projectId, question.trim(), principal.getUserId(), taskId);
                return ApiResponse.success(result);
            }

            // 指定了类名 → 精确上下文匹配（兼容原有行为）
            Map<String, Object> result = impactAnalysisService.contextualQa(projectId, question, classFqn, principal.getUserId(), taskId);
            return ApiResponse.success(result);
        } finally {
            progressRegistry.complete(taskId);
        }
    }

    @GetMapping("/pattern")
    @Operation(summary = "获取最新设计模式报告")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getPattern(@PathVariable Long projectId,
                                                       @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return ApiResponse.success(latestInsightMap(projectId, "PATTERN"));
    }

    @PostMapping("/pattern")
    @Operation(summary = "生成/刷新设计模式报告")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> generatePattern(@PathVariable Long projectId,
                                                            @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return generateInsight(() -> architecturePatternService.analyzePatterns(projectId, principal.getUserId(), taskId), taskId);
    }

    @GetMapping("/anti-pattern")
    @Operation(summary = "获取最新反模式检测")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getAntiPattern(@PathVariable Long projectId,
                                                           @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return ApiResponse.success(latestInsightMap(projectId, "ANTI_PATTERN"));
    }

    @PostMapping("/anti-pattern")
    @Operation(summary = "生成/刷新反模式检测")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> generateAntiPattern(@PathVariable Long projectId,
                                                                @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                                @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return generateInsight(() -> antiPatternService.analyzeAntiPatterns(projectId, principal.getUserId(), taskId), taskId);
    }

    @GetMapping("/constitution-check")
    @Operation(summary = "获取最新规约合规检查")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getConstitutionCheck(@PathVariable Long projectId,
                                                                 @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return ApiResponse.success(latestInsightMap(projectId, "CONSTITUTION_CHECK"));
    }

    @PostMapping("/constitution-check")
    @Operation(summary = "生成/刷新规约合规检查")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    public ApiResponse<Map<String, Object>> generateConstitutionCheck(@PathVariable Long projectId,
                                                                      @RequestHeader(value = "X-Ai-Task-Id", required = false) String taskId,
                                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        return generateInsight(() -> constitutionCheckService.analyzeConstitution(projectId, principal.getUserId(), taskId), taskId);
    }

    private ApiResponse<Map<String, Object>> generateInsight(Supplier<InsightEntity> analysis, String taskId) {
        try {
            InsightEntity insight = analysis.get();
            if (insight == null) {
                return ApiResponse.error(500, "AI 分析失败：服务未就绪或返回为空，请稍后重试");
            }
            Map<String, Object> result = new HashMap<>();
            result.put("id", insight.getId());
            result.put("title", insight.getTitle());
            result.put("confidence", insight.getConfidence());
            return ApiResponse.success(result);
        } finally {
            progressRegistry.complete(taskId);
        }
    }

    private Map<String, Object> latestInsightMap(Long projectId, String type) {
        InsightEntity insight = insightService.getLatestByType(projectId, type);
        if (insight == null) {
            return null;
        }
        Map<String, Object> result = new HashMap<>();
        result.put("id", insight.getId());
        result.put("title", insight.getTitle());
        result.put("content", insight.getContent());
        result.put("confidence", insight.getConfidence());
        result.put("createdAt", insight.getCreatedAt() != null ? insight.getCreatedAt().toString() : null);
        return result;
    }
}
