package com.codeatlas.server.service.impl;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.service.SearchService;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class SearchServiceImpl implements SearchService {

    private static final int MAX_CLASS_RESULTS = 20;
    private static final int PER_PROJECT_CLASS_LIMIT = 20;

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

        if ("all".equals(type) || "project".equals(type)) {
            List<Project> projects = projectMapper.searchByNameForUser(q, 20, userId);
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
            // 只搜索用户有权限的项目中的类
            List<Project> userProjects = projectMapper.findByUserId(userId);
            if (userProjects.isEmpty()) {
                result.put("classes", Collections.emptyList());
                return result;
            }
            // 逐项目查询后按轮次交错合并：单一项目的命中数不影响其他项目出现，避免全局 LIMIT 被先搜的项目独占
            List<List<Map<String, Object>>> hitsByProject = new ArrayList<>();
            for (Project p : userProjects) {
                List<ClassSummaryEntity> hits = classSummaryMapper.searchByKeyword(p.getId(), q, PER_PROJECT_CLASS_LIMIT);
                if (hits.isEmpty()) {
                    continue;
                }
                List<Map<String, Object>> mapped = new ArrayList<>();
                for (ClassSummaryEntity c : hits) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", c.getId());
                    item.put("fqn", c.getFqn());
                    item.put("simpleName", c.getSimpleName());
                    item.put("type", "class");
                    item.put("projectId", c.getProjectId());
                    item.put("layer", c.getLayer());
                    mapped.add(item);
                }
                hitsByProject.add(mapped);
            }
            List<Map<String, Object>> classList = new ArrayList<>();
            for (int round = 0; round < PER_PROJECT_CLASS_LIMIT && classList.size() < MAX_CLASS_RESULTS; round++) {
                for (List<Map<String, Object>> hits : hitsByProject) {
                    if (round < hits.size()) {
                        classList.add(hits.get(round));
                        if (classList.size() >= MAX_CLASS_RESULTS) {
                            break;
                        }
                    }
                }
            }
            result.put("classes", classList);
        }

        return result;
    }
}
