package com.codeatlas.server.service.tool;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.service.Tool;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SearchClassesTool implements Tool {

    private final ClassSummaryMapper classSummaryMapper;

    public SearchClassesTool(ClassSummaryMapper classSummaryMapper) {
        this.classSummaryMapper = classSummaryMapper;
    }

    @Override
    public String getName() {
        return "search_classes";
    }

    @Override
    public String getDescription() {
        return "通过关键词搜索类名（FQN 或简单类名）。返回匹配的类列表，包含完整类名、分层、方法数、行数。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "搜索关键词，支持类名或包名的一部分");
        props.put("keyword", keyword);

        schema.put("properties", props);
        schema.put("required", java.util.Collections.singletonList("keyword"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, Long projectId) {
        String keyword = arguments != null ? (String) arguments.get("keyword") : null;
        if (keyword == null || keyword.trim().isEmpty()) {
            return "(无结果) 请提供 keyword 参数";
        }

        List<ClassSummaryEntity> raw = classSummaryMapper.searchByKeyword(keyword.trim(), 100);
        List<ClassSummaryEntity> results = raw.stream()
                .filter(c -> projectId.equals(c.getProjectId()))
                .collect(java.util.stream.Collectors.toList());
        if (results.isEmpty()) {
            return "(无结果) 未找到包含 \"" + keyword + "\" 的类，尝试换一个关键词";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("找到 ").append(results.size()).append(" 个匹配类:\n");
        for (int i = 0; i < Math.min(results.size(), 10); i++) {
            ClassSummaryEntity c = results.get(i);
            sb.append(i + 1).append(". ").append(c.getFqn())
                    .append(" [").append(c.getLayer() != null ? c.getLayer() : "unknown").append("]")
                    .append(" 方法").append(c.getTotalMethods() != null ? c.getTotalMethods() : 0)
                    .append(" 行").append(c.getLineCount() != null ? c.getLineCount() : 0)
                    .append("\n");
        }
        return sb.toString();
    }
}
