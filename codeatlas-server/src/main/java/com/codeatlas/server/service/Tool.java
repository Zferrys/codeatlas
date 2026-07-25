package com.codeatlas.server.service;

import java.util.Map;

/**
 * 工具接口 — Agentic Search 中可供 AI 调用的工具。
 */
public interface Tool {

    /** 工具名称（传给 AI 的 function name） */
    String getName();

    /** 工具描述（AI 用来决定何时调用） */
    String getDescription();

    /** 参数 JSON Schema（给 AI 的参数定义） */
    Map<String, Object> getParametersSchema();

    /** 执行工具并返回结果字符串 */
    String execute(Map<String, Object> arguments, Long projectId);
}
