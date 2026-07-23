package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "全局搜索")
public class SearchController {

    private final ProjectMapper projectMapper;
    private final ClassSummaryMapper classSummaryMapper;

    public SearchController(ProjectMapper projectMapper, ClassSummaryMapper classSummaryMapper) {
        this.projectMapper = projectMapper;
        this.classSummaryMapper = classSummaryMapper;
    }

    @GetMapping
    @Operation(summary = "全局搜索项目和类")
    public ApiResponse<Map<String, Object>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "all") String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        String keyword = q.trim();
        if (keyword.isEmpty()) {
            result.put("projects", Collections.emptyList());
            result.put("classes", Collections.emptyList());
            return ApiResponse.success(result);
        }

        if ("all".equals(type) || "project".equals(type)) {
            List<Project> projects = projectMapper.searchByName(keyword, 20);
            List<Map<String, Object>> projectList = new ArrayList<>();
            for (Project p : projects) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.getId());
                item.put("name", p.getName());
                item.put("type", "project");
                item.put("description", p.getDescription());
                projectList.add(item);
            }
            result.put("projects", projectList);
        }

        if ("all".equals(type) || "class".equals(type)) {
            List<ClassSummaryEntity> classes = classSummaryMapper.searchByKeyword(keyword, 20);
            List<Map<String, Object>> classList = new ArrayList<>();
            for (ClassSummaryEntity c : classes) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", c.getId());
                item.put("fqn", c.getFqn());
                item.put("simpleName", c.getSimpleName());
                item.put("type", "class");
                item.put("projectId", c.getProjectId());
                item.put("layer", c.getLayer());
                classList.add(item);
            }
            result.put("classes", classList);
        }

        return ApiResponse.success(result);
    }
}
