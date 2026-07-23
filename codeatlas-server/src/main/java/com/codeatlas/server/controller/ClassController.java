package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/classes")
@Tag(name = "类浏览器")
public class ClassController {

    private final ClassSummaryMapper classSummaryMapper;

    public ClassController(ClassSummaryMapper classSummaryMapper) {
        this.classSummaryMapper = classSummaryMapper;
    }

    @GetMapping("/{fqn:.+}")
    @Operation(summary = "获取类详情")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> getClassDetail(@PathVariable Long projectId,
                                                            @PathVariable String fqn) {
        List<ClassSummaryEntity> all = classSummaryMapper.findByProjectId(projectId);
        ClassSummaryEntity target = all.stream()
                .filter(c -> fqn.equals(c.getFqn()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return ApiResponse.error(404, "类不存在: " + fqn);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", target.getId());
        result.put("fqn", target.getFqn());
        result.put("simpleName", target.getSimpleName());
        result.put("packageName", target.getPackageName());
        result.put("classType", target.getClassType());
        result.put("layer", target.getLayer());
        result.put("publicMethods", target.getPublicMethods());
        result.put("totalMethods", target.getTotalMethods());
        result.put("lineCount", target.getLineCount());
        result.put("annotations", target.getAnnotations());
        result.put("dependencies", target.getDependencies());
        result.put("moduleName", target.getModuleName());
        return ApiResponse.success(result);
    }

    @GetMapping("/search")
    @Operation(summary = "搜索类（按类名或全限定名）")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> searchClasses(@PathVariable Long projectId,
                                                                  @RequestParam String q,
                                                                  @RequestParam(defaultValue = "20") int limit) {
        List<ClassSummaryEntity> entities = classSummaryMapper.searchByKeyword(q, limit);
        List<Map<String, Object>> records = entities.stream()
                .filter(e -> e.getProjectId().equals(projectId))
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", e.getId());
                    item.put("fqn", e.getFqn());
                    item.put("simpleName", e.getSimpleName());
                    item.put("packageName", e.getPackageName());
                    item.put("classType", e.getClassType());
                    item.put("layer", e.getLayer());
                    item.put("lineCount", e.getLineCount());
                    item.put("projectId", e.getProjectId());
                    return item;
                })
                .collect(Collectors.toList());
        return ApiResponse.success(records);
    }
}
