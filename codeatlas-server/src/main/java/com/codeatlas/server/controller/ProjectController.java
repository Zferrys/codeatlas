package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.request.UpdateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "项目管理")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    @Operation(summary = "创建项目")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT')")
    @AuditLog(action = "CREATE_PROJECT", targetType = "PROJECT", targetIdExpression = "#result.data.id", detail = "创建项目")
    public ApiResponse<ProjectVO> createProject(@Valid @RequestBody CreateProjectRequest request,
                                                 @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(projectService.createProject(request, principal.getUserId()));
    }

    @GetMapping
    @Operation(summary = "项目列表")
    public ApiResponse<Map<String, Object>> listProjects(@AuthenticationPrincipal CodeAtlasUserDetails principal) {
        List<ProjectVO> projects = projectService.listProjects(principal.getUserId());
        Map<String, Object> result = new HashMap<>();
        result.put("records", projects);
        return ApiResponse.success(result);
    }

    @GetMapping("/{id}")
    @Operation(summary = "项目详情")
    public ApiResponse<ProjectVO> getProject(@PathVariable Long id,
                                              @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(projectService.getProjectById(id, principal.getUserId()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除项目")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Void> deleteProject(@PathVariable Long id,
                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectService.deleteProject(id, principal.getUserId());
        return ApiResponse.success();
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新项目")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT')")
    public ApiResponse<ProjectVO> updateProject(@PathVariable Long id,
                                                 @Valid @RequestBody UpdateProjectRequest request,
                                                 @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(projectService.updateProject(
                id, request.getName(), request.getDescription(), principal.getUserId()));
    }
}
