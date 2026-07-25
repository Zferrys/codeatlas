package com.codeatlas.engine.ai;

import java.util.Map;

/**
 * 工具定义 — 描述一个可供 AI 调用的工具（符合 OpenAI/Anthropic function schema）。
 */
public class ToolDef {

    private String name;
    private String description;
    private Map<String, Object> parameters;

    public ToolDef() {}

    public ToolDef(String name, String description, Map<String, Object> parameters) {
        this.name = name;
        this.description = description;
        this.parameters = parameters;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Map<String, Object> getParameters() { return parameters; }
    public void setParameters(Map<String, Object> parameters) { this.parameters = parameters; }
}
