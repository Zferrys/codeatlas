package com.codeatlas.server.service.tool;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.service.Tool;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class GetLayerSummaryTool implements Tool {

    private final ClassSummaryMapper classSummaryMapper;

    public GetLayerSummaryTool(ClassSummaryMapper classSummaryMapper) {
        this.classSummaryMapper = classSummaryMapper;
    }

    @Override
    public String getName() {
        return "get_layer_summary";
    }

    @Override
    public String getDescription() {
        return "获取项目的分层架构概览，包括每层的类数量、关键类列表。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.emptyMap());
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, Long projectId) {
        List<ClassSummaryEntity> classes = classSummaryMapper.findByProjectId(projectId);
        if (classes == null || classes.isEmpty()) {
            return "(无数据) 该项目暂无类数据，请先执行扫描";
        }

        Map<String, List<ClassSummaryEntity>> byLayer = classes.stream()
                .collect(Collectors.groupingBy(c -> c.getLayer() != null ? c.getLayer() : "unknown"));

        StringBuilder sb = new StringBuilder();
        sb.append("项目架构概览: 共 ").append(classes.size()).append(" 个类，")
                .append(byLayer.size()).append(" 个分层\n\n");

        for (Map.Entry<String, List<ClassSummaryEntity>> entry : byLayer.entrySet()) {
            String layer = entry.getKey();
            List<ClassSummaryEntity> layerClasses = entry.getValue();
            sb.append("【").append(layer).append("】").append(layerClasses.size()).append(" 个类\n");

            List<ClassSummaryEntity> top = layerClasses.stream()
                    .sorted((a, b) -> Integer.compare(
                            b.getTotalMethods() != null ? b.getTotalMethods() : 0,
                            a.getTotalMethods() != null ? a.getTotalMethods() : 0))
                    .limit(5)
                    .collect(Collectors.toList());

            for (ClassSummaryEntity c : top) {
                sb.append("  - ").append(c.getSimpleName())
                        .append(" (方法").append(c.getTotalMethods() != null ? c.getTotalMethods() : 0)
                        .append(", ").append(c.getLineCount() != null ? c.getLineCount() : 0).append("行)\n");
            }
            if (layerClasses.size() > 5) {
                sb.append("  ... 及其他 ").append(layerClasses.size() - 5).append(" 个类\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
