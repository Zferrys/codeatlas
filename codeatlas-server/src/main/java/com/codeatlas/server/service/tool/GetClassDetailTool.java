package com.codeatlas.server.service.tool;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.service.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GetClassDetailTool implements Tool {

    private final ClassSummaryMapper classSummaryMapper;

    public GetClassDetailTool(ClassSummaryMapper classSummaryMapper) {
        this.classSummaryMapper = classSummaryMapper;
    }

    @Override
    public String getName() {
        return "get_class_detail";
    }

    @Override
    public String getDescription() {
        return "获取指定类的详细信息，包括分层、方法数、行数、注解列表、依赖列表。参数 fqn 为类的全限定名。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> fqn = new LinkedHashMap<>();
        fqn.put("type", "string");
        fqn.put("description", "类的全限定名（FQN），如 com.example.service.PaymentService");
        props.put("fqn", fqn);

        schema.put("properties", props);
        schema.put("required", Collections.singletonList("fqn"));
        return schema;
    }

    @Override
    public String execute(Map<String, Object> arguments, Long projectId) {
        String fqn = arguments != null ? (String) arguments.get("fqn") : null;
        if (fqn == null || fqn.trim().isEmpty()) {
            return "(错误) 请提供 fqn 参数";
        }

        ClassSummaryEntity cls = classSummaryMapper.findByProjectIdAndFqn(projectId, fqn.trim());
        if (cls == null) {
            return "(无结果) 未找到类 \"" + fqn + "\"，请检查类名是否正确";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("类详情:\n");
        sb.append("- 全限定名: ").append(cls.getFqn()).append("\n");
        sb.append("- 简单类名: ").append(cls.getSimpleName()).append("\n");
        sb.append("- 包名: ").append(cls.getPackageName()).append("\n");
        sb.append("- 类型: ").append(cls.getClassType() != null ? cls.getClassType() : "class").append("\n");
        sb.append("- 分层: ").append(cls.getLayer() != null ? cls.getLayer() : "unknown").append("\n");
        sb.append("- 公开方法数: ").append(cls.getPublicMethods() != null ? cls.getPublicMethods() : 0).append("\n");
        sb.append("- 总方法数: ").append(cls.getTotalMethods() != null ? cls.getTotalMethods() : 0).append("\n");
        sb.append("- 代码行数: ").append(cls.getLineCount() != null ? cls.getLineCount() : 0).append("\n");

        if (cls.getAnnotations() != null && !cls.getAnnotations().isEmpty() && !"[]".equals(cls.getAnnotations())) {
            sb.append("- 注解: ").append(cls.getAnnotations()).append("\n");
        }

        if (cls.getDependencies() != null && !cls.getDependencies().isEmpty() && !"[]".equals(cls.getDependencies())) {
            sb.append("- 依赖列表: ").append(cls.getDependencies()).append("\n");
        }

        if (cls.getModuleName() != null && !cls.getModuleName().isEmpty()) {
            sb.append("- 所属模块: ").append(cls.getModuleName()).append("\n");
        }

        return sb.toString();
    }
}
