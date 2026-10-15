package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.PromptTemplate;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ImpactAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);
    private static final int MAX_BFS_DEPTH = 5;
    private static final int MAX_INPUT_LENGTH = 2000;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AiAnalysisService aiAnalysisService;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final EvidenceVerifier evidenceVerifier;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiClient aiClient;
    private final ChatHistoryService chatHistoryService;
    private final AiTaskProgressRegistry progressRegistry;

    public ImpactAnalysisService(AiAnalysisService aiAnalysisService,
                                  ProjectService projectService,
                                  ScanService scanService,
                                  EvidenceVerifier evidenceVerifier,
                                  TokenBudgetManager tokenBudgetManager,
                                  @Autowired(required = false) AiClient aiClient,
                                  ChatHistoryService chatHistoryService,
                                  AiTaskProgressRegistry progressRegistry) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.evidenceVerifier = evidenceVerifier;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiClient = aiClient;
        this.chatHistoryService = chatHistoryService;
        this.progressRegistry = progressRegistry;
    }

    /**
     * 变更影响分析：BFS 遍历依赖链 + AI 风险评估。
     */
    public Map<String, Object> analyzeImpact(Long projectId, String targetClass,
                                              String changeType, String targetMethod,
                                              String description, Long userId) {
        return analyzeImpact(projectId, targetClass, changeType, targetMethod, description, userId, null);
    }

    /**
     * @param taskId 前端进度任务 ID；非 null 时推送阶段事件并支持断开即取消
     */
    public Map<String, Object> analyzeImpact(Long projectId, String targetClass,
                                              String changeType, String targetMethod,
                                              String description, Long userId, String taskId) {
        changeType = sanitizeUserInput(changeType);
        targetMethod = sanitizeUserInput(targetMethod);
        description = sanitizeUserInput(description);

        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        ScanRecord scan = scanService.getLatestScanEntity(projectId);
        if (scan == null) {
            throw new BusinessException(400, "请先触发一次扫描");
        }

        List<ClassSummaryEntity> classes = scanService.getClassSummaries(projectId);
        ClassSummaryEntity target = classes.stream()
                .filter(c -> c.getFqn().equals(targetClass) || c.getSimpleName().equals(targetClass))
                .findFirst().orElse(null);

        List<Map<String, Object>> impactPaths = buildImpactPaths(target, classes);
        int totalImpacted = countImpactedClasses(impactPaths);

        Map<String, Object> result = new HashMap<>();
        result.put("targetClass", target != null ? target.getFqn() : targetClass);
        result.put("changeType", changeType);
        result.put("targetMethod", targetMethod);
        result.put("totalImpacted", totalImpacted);
        result.put("impactPaths", impactPaths);
        progressRegistry.publish(taskId, "正在分析依赖影响链路…");

        boolean aiAnswered = false;
        if (target != null) {
            String prompt = buildImpactPrompt(project.getName(), target, classes, impactPaths,
                    changeType, targetMethod, description);
            if (prompt.isEmpty()) {
                log.warn("Impact AI analysis skipped: empty prompt for projectId={}", projectId);
            } else if (aiClient == null) {
                log.info("Impact AI analysis skipped: no AI client for projectId={}", projectId);
            } else if (progressRegistry.isCancelled(taskId)) {
                result.put("aiAnalysis", "本次分析已被用户终止");
                result.put("cancelled", true);
            } else {
                int estimatedTokens = prompt.length() * 2 / 3 + 4096;
                if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
                    log.warn("AI token monthly budget exceeded, rejecting impact analysis for projectId={}", projectId);
                    saveAudit("IMPACT_ANALYSIS", projectId, userId, null, "Monthly token budget exceeded", false);
                    result.put("aiAnalysis", "本月 AI Token 配额已用完，请下月再试。");
                } else {
                    try {
                        AiRequest request = new AiRequest();
                        request.setPrompt(prompt);
                        request.setSystemPrompt(
                                "你是一位资深软件架构师。分析代码变更的影响范围，评估风险，给出建议。用中文回答，保留类名/方法名原文。");
                        // 推理模型 reasoning 与正文共享 max_tokens：4096 档实测被推理 100% 耗尽（双模型空正文），对齐架构模式识别的成功档位
                        request.setMaxTokens(8192);
                        progressRegistry.publish(taskId, "AI 正在评估变更风险…");

                        AiResponse response = aiAnalysisService.callAiWithResilience(request);
                        if (response != null && progressRegistry.isCancelled(taskId)) {
                            // 用户已终止：token 已消耗仍需记账，结果丢弃不落历史；审计口径统一为失败
                            saveAudit("IMPACT_ANALYSIS", projectId, userId, response, "Cancelled by user", false);
                            result.put("aiAnalysis", "本次分析已被用户终止");
                            result.put("cancelled", true);
                        } else if (response != null && response.getContent() != null
                                && !response.getContent().trim().isEmpty()) {
                            result.put("aiAnalysis", response.getContent());
                            saveAudit("IMPACT_ANALYSIS", projectId, userId, response, null, true);
                            aiAnswered = true;
                        } else {
                            // 空正文响应也带 response：已消耗 token 仍需记账
                            saveAudit("IMPACT_ANALYSIS", projectId, userId, response,
                                    "All AI models exhausted or no content", false);
                            result.put("aiAnalysis", "AI 分析暂不可用，以下为静态依赖分析结果");
                        }
                    } catch (Exception e) {
                        log.warn("AI impact analysis failed for projectId={}", projectId, e);
                        // 配额已扣，失败路径也必须落审计；审计表为管理端可见面，只写异常类型不写原始消息（完整堆栈在服务日志）
                        saveAudit("IMPACT_ANALYSIS", projectId, userId, null,
                                "AI call exception: " + e.getClass().getSimpleName(), false);
                        result.put("aiAnalysis", "AI 分析暂不可用，以下为静态依赖分析结果");
                    }
                }
            }
        }

        // 仅记录真实 AI 回答（降级路径不落历史）；verification 列存结果快照供前端还原完整视图
        if (aiAnswered) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("targetClass", result.get("targetClass"));
            snapshot.put("changeType", changeType);
            snapshot.put("targetMethod", targetMethod);
            snapshot.put("description", description);
            snapshot.put("totalImpacted", totalImpacted);
            snapshot.put("impactPaths", impactPaths);
            recordChatHistory(projectId, userId, ChatHistoryService.SCOPE_IMPACT,
                    (String) result.get("targetClass"),
                    buildImpactQuestion((String) result.get("targetClass"), changeType, targetMethod, description),
                    (String) result.get("aiAnalysis"), snapshot);
        }

        return result;
    }

    /**
     * 上下文限定问答：基于项目代码上下文回答用户问题。
     */
    public Map<String, Object> contextualQa(Long projectId, String question, String classFqn, Long userId) {
        return contextualQa(projectId, question, classFqn, userId, null);
    }

    /**
     * @param taskId 前端进度任务 ID；非 null 时推送阶段事件并支持断开即取消
     */
    public Map<String, Object> contextualQa(Long projectId, String question, String classFqn, Long userId, String taskId) {
        question = sanitizeUserInput(question);
        String sanitizedClassFqn = sanitizeUserInput(classFqn);

        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        List<ClassSummaryEntity> classes = scanService.getClassSummaries(projectId);

        ClassSummaryEntity targetClass = null;
        if (!sanitizedClassFqn.isEmpty()) {
            targetClass = classes.stream()
                    .filter(c -> c.getFqn().equals(sanitizedClassFqn))
                    .findFirst().orElse(null);
        }

        Map<String, String> vars = new HashMap<>();
        vars.put("projectName", project.getName());
        vars.put("language", project.getLanguage() != null ? project.getLanguage() : "Java");
        vars.put("contextClass", targetClass != null ? formatClassDetail(targetClass)
                : "- 类名: " + sanitizedClassFqn + "（未在当前扫描结果中找到该类）\n");
        vars.put("relatedDependencies", buildRelatedDependencies(targetClass, classes));
        vars.put("userQuestion", question);

        PromptTemplate template = new PromptTemplate("prompts/context-qa.md");
        String prompt = template.render(vars);

        Map<String, Object> result = new HashMap<>();
        result.put("question", question);
        result.put("classFqn", sanitizedClassFqn);

        if (aiClient == null || prompt.isEmpty()) {
            log.warn("Context QA skipped: aiClient={}, emptyPrompt={}, projectId={}",
                    aiClient == null, prompt.isEmpty(), projectId);
            result.put("answer", "AI 服务暂不可用，请稍后重试");
            return result;
        }

        if (progressRegistry.isCancelled(taskId)) {
            result.put("answer", "本次回答已被用户终止");
            result.put("cancelled", true);
            return result;
        }

        int estimatedTokens = prompt.length() * 2 / 3 + 4096;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            log.warn("AI token monthly budget exceeded, rejecting context QA for projectId={}", projectId);
            saveAudit("CONTEXT_QA", projectId, userId, null, "Monthly token budget exceeded", false);
            result.put("answer", "本月 AI Token 配额已用完，请下月再试。");
            return result;
        }

        try {
            AiRequest request = new AiRequest();
            request.setPrompt(prompt);
            request.setSystemPrompt("你是一位资深软件架构师，正在帮助开发者理解代码库。基于提供的项目上下文准确回答问题。");
            progressRegistry.publish(taskId, "AI 正在组织回答…");

            AiResponse response = aiAnalysisService.callAiWithResilience(request);
            if (response == null || response.getContent() == null || response.getContent().trim().isEmpty()) {
                // 空正文响应也带 response：已消耗 token 仍需记账
                saveAudit("CONTEXT_QA", projectId, userId, response, "All AI models exhausted or no content", false);
                result.put("answer", "AI 服务暂不可用，请稍后重试");
                return result;
            }
            if (progressRegistry.isCancelled(taskId)) {
                // 用户已终止：token 已消耗仍需记账，结果丢弃不落历史；审计口径统一为失败
                saveAudit("CONTEXT_QA", projectId, userId, response, "Cancelled by user", false);
                result.put("answer", "本次回答已被用户终止");
                result.put("cancelled", true);
                return result;
            }

            String answer = response.getContent();
            long totalPromptTokens = response.getPromptTokens();
            long totalCompletionTokens = response.getCompletionTokens();
            long totalTokens = response.getTokensUsed();
            long totalLatency = response.getLatencyMs();
            // 输出侧证据验证：答案对照项目数据与本次注入的上下文
            if (!classes.isEmpty()) {
                try {
                    progressRegistry.publish(taskId, "正在进行证据交叉验证…");
                    List<String> evidence = Collections.singletonList(prompt);
                    EvidenceVerifier.VerificationReport report = evidenceVerifier.verify(answer, evidence, classes);
                    boolean corrected = false;
                    int correctionRounds = 0;
                    if (report.needsCorrection() && !progressRegistry.isCancelled(taskId)) {
                        correctionRounds = 1;
                        progressRegistry.publish(taskId, "发现证据瑕疵，正在自动修正…");
                        AiResponse correctionResponse = runContextCorrection(prompt, answer, report);
                        if (correctionResponse != null) {
                            // 修正轮消耗无论结果是否采纳都必须记账（累计进入提交点审计行）
                            totalPromptTokens += correctionResponse.getPromptTokens();
                            totalCompletionTokens += correctionResponse.getCompletionTokens();
                            totalTokens += correctionResponse.getTokensUsed();
                            totalLatency += correctionResponse.getLatencyMs();
                            String correctedAnswer = correctionResponse.getContent();
                            if (correctedAnswer != null && !correctedAnswer.trim().isEmpty()) {
                                EvidenceVerifier.VerificationReport recheck =
                                        evidenceVerifier.verify(correctedAnswer, evidence, classes);
                                if (recheck.anomalyCount() < report.anomalyCount()) {
                                    answer = correctedAnswer;
                                    report = recheck;
                                    corrected = true;
                                    log.info("Context QA evidence correction adopted for projectId={}", projectId);
                                }
                            }
                        }
                    }
                    Map<String, Object> verification = report.toMap();
                    verification.put("corrected", corrected);
                    verification.put("correctionRounds", correctionRounds);
                    result.put("verification", verification);
                } catch (Exception ve) {
                    log.warn("Context QA evidence verification failed (non-fatal): {}", ve.getMessage());
                }
            }
            if (progressRegistry.isCancelled(taskId)) {
                // 用户已终止（含证据校验/修正期间）：主调用与修正轮 token 一并记账；审计口径统一为失败
                saveAudit("CONTEXT_QA", projectId, userId, totalPromptTokens, totalCompletionTokens,
                        totalTokens, totalLatency, "Cancelled by user", false);
                result.put("answer", "本次回答已被用户终止");
                result.put("cancelled", true);
                return result;
            }
            // 提交点：审计行与返回紧邻执行，此后的终止不再影响交付
            saveAudit("CONTEXT_QA", projectId, userId, totalPromptTokens, totalCompletionTokens,
                    totalTokens, totalLatency, null, true);
            result.put("answer", answer);
            recordChatHistory(projectId, userId, ChatHistoryService.SCOPE_CLASS, sanitizedClassFqn,
                    question, answer, result.get("verification"));
            return result;
        } catch (Exception e) {
            log.error("Context QA failed for projectId={}", projectId, e);
            // 配额已扣，失败路径也必须落审计；审计表为管理端可见面，只写异常类型不写原始消息（完整堆栈在服务日志）
            saveAudit("CONTEXT_QA", projectId, userId, null, "AI call exception: " + e.getClass().getSimpleName(), false);
            throw new BusinessException(500, "AI 问答服务暂时不可用，请稍后重试");
        }
    }

    /**
     * 上下文问答修正轮：把证据校验异常清单回给模型，低温重写。
     * 返回完整响应（含 token 记账信息），调用方负责累计消耗并记账。
     */
    private AiResponse runContextCorrection(String originalPrompt, String answer,
                                            EvidenceVerifier.VerificationReport report) {
        try {
            AiRequest correctionRequest = new AiRequest();
            correctionRequest.setPrompt(originalPrompt + "\n\n## 你上一条回答\n" + answer
                    + "\n\n## 修正请求\n" + evidenceVerifier.buildCorrectionPrompt(report));
            correctionRequest.setSystemPrompt("你是一位资深软件架构师，正在帮助开发者理解代码库。基于提供的项目上下文准确回答问题。");
            correctionRequest.setTemperature(0.1);
            return aiAnalysisService.callAiWithResilience(correctionRequest);
        } catch (Exception e) {
            log.warn("Context QA correction round failed: {}", e.getMessage());
            return null;
        }
    }

    // ========== private helpers ==========

    private List<Map<String, Object>> buildImpactPaths(ClassSummaryEntity target,
                                                        List<ClassSummaryEntity> allClasses) {
        List<Map<String, Object>> paths = new ArrayList<>();
        if (target == null) return paths;

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        visited.add(target.getFqn());
        queue.add(target.getFqn());
        int depth = 0;

        while (!queue.isEmpty() && depth < MAX_BFS_DEPTH) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String currentFqn = queue.poll();
                for (ClassSummaryEntity cls : allClasses) {
                    if (visited.contains(cls.getFqn())) continue;
                    List<String> deps = aiAnalysisService.parseDeps(cls.getDependencies());
                    if (deps.contains(currentFqn)) {
                        visited.add(cls.getFqn());
                        queue.add(cls.getFqn());
                        Map<String, Object> pathEntry = new HashMap<>();
                        pathEntry.put("source", aiAnalysisService.shorten(cls.getFqn()));
                        pathEntry.put("sourceFqn", cls.getFqn());
                        pathEntry.put("target", aiAnalysisService.shorten(currentFqn));
                        pathEntry.put("targetFqn", currentFqn);
                        pathEntry.put("depth", depth + 1);
                        pathEntry.put("sourceLayer", cls.getLayer());
                        paths.add(pathEntry);
                    }
                }
            }
            depth++;
        }

        return paths;
    }

    private int countImpactedClasses(List<Map<String, Object>> paths) {
        Set<String> classes = new HashSet<>();
        for (Map<String, Object> p : paths) {
            classes.add((String) p.get("sourceFqn"));
            classes.add((String) p.get("targetFqn"));
        }
        return classes.size();
    }

    private String buildImpactPrompt(String projectName, ClassSummaryEntity target,
                                      List<ClassSummaryEntity> allClasses,
                                      List<Map<String, Object>> impactPaths,
                                      String changeType, String targetMethod, String description) {
        Map<String, String> vars = new HashMap<>();
        vars.put("projectName", projectName);
        vars.put("classCount", String.valueOf(allClasses.size()));
        vars.put("targetClass", formatClassDetail(target));
        vars.put("changeType", changeType != null && !changeType.isEmpty() ? changeType : "MODIFY_METHOD");
        vars.put("targetMethod", targetMethod != null && !targetMethod.isEmpty() ? targetMethod : "未指定");
        vars.put("changeDescription", description != null && !description.isEmpty() ? description : "未提供");
        vars.put("dependencyHighlights", formatImpactPaths(impactPaths));
        PromptTemplate template = new PromptTemplate("prompts/impact-analysis.md");
        return template.render(vars);
    }

    private String formatClassDetail(ClassSummaryEntity cls) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 类名: ").append(cls.getFqn()).append("\n");
        sb.append("- 类型: ").append(cls.getClassType()).append("\n");
        sb.append("- 分层: ").append(cls.getLayer()).append("\n");
        sb.append("- 方法数: ").append(cls.getTotalMethods()).append("\n");
        sb.append("- public 方法数: ").append(cls.getPublicMethods()).append("\n");
        sb.append("- 行数: ").append(cls.getLineCount()).append("\n");
        List<String> methodNames = aiAnalysisService.parseDeps(cls.getMethodNames());
        if (!methodNames.isEmpty()) {
            sb.append("- 方法列表: ").append(String.join(", ", methodNames)).append("\n");
        }
        return sb.toString();
    }

    private String buildRelatedDependencies(ClassSummaryEntity targetClass,
                                            List<ClassSummaryEntity> classes) {
        if (targetClass == null) {
            return "（未指定具体类）\n";
        }
        List<String> deps = aiAnalysisService.parseDeps(targetClass.getDependencies());
        StringBuilder sb = new StringBuilder();
        for (String dep : deps) {
            ClassSummaryEntity depClass = classes.stream()
                    .filter(c -> c.getFqn().equals(dep))
                    .findFirst().orElse(null);
            if (depClass != null) {
                sb.append("- ").append(depClass.getSimpleName())
                        .append(" (").append(depClass.getLayer()).append(")\n");
            }
        }
        return sb.length() > 0 ? sb.toString() : "（无项目内直接依赖）\n";
    }

    private String formatImpactPaths(List<Map<String, Object>> impactPaths) {
        if (impactPaths.isEmpty()) {
            return "（BFS 未发现依赖待修改类的其他类）\n";
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> path : impactPaths) {
            sb.append("- ").append(path.get("source")).append(" → ").append(path.get("target"))
                    .append(" (depth=").append(path.get("depth")).append(")\n");
        }
        return sb.toString();
    }

    private void saveAudit(String stage, Long projectId, Long userId,
                           AiResponse response, String errorMessage, boolean success) {
        saveAudit(stage, projectId, userId,
                response != null ? response.getPromptTokens() : 0,
                response != null ? response.getCompletionTokens() : 0,
                response != null ? response.getTokensUsed() : 0,
                response != null ? response.getLatencyMs() : 0,
                errorMessage, success);
    }

    private void saveAudit(String stage, Long projectId, Long userId,
                           long promptTokens, long completionTokens, long totalTokens, long latencyMs,
                           String errorMessage, boolean success) {
        aiAnalysisService.saveAiAuditLog(aiClient.getModelName(), stage, projectId, userId,
                promptTokens, completionTokens, totalTokens, latencyMs, errorMessage, success);
    }

    private void recordChatHistory(Long projectId, Long userId, String scope, String contextFqn,
                                   String question, String answer, Object verification) {
        try {
            ChatHistoryEntity entity = new ChatHistoryEntity();
            entity.setProjectId(projectId);
            entity.setUserId(userId);
            entity.setScope(scope);
            entity.setContextFqn(contextFqn);
            entity.setQuestion(question);
            entity.setAnswer(answer);
            if (verification != null) {
                entity.setVerification(OBJECT_MAPPER.writeValueAsString(verification));
            }
            chatHistoryService.record(entity);
        } catch (Exception e) {
            log.warn("Failed to record chat history (non-fatal): {}", e.getMessage());
        }
    }

    private String buildImpactQuestion(String targetClass, String changeType,
                                       String targetMethod, String description) {
        StringBuilder sb = new StringBuilder("变更影响分析: ")
                .append(targetClass != null ? targetClass : "未指定");
        if (changeType != null && !changeType.isEmpty()) {
            sb.append(" [").append(changeType).append("]");
        }
        if (targetMethod != null && !targetMethod.isEmpty()) {
            sb.append(" 方法: ").append(targetMethod);
        }
        if (description != null && !description.isEmpty()) {
            sb.append(" — ").append(description);
        }
        return sb.toString();
    }

    /**
     * Prompt 注入防护: 限长、过滤控制字符、过滤已知注入模式。
     */
    private String sanitizeUserInput(String input) {
        if (input == null) return "";
        String sanitized = input.length() > MAX_INPUT_LENGTH ? input.substring(0, MAX_INPUT_LENGTH) : input;
        sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        sanitized = sanitized
                .replaceAll("(?i)ignore (all )?(previous|above|prior) instructions?\\s*[:.]?", "[FILTERED]")
                .replaceAll("(?i)system prompt[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)you are now[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)new instructions?[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)forget (everything|all)[\\s:]*", "[FILTERED]");
        return sanitized;
    }
}
