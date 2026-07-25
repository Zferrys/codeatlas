package com.codeatlas.server.service;

import com.codeatlas.engine.ai.*;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.AiAuditLogEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgenticSearchService {

    private static final Logger log = LoggerFactory.getLogger(AgenticSearchService.class);
    private static final int MAX_ITERATIONS = 5;

    private final AiClient aiClient;
    private final ToolRegistry toolRegistry;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiAuditLogMapper aiAuditLogMapper;

    public AgenticSearchService(AiClient aiClient, ToolRegistry toolRegistry,
                                ProjectService projectService, ScanService scanService,
                                TokenBudgetManager tokenBudgetManager,
                                AiAuditLogMapper aiAuditLogMapper) {
        this.aiClient = aiClient;
        this.toolRegistry = toolRegistry;
        this.projectService = projectService;
        this.scanService = scanService;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiAuditLogMapper = aiAuditLogMapper;
    }

    /**
     * Agentic Search 入口：通过多轮 tool-calling 自动检索代码库并回答问题。
     *
     * @return Map 包含 answer、iterations、toolsCalled 等字段
     */
    public Map<String, Object> search(Long projectId, String question, Long userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("question", question);

        if (aiClient == null) {
            result.put("answer", "AI 服务未配置，无法执行智能搜索。请检查 API Key 设置。");
            return result;
        }

        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            result.put("answer", "项目不存在");
            return result;
        }

        int totalClasses = 0;
        try {
            totalClasses = scanService.getClassSummaries(projectId).size();
        } catch (Exception ignored) {}

        // token budget
        int estimatedTokens = question.length() * 2 + 4000;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            result.put("answer", "本月 AI Token 配额已用完，请下月再试。");
            return result;
        }

        // build conversation
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildSystemPrompt(project, totalClasses)));
        messages.add(Message.user(question));

        int iterations = 0;
        int totalTokenUsed = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        long totalLatency = 0;
        boolean success = false;
        List<String> toolsCalled = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            iterations = i + 1;

            AiRequest request = new AiRequest();
            request.setMessages(messages);
            request.setTools(toolRegistry.getToolDefs());
            request.setTemperature(0.3);
            request.setMaxTokens(2048);

            AiResponse response;
            try {
                response = aiClient.chat(request);
            } catch (Exception e) {
                log.error("Agentic search AI call failed at iteration {}", iterations, e);
                result.put("answer", "AI 调用失败: " + e.getMessage());
                result.put("iterations", iterations);
                return result;
            }

            if (response == null) {
                result.put("answer", "AI 服务暂不可用（所有模型已熔断），请稍后重试。");
                result.put("iterations", iterations);
                return result;
            }

            totalTokenUsed += response.getTokensUsed();
            totalPromptTokens += response.getPromptTokens();
            totalCompletionTokens += response.getCompletionTokens();
            totalLatency += response.getLatencyMs();

            // check for tool calls
            if (response.hasToolCalls()) {
                // add assistant message with tool calls
                Message assistantMsg = Message.assistant(response.getToolCalls());
                if (response.getContent() != null && !response.getContent().isEmpty()) {
                    assistantMsg.setContent(response.getContent());
                }
                messages.add(assistantMsg);

                // execute tools one by one
                for (ToolCall tc : response.getToolCalls()) {
                    log.info("Agent iteration {}: calling tool {} with args {}",
                            iterations, tc.getName(), tc.getArguments());
                    toolsCalled.add(tc.getName());

                    String toolResult = toolRegistry.execute(tc, projectId);
                    messages.add(Message.tool(tc.getId(), toolResult));
                }
            } else {
                // final answer — no more tool calls
                result.put("answer", response.getContent() != null ? response.getContent() : "(AI 未返回内容)");
                success = true;
                break;
            }
        }

        if (!result.containsKey("answer")) {
            result.put("answer", "分析未能在 " + MAX_ITERATIONS + " 轮内完成，请尝试更具体的问题。");
        }

        result.put("iterations", iterations);
        result.put("toolsCalled", toolsCalled);
        result.put("totalTokens", totalTokenUsed);

        // audit log
        long elapsed = System.currentTimeMillis() - startTime;
        saveAuditLog(aiClient.getModelName(), projectId, userId,
                totalPromptTokens, totalCompletionTokens, totalTokenUsed, elapsed,
                iterations, toolsCalled, success);

        log.info("Agentic search completed: projectId={}, iterations={}, tools={}, tokens={}, latency={}ms",
                projectId, iterations, toolsCalled, totalTokenUsed, elapsed);

        return result;
    }

    private String buildSystemPrompt(Project project, int totalClasses) {
        PromptTemplate template = new PromptTemplate("prompts/agentic-search.md");
        Map<String, String> vars = new HashMap<>();
        vars.put("projectName", project.getName());
        vars.put("language", project.getLanguage() != null ? project.getLanguage() : "Java");
        vars.put("classCount", String.valueOf(totalClasses));
        return template.render(vars);
    }

    private void saveAuditLog(String model, Long projectId, Long userId,
                              int promptTokens, int completionTokens, int totalTokens,
                              long latencyMs, int iterations, List<String> toolsCalled,
                              boolean success) {
        try {
            AiAuditLogEntity logEntity = new AiAuditLogEntity();
            logEntity.setModel(model);
            logEntity.setStage("AGENTIC_SEARCH");
            logEntity.setProjectId(projectId);
            logEntity.setUserId(userId);
            logEntity.setPromptTokens(promptTokens);
            logEntity.setCompletionTokens(completionTokens);
            logEntity.setTotalTokens(totalTokens);
            logEntity.setLatencyMs((int) latencyMs);
            logEntity.setSuccess(success);
            if (totalTokens > 0) {
                double cost = totalTokens / 1_000_000.0 * 8.0;
                logEntity.setCost(java.math.BigDecimal.valueOf(Math.round(cost * 100000.0) / 100000.0));
            }
            if (!success) {
                logEntity.setErrorMessage("Truncated after " + iterations + " iterations, tools="
                        + String.join(",", toolsCalled));
            }
            aiAuditLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("Failed to write agentic search audit log: {}", e.getMessage());
        }
    }
}
