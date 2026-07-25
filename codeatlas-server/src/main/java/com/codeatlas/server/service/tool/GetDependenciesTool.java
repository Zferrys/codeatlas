package com.codeatlas.server.service.tool;

import com.codeatlas.server.service.Neo4jGraphService;
import com.codeatlas.server.service.Tool;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class GetDependenciesTool implements Tool {

    private final Neo4jGraphService neo4jGraphService;

    public GetDependenciesTool(Neo4jGraphService neo4jGraphService) {
        this.neo4jGraphService = neo4jGraphService;
    }

    @Override
    public String getName() {
        return "get_dependencies";
    }

    @Override
    public String getDescription() {
        return "查询指定类的直接依赖关系。direction 参数: outgoing（该类依赖谁）、incoming（谁依赖该类）、both（双向）。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> fqn = new LinkedHashMap<>();
        fqn.put("type", "string");
        fqn.put("description", "类的全限定名");
        props.put("fqn", fqn);

        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "string");
        direction.put("enum", Arrays.asList("outgoing", "incoming", "both"));
        direction.put("description", "依赖方向，默认为 both");
        props.put("direction", direction);

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
        String direction = arguments != null ? (String) arguments.get("direction") : "both";
        if (direction == null) direction = "both";

        return neo4jGraphService.queryDirectNeighbors(projectId, fqn.trim(), direction);
    }
}
