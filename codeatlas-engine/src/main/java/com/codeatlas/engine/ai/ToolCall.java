package com.codeatlas.engine.ai;

import java.util.Map;

/**
 * 工具调用 — AI 返回的单个工具调用请求。
 */
public class ToolCall {

    private String id;
    private String name;
    private Map<String, Object> arguments;

    public ToolCall() {}

    public ToolCall(String id, String name, Map<String, Object> arguments) {
        this.id = id;
        this.name = name;
        this.arguments = arguments;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    /** 安全获取字符串参数 */
    public String getStringArg(String key) {
        if (arguments == null) return null;
        Object val = arguments.get(key);
        return val != null ? val.toString() : null;
    }
}
