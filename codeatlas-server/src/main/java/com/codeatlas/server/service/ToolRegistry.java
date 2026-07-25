package com.codeatlas.server.service;

import com.codeatlas.engine.ai.ToolCall;
import com.codeatlas.engine.ai.ToolDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 工具注册表 — 自动发现所有 Tool 实现，提供 AI 工具定义和执行能力。
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> toolList) {
        this.tools = new LinkedHashMap<>();
        for (Tool tool : toolList) {
            tools.put(tool.getName(), tool);
            log.info("Registered tool: {} — {}", tool.getName(), tool.getDescription());
        }
    }

    /** 返回所有工具的 ToolDef 列表，用于传给 AI */
    public List<ToolDef> getToolDefs() {
        return tools.values().stream()
                .map(t -> new ToolDef(t.getName(), t.getDescription(), t.getParametersSchema()))
                .collect(Collectors.toList());
    }

    /** 执行单个工具调用并返回结果字符串 */
    public String execute(ToolCall toolCall, Long projectId) {
        Tool tool = tools.get(toolCall.getName());
        if (tool == null) {
            return "(错误) 未知工具: " + toolCall.getName();
        }
        try {
            log.info("Executing tool: {} with args: {}", toolCall.getName(), toolCall.getArguments());
            return tool.execute(toolCall.getArguments(), projectId);
        } catch (Exception e) {
            log.error("Tool execution failed: {}", toolCall.getName(), e);
            return "(错误) 工具执行失败: " + e.getMessage();
        }
    }
}
