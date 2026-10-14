package com.codeatlas.server.service;

import com.codeatlas.common.util.AiPromptSanitizer;
import com.codeatlas.engine.ai.*;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.AiAuditLogEntity;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AgenticSearchService {

    private static final Logger log = LoggerFactory.getLogger(AgenticSearchService.class);
    private static final int MAX_ITERATIONS = 5;
    private static final int CORRECTION_TOKEN_ESTIMATE = 3000;
    private static final String NO_CONTENT_PLACEHOLDER = "(AI 未返回内容)";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiClient aiClient;
    private final ToolRegistry toolRegistry;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiAuditLogMapper aiAuditLogMapper;
    private final EvidenceVerifier evidenceVerifier;
    private final ChatHistoryService chatHistoryService;
    private final AiTaskProgressRegistry progressRegistry;

    public AgenticSearchService(AiClient aiClient, ToolRegistry toolRegistry,
                                ProjectService projectService, ScanService scanService,
                                TokenBudgetManager tokenBudgetManager,
                                AiAuditLogMapper aiAuditLogMapper,
                                EvidenceVerifier evidenceVerifier,
                                ChatHistoryService chatHistoryService,
                                AiTaskProgressRegistry progressRegistry) {
        this.aiClient = aiClient;
        this.toolRegistry = toolRegistry;
        this.projectService = projectService;
        this.scanService = scanService;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiAuditLogMapper = aiAuditLogMapper;
        this.evidenceVerifier = evidenceVerifier;
        this.chatHistoryService = chatHistoryService;
        this.progressRegistry = progressRegistry;
    }

    /**
     * Agentic Search 入口：通过多轮 tool-calling 自动检索代码库并回答问题。
     *
     * @return Map 包含 answer、iterations、toolsCalled 等字段
     */
    public Map<String, Object> search(Long projectId, String question, Long userId) {
        return search(projectId, question, userId, null);
    }

    /**
     * @param taskId 前端进度任务 ID；非 null 时推送阶段事件并支持断开即取消
     */
    public Map<String, Object> search(Long projectId, String question, Long userId, String taskId) {
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

        List<ClassSummaryEntity> classSummaries = Collections.emptyList();
        try {
            List<ClassSummaryEntity> loaded = scanService.getClassSummaries(projectId);
            if (loaded != null) classSummaries = loaded;
        } catch (Exception ignored) {}
        int totalClasses = classSummaries.size();

        // 输入净化：防止提示注入
        String sanitizedQuestion = AiPromptSanitizer.sanitize(question);
        if (sanitizedQuestion.isEmpty()) {
            result.put("answer", "问题内容无效（净化后为空），请重新输入。");
            return result;
        }

        // token budget
        int estimatedTokens = sanitizedQuestion.length() * 2 + 4000;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            result.put("answer", "本月 AI Token 配额已用完，请下月再试。");
            return result;
        }
        progressRegistry.publish(taskId, "正在分析问题，规划检索策略…");

        // build conversation
        List<Message> messages = new ArrayList<>();
        messages.add(Message.system(buildSystemPrompt(project, totalClasses)));
        messages.add(Message.user(sanitizedQuestion));

        int iterations = 0;
        int totalTokenUsed = 0;
        int totalPromptTokens = 0;
        int totalCompletionTokens = 0;
        long totalLatency = 0;
        boolean success = false;
        List<String> toolsCalled = new ArrayList<>();
        List<String> evidence = new ArrayList<>();

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            iterations = i + 1;

            if (progressRegistry.isCancelled(taskId)) {
                return finishCancelled(result, projectId, userId, startTime, iterations,
                        toolsCalled, totalPromptTokens, totalCompletionTokens, totalTokenUsed);
            }
            progressRegistry.publish(taskId, "第 " + iterations + " 轮：AI 正在分析并选择检索动作…");

            AiRequest request = new AiRequest();
            request.setMessages(messages);
            request.setTools(toolRegistry.getToolDefs());
            request.setTemperature(0.3);
            request.setMaxTokens(4096);

            AiResponse response;
            try {
                response = aiClient.chat(request);
            } catch (Exception e) {
                log.error("Agentic search AI call failed at iteration {}", iterations, e);
                // 异常细节只进日志不回传前端；配额已扣，失败路径也必须落审计
                result.put("answer", "AI 调用失败，请稍后重试。");
                result.put("iterations", iterations);
                saveAuditLog(aiClient.getModelName(), projectId, userId,
                        totalPromptTokens, totalCompletionTokens, totalTokenUsed,
                        System.currentTimeMillis() - startTime, iterations, toolsCalled,
                        "AI call failed at iteration " + iterations, false);
                return result;
            }

            if (response == null) {
                // 配额已扣，失败路径也必须落审计
                result.put("answer", "AI 服务暂不可用（所有模型已熔断），请稍后重试。");
                result.put("iterations", iterations);
                saveAuditLog(aiClient.getModelName(), projectId, userId,
                        totalPromptTokens, totalCompletionTokens, totalTokenUsed,
                        System.currentTimeMillis() - startTime, iterations, toolsCalled,
                        "All AI models exhausted or unavailable", false);
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
                if (response.getContent() != null && !response.getContent().trim().isEmpty()) {
                    assistantMsg.setContent(response.getContent());
                }
                messages.add(assistantMsg);

                // execute tools one by one
                for (ToolCall tc : response.getToolCalls()) {
                    log.info("Agent iteration {}: calling tool {} with args {}",
                            iterations, tc.getName(), tc.getArguments());
                    toolsCalled.add(tc.getName());

                    String toolResult = toolRegistry.execute(tc, projectId);
                    evidence.add(toolResult);
                    messages.add(Message.tool(tc.getId(), toolResult));
                }
                progressRegistry.publish(taskId, "第 " + iterations + " 轮：已检索到 "
                        + response.getToolCalls().size() + " 项代码证据，继续分析…");
            } else {
                // final answer — no more tool calls
                String finalContent = response.getContent();
                result.put("answer", finalContent != null && !finalContent.trim().isEmpty()
                        ? finalContent : NO_CONTENT_PLACEHOLDER);
                success = true;
                break;
            }
        }

        if (!success) {
            if (progressRegistry.isCancelled(taskId)) {
                return finishCancelled(result, projectId, userId, startTime, iterations,
                        toolsCalled, totalPromptTokens, totalCompletionTokens, totalTokenUsed);
            }
            // 工具轮次用尽模型仍在请求工具：去掉工具再追加一轮强制总结，保证用户拿到答案
            progressRegistry.publish(taskId, "已达检索轮次上限，正在汇总已获取的信息…");
            messages.add(Message.user(
                    "已达到工具调用轮次上限，请立即基于以上已获取的信息直接给出最终回答，不要再请求调用工具。"));
            AiRequest finalRequest = new AiRequest();
            finalRequest.setMessages(messages);
            finalRequest.setTemperature(0.3);
            finalRequest.setMaxTokens(4096);
            try {
                AiResponse finalResponse = aiClient.chat(finalRequest);
                if (finalResponse != null) {
                    totalTokenUsed += finalResponse.getTokensUsed();
                    totalPromptTokens += finalResponse.getPromptTokens();
                    totalCompletionTokens += finalResponse.getCompletionTokens();
                    if (finalResponse.getContent() != null && !finalResponse.getContent().trim().isEmpty()) {
                        result.put("answer", finalResponse.getContent());
                        success = true;
                        log.info("Agentic search forced final synthesis succeeded after {} iterations", iterations);
                    }
                }
            } catch (Exception e) {
                log.warn("Agentic search forced final synthesis failed: {}", e.getMessage());
            }
        }

        if (!result.containsKey("answer")) {
            result.put("answer", "分析未能在 " + MAX_ITERATIONS + " 轮内完成，请尝试更具体的问题。");
        }

        if (success && progressRegistry.isCancelled(taskId)) {
            return finishCancelled(result, projectId, userId, startTime, iterations,
                    toolsCalled, totalPromptTokens, totalCompletionTokens, totalTokenUsed);
        }

        // 输出侧多源交叉验证 + 自动修正轮
        if (success && result.get("answer") instanceof String && !classSummaries.isEmpty()) {
            try {
                progressRegistry.publish(taskId, "正在进行证据交叉验证…");
                EvidenceVerifier.VerificationReport report = evidenceVerifier.verify(
                        (String) result.get("answer"), evidence, classSummaries);
                boolean corrected = false;
                int correctionRounds = 0;

                if (report.needsCorrection() && !progressRegistry.isCancelled(taskId)) {
                    int anomalyBefore = report.anomalyCount();
                    if (tokenBudgetManager.tryConsume(CORRECTION_TOKEN_ESTIMATE)) {
                        correctionRounds = 1;
                        try {
                            progressRegistry.publish(taskId, "发现证据瑕疵，正在自动修正…");
                            List<Message> correctionMessages = new ArrayList<>(messages);
                            correctionMessages.add(Message.assistant((String) result.get("answer")));
                            correctionMessages.add(Message.user(evidenceVerifier.buildCorrectionPrompt(report)));
                            AiRequest correctionRequest = new AiRequest();
                            correctionRequest.setMessages(correctionMessages);
                            correctionRequest.setTemperature(0.1);
                            correctionRequest.setMaxTokens(4096);
                            AiResponse correctionResponse = aiClient.chat(correctionRequest);
                            if (correctionResponse != null && correctionResponse.getContent() != null
                                    && !correctionResponse.getContent().trim().isEmpty()) {
                                totalTokenUsed += correctionResponse.getTokensUsed();
                                totalPromptTokens += correctionResponse.getPromptTokens();
                                totalCompletionTokens += correctionResponse.getCompletionTokens();
                                totalLatency += correctionResponse.getLatencyMs();
                                EvidenceVerifier.VerificationReport recheck = evidenceVerifier.verify(
                                        correctionResponse.getContent(), evidence, classSummaries);
                                if (recheck.anomalyCount() < anomalyBefore) {
                                    result.put("answer", correctionResponse.getContent());
                                    report = recheck;
                                    corrected = true;
                                    log.info("Evidence correction adopted: anomalies {} -> {}",
                                            anomalyBefore, recheck.anomalyCount());
                                } else {
                                    log.warn("Evidence correction did not improve answer, keeping original");
                                }
                            }
                        } catch (Exception ce) {
                            log.warn("Evidence correction round failed: {}", ce.getMessage());
                        }
                    } else {
                        log.warn("Evidence correction skipped: token budget exceeded");
                    }
                }

                Map<String, Object> verification = report.toMap();
                verification.put("corrected", corrected);
                verification.put("correctionRounds", correctionRounds);
                result.put("verification", verification);
                log.info("Agentic search evidence verification: unsourced={}, relationMismatches={}, corrected={}",
                        report.getUnsourced(), report.getRelationMismatches(), corrected);
            } catch (Exception e) {
                log.warn("Evidence verification failed (non-fatal): {}", e.getMessage());
            }
        }

        if (success && progressRegistry.isCancelled(taskId)) {
            // 证据校验/修正期间被终止：回答丢弃，全部已消耗 token（含修正轮）记账
            return finishCancelled(result, projectId, userId, startTime, iterations,
                    toolsCalled, totalPromptTokens, totalCompletionTokens, totalTokenUsed);
        }

        result.put("iterations", iterations);
        result.put("toolsCalled", toolsCalled);
        result.put("totalTokens", totalTokenUsed);

        // 提交点：落历史与审计紧邻执行，此后的终止不再影响交付；仅记录真实 AI 回答（降级/失败路径不落历史）
        if (success) {
            recordChatHistory(projectId, userId, question, result);
        }

        // audit log
        long elapsed = System.currentTimeMillis() - startTime;
        saveAuditLog(aiClient.getModelName(), projectId, userId,
                totalPromptTokens, totalCompletionTokens, totalTokenUsed, elapsed,
                iterations, toolsCalled,
                success ? null : "Truncated after " + iterations + " iterations, tools="
                        + String.join(",", toolsCalled),
                success);

        log.info("Agentic search completed: projectId={}, iterations={}, tools={}, tokens={}, latency={}ms",
                projectId, iterations, toolsCalled, totalTokenUsed, elapsed);

        return result;
    }

    /** 用户取消后的统一收尾：丢弃回答、落失败审计（已消耗 token 仍需记账），不落历史 */
    private Map<String, Object> finishCancelled(Map<String, Object> result, Long projectId, Long userId,
                                                long startTime, int iterations, List<String> toolsCalled,
                                                int totalPromptTokens, int totalCompletionTokens, int totalTokenUsed) {
        result.put("answer", "本次回答已被用户终止");
        result.put("cancelled", true);
        result.put("iterations", iterations);
        saveAuditLog(aiClient.getModelName(), projectId, userId,
                totalPromptTokens, totalCompletionTokens, totalTokenUsed,
                System.currentTimeMillis() - startTime, iterations, toolsCalled,
                "Cancelled by user", false);
        return result;
    }

    private void recordChatHistory(Long projectId, Long userId, String question, Map<String, Object> result) {
        try {
            Object answer = result.get("answer");
            String answerText = answer != null ? answer.toString() : null;
            if (answerText == null || answerText.trim().isEmpty() || NO_CONTENT_PLACEHOLDER.equals(answerText)) {
                // 模型未产出真实回答（空串/占位文案）时不落历史
                return;
            }
            ChatHistoryEntity entity = new ChatHistoryEntity();
            entity.setProjectId(projectId);
            entity.setUserId(userId);
            entity.setScope(ChatHistoryService.SCOPE_GLOBAL);
            entity.setQuestion(question);
            entity.setAnswer(answerText);
            Object verification = result.get("verification");
            if (verification != null) {
                entity.setVerification(OBJECT_MAPPER.writeValueAsString(verification));
            }
            chatHistoryService.record(entity);
        } catch (Exception e) {
            log.warn("Failed to record chat history (non-fatal): {}", e.getMessage());
        }
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
                              String errorMessage, boolean success) {
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
            logEntity.setErrorMessage(errorMessage);
            if (totalTokens > 0) {
                double cost = totalTokens / 1_000_000.0 * 8.0;
                logEntity.setCost(java.math.BigDecimal.valueOf(Math.round(cost * 100000.0) / 100000.0));
            }
            aiAuditLogMapper.insert(logEntity);
        } catch (Exception e) {
            log.warn("Failed to write agentic search audit log: {}", e.getMessage());
        }
    }
}
