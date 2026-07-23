package com.codeatlas.server.service.impl;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.service.SearchService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class SearchServiceImpl implements SearchService {

    private final ProjectMapper projectMapper;
    private final ClassSummaryMapper classSummaryMapper;

    public SearchServiceImpl(ProjectMapper projectMapper, ClassSummaryMapper classSummaryMapper) {
        this.projectMapper = projectMapper;
        this.classSummaryMapper = classSummaryMapper;
    }

    @Override
    public Map<String, Object> search(String keyword, String type, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();

        String q = keyword.trim();
        if (q.isEmpty()) {
            result.put("projects", Collections.emptyList());
            result.put("classes", Collections.emptyList());
            return result;
        }

        List<Project> userProjects = projectMapper.findByUserId(userId);
        Set<Long> userProjectIds = userProjects.stream()
                .map(Project::getId)
                .collect(Collectors.toSet());

        if ("all".equals(type) || "project".equals(type)) {
            List<Project> projects = projectMapper.searchByName(q, 20);
            List<Map<String, Object>> projectList = new ArrayList<>();
            for (Project p : projects) {
                if (!userProjectIds.contains(p.getId())) {
                    continue;
                }
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
            List<ClassSummaryEntity> classes = classSummaryMapper.searchByKeyword(q, 20);
            List<Map<String, Object>> classList = new ArrayList<>();
            for (ClassSummaryEntity c : classes) {
                if (!userProjectIds.contains(c.getProjectId())) {
                    continue;
                }
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

        return result;
    }
}
