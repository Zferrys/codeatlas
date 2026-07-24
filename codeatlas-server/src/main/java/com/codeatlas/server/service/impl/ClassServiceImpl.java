package com.codeatlas.server.service.impl;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.service.ClassService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ClassServiceImpl implements ClassService {

    private final ClassSummaryMapper classSummaryMapper;

    public ClassServiceImpl(ClassSummaryMapper classSummaryMapper) {
        this.classSummaryMapper = classSummaryMapper;
    }

    @Override
    public Map<String, Object> getClassDetail(Long projectId, String fqn) {
        List<ClassSummaryEntity> all = classSummaryMapper.findByProjectId(projectId);
        ClassSummaryEntity target = all.stream()
                .filter(c -> fqn.equals(c.getFqn()))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return null;
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
        return result;
    }

    @Override
    public List<Map<String, Object>> searchClasses(Long projectId, String keyword, int limit) {
        List<ClassSummaryEntity> entities = classSummaryMapper.searchByKeyword(keyword, limit);
        return entities.stream()
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
    }
}
