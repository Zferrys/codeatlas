package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.service.ClassService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/classes")
@Tag(name = "类浏览器")
public class ClassController {

    private final ClassService classService;

    public ClassController(ClassService classService) {
        this.classService = classService;
    }

    @GetMapping("/{fqn:.+}")
    @Operation(summary = "获取类详情")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getClassDetail(@PathVariable Long projectId,
                                                            @PathVariable String fqn) {
        Map<String, Object> detail = classService.getClassDetail(projectId, fqn);
        if (detail == null) {
            return ApiResponse.error(404, "类不存在: " + fqn);
        }
        return ApiResponse.success(detail);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索类（按类名或全限定名）")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> searchClasses(@PathVariable Long projectId,
                                                                  @RequestParam String q,
                                                                  @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(classService.searchClasses(projectId, q, limit));
    }
}
