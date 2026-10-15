package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.AiException;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.PromptTemplate;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 三种 AI 洞察分析（架构模式识别/反模式检测/规约合规检查）的公共骨架，与架构叙事同口径：
 * 前置校验 → 提示词渲染 → Token 预算闸门 → AI 调用 → 审计 → 生成侧防幻觉（可选重试）→ 落 insight 表。
 * 子类只提供差异钩子（阶段名/模板/系统提示词/maxTokens/洞察类型与标题/日志标签），日志与指标口径保持一致。
 */
public abstract class AbstractInsightAnalysisService {

    protected static final int MAX_CLASSES_PER_CHUNK = 200;

    /** 实例 logger：子类名称即日志来源，保持各服务原有 logger 名不变 */
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final AiAnalysisService aiAnalysisService;
    protected final ProjectService projectService;
    protected final ScanService scanService;
    protected final InsightService insightService;
    protected final HallucinationChecker hallucinationChecker;
    protected final TokenBudgetManager tokenBudgetManager;
    protected final AiClient aiClient;
    protected final AiTaskProgressRegistry progressRegistry;

    protected AbstractInsightAnalysisService(AiAnalysisService aiAnalysisService,
                                             ProjectService projectService,
                                             ScanService scanService,
                                             InsightService insightService,
                                             HallucinationChecker hallucinationChecker,
                                             TokenBudgetManager tokenBudgetManager,
                                             AiClient aiClient,
                                             AiTaskProgressRegistry progressRegistry) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.insightService = insightService;
        this.hallucinationChecker = hallucinationChecker;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiClient = aiClient;
        this.progressRegistry = progressRegistry;
    }

    protected InsightEntity execute(Long projectId, Long userId) {
        return execute(projectId, userId, null);
    }

    /**
     * @param taskId 前端进度任务 ID；非 null 时推送阶段事件并支持断开即取消（取消返回 null 不落库）
     */
    protected InsightEntity execute(Long projectId, Long userId, String taskId) {
        if (aiClient == null) {
            log.info("{} skipped: no AI client for projectId={}", logLabel(), projectId);
            return null;
        }
        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }
        ScanRecord scan = scanService.getLatestScanEntity(projectId);
        if (scan == null) {
            throw new BusinessException(400, "请先触发一次扫描");
        }
        List<ClassSummaryEntity> classes = scanService.getClassSummaries(projectId);
        if (classes.isEmpty()) {
            throw new BusinessException(400, "暂无扫描结果，请先触发一次扫描");
        }
        progressRegistry.publish(taskId, "正在渲染分析提示词…");

        AnalysisContext ctx = new AnalysisContext(project, scan, classes);
        prepareVariables(ctx);

        PromptTemplate template = new PromptTemplate(templatePath());
        String prompt = template.render(ctx.vars);
        if (prompt.isEmpty()) {
            log.warn("{} skipped: empty prompt for projectId={}", logLabel(), projectId);
            return null;
        }

        if (progressRegistry.isCancelled(taskId)) {
            // 先于预算扣减检查：用户已终止时不再消耗预算（与影响分析/上下文问答口径一致）
            log.info("{} cancelled before AI call for projectId={}", logLabel(), projectId);
            return null;
        }

        int estimatedTokens = prompt.length() * 2 / 3 + 4096;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            log.warn("AI token monthly budget exceeded, rejecting {} for projectId={}",
                    logLabel().toLowerCase(Locale.ROOT), projectId);
            saveAudit(projectId, userId, null, "Monthly token budget exceeded", false);
            throw new BusinessException(ErrorCode.AI_TOKEN_BUDGET_EXCEEDED);
        }

        progressRegistry.publish(taskId, "AI 正在生成报告（通常需要 1–3 分钟）…");

        AiRequest request = new AiRequest();
        request.setPrompt(prompt);
        request.setSystemPrompt(systemPrompt());
        request.setMaxTokens(maxTokens());

        AiResponse response = aiAnalysisService.callAiWithResilience(request);
        if (response == null || response.getContent() == null || response.getContent().trim().isEmpty()) {
            // 空正文响应也带 response：已消耗 token 仍需记账
            saveAudit(projectId, userId, response, "All AI models exhausted or no content", false);
            log.warn("{} failed for projectId={}", logLabel(), projectId);
            throw new AiException(ErrorCode.AI_API_INTERNAL_ERROR,
                    "AI 模型多次尝试均未返回有效内容，请稍后重试");
        }
        if (progressRegistry.isCancelled(taskId)) {
            log.info("{} cancelled after AI call, discarding result for projectId={}", logLabel(), projectId);
            // 用户已终止：token 已消耗仍需记账，结果丢弃；审计口径统一为失败
            saveAudit(projectId, userId, response, "Cancelled by user", false);
            return null;
        }

        String content = response.getContent();
        long totalPromptTokens = response.getPromptTokens();
        long totalCompletionTokens = response.getCompletionTokens();
        long totalTokens = response.getTokensUsed();
        long totalLatency = response.getLatencyMs();
        progressRegistry.publish(taskId, "正在做类名防幻觉校验…");

        boolean retryAttempted = false;
        AiResponse retryResponse = null;
        try {
            HallucinationChecker.HallucinationResult hResult = hallucinationChecker.check(content, classes);
            if (hResult.hasHallucination()) {
                HallucinationChecker.Action action = hallucinationChecker.suggestAction(hResult);
                log.warn("{}: fakeClasses={}, action={}",
                        hallucinationCheckLabel(), hResult.getFakeClasses().size(), action);
                if (action == HallucinationChecker.Action.RETRY && classes.size() <= MAX_CLASSES_PER_CHUNK
                        && !progressRegistry.isCancelled(taskId)) {
                    retryAttempted = true;
                    progressRegistry.publish(taskId, "发现可疑类名，正在重新生成…");
                    AiRequest retryReq = new AiRequest();
                    retryReq.setPrompt(prompt);
                    retryReq.setSystemPrompt(retrySystemPrompt());
                    retryReq.setTemperature(0.1);
                    retryReq.setMaxTokens(maxTokens());
                    retryResponse = aiAnalysisService.callAiWithResilience(retryReq);
                }
            }
        } catch (Exception hc) {
            // 校验自身异常不应阻塞交付；审计已后移至提交点，异常逃逸会导致已消耗 token 无法入账
            log.warn("{} hallucination check failed for projectId={}: {}", logLabel(), projectId, hc.getMessage());
        }
        if (retryResponse != null) {
            // 重试调用的消耗无论结果是否采纳都必须记账（token 累计进入提交点审计行）
            totalPromptTokens += retryResponse.getPromptTokens();
            totalCompletionTokens += retryResponse.getCompletionTokens();
            totalTokens += retryResponse.getTokensUsed();
            totalLatency += retryResponse.getLatencyMs();
            boolean retryAdopted = false;
            try {
                retryAdopted = retryResponse.getContent() != null && !retryResponse.getContent().trim().isEmpty()
                        && !hallucinationChecker.check(retryResponse.getContent(), classes).hasHallucination();
            } catch (Exception hc) {
                log.warn("{} hallucination recheck failed for projectId={}: {}", logLabel(), projectId, hc.getMessage());
            }
            if (retryAdopted) {
                content = retryResponse.getContent();
                log.info("{} hallucination retry successful for projectId={}", logLabel(), projectId);
            } else {
                log.warn("{} hallucination retry failed for projectId={}, using degraded result",
                        logLabel(), projectId);
            }
        } else if (retryAttempted) {
            log.warn("{} hallucination retry call failed for projectId={}, using degraded result",
                    logLabel(), projectId);
        }

        if (progressRegistry.isCancelled(taskId)) {
            log.info("{} cancelled after hallucination handling, discarding for projectId={}", logLabel(), projectId);
            // 用户已终止：主调用与重试的 token 均已消耗，需一并记账；审计口径统一为失败
            saveAudit(projectId, userId, totalPromptTokens, totalCompletionTokens, totalTokens, totalLatency,
                    "Cancelled by user", false);
            return null;
        }
        // 提交点：审计行与落库紧邻执行，此后的终止不再改写审计语义
        saveAudit(projectId, userId, totalPromptTokens, totalCompletionTokens, totalTokens, totalLatency, null, true);
        progressRegistry.publish(taskId, "正在保存报告…");

        InsightEntity insight = new InsightEntity();
        insight.setScanId(scan.getId());
        insight.setProjectId(projectId);
        insight.setType(insightType());
        insight.setTitle(insightTitle(project.getName()));
        insight.setContent(content);
        insight.setConfidence(aiAnalysisService.calculateConfidenceSimple(content, classes));
        insight.setSources("[]");
        insight.setMetadata(String.format("{\"tokensUsed\":%d,\"latencyMs\":%d,\"classCount\":%d%s}",
                totalTokens, totalLatency, classes.size(), ctx.metadataExtra));
        insightService.saveInsight(insight);

        log.info("{} saved: id={}, projectId={}", insightLogLabel(), insight.getId(), projectId);
        return insight;
    }

    /** 钩子：子类可覆写以追加变量（如规约定义的 ruleDefinitions）与 metadata 扩展（如 ruleCount） */
    protected void prepareVariables(AnalysisContext ctx) {
        ctx.vars.putAll(aiAnalysisService.buildPromptVariables(ctx.project, ctx.scan, ctx.classes));
    }

    protected void saveAudit(Long projectId, Long userId, AiResponse response,
                             String errorMessage, boolean success) {
        saveAudit(projectId, userId,
                response != null ? response.getPromptTokens() : 0,
                response != null ? response.getCompletionTokens() : 0,
                response != null ? response.getTokensUsed() : 0,
                response != null ? response.getLatencyMs() : 0,
                errorMessage, success);
    }

    protected void saveAudit(Long projectId, Long userId,
                             long promptTokens, long completionTokens, long totalTokens, long latencyMs,
                             String errorMessage, boolean success) {
        aiAnalysisService.saveAiAuditLog(aiClient.getModelName(), stage(), projectId, userId,
                promptTokens, completionTokens, totalTokens, latencyMs, errorMessage, success);
    }

    protected static class AnalysisContext {
        final Project project;
        final ScanRecord scan;
        final List<ClassSummaryEntity> classes;
        final Map<String, String> vars = new HashMap<>();
        String metadataExtra = "";

        AnalysisContext(Project project, ScanRecord scan, List<ClassSummaryEntity> classes) {
            this.project = project;
            this.scan = scan;
            this.classes = classes;
        }
    }

    protected abstract String stage();

    protected abstract String templatePath();

    protected abstract String systemPrompt();

    protected abstract String retrySystemPrompt();

    protected abstract String insightType();

    protected abstract String insightTitle(String projectName);

    /** 日志标签（英文，如 "Pattern analysis"）：用于 skipped/failed/重试等日志措辞 */
    protected abstract String logLabel();

    /** 幻觉检查日志标签（如 "Pattern hallucination check"） */
    protected abstract String hallucinationCheckLabel();

    /** 落库成功日志标签（如 "Pattern insight"） */
    protected abstract String insightLogLabel();

    /**
     * 推理模型的 reasoning 与正文共享该预算，预算需覆盖推理开销再留给正文，
     * 否则会出现"正文为空但 HTTP 200"的截断（上限而非实际花费，调大无额外成本）。
     */
    protected int maxTokens() {
        return 8192;
    }
}
