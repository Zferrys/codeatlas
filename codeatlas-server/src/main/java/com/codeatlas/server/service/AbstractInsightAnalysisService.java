package com.codeatlas.server.service;

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

    protected AbstractInsightAnalysisService(AiAnalysisService aiAnalysisService,
                                             ProjectService projectService,
                                             ScanService scanService,
                                             InsightService insightService,
                                             HallucinationChecker hallucinationChecker,
                                             TokenBudgetManager tokenBudgetManager,
                                             AiClient aiClient) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.insightService = insightService;
        this.hallucinationChecker = hallucinationChecker;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiClient = aiClient;
    }

    protected InsightEntity execute(Long projectId, Long userId) {
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

        AnalysisContext ctx = new AnalysisContext(project, scan, classes);
        prepareVariables(ctx);

        PromptTemplate template = new PromptTemplate(templatePath());
        String prompt = template.render(ctx.vars);
        if (prompt.isEmpty()) {
            log.warn("{} skipped: empty prompt for projectId={}", logLabel(), projectId);
            return null;
        }

        int estimatedTokens = prompt.length() * 2 / 3 + 4096;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            log.warn("AI token monthly budget exceeded, rejecting {} for projectId={}",
                    logLabel().toLowerCase(Locale.ROOT), projectId);
            saveAudit(projectId, userId, null, "Monthly token budget exceeded", false);
            return null;
        }

        AiRequest request = new AiRequest();
        request.setPrompt(prompt);
        request.setSystemPrompt(systemPrompt());
        request.setTemperature(0.3);
        request.setMaxTokens(maxTokens());

        AiResponse response = aiAnalysisService.callAiWithResilience(request);
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            saveAudit(projectId, userId, null, "All AI models exhausted or no content", false);
            log.warn("{} failed for projectId={}", logLabel(), projectId);
            return null;
        }
        saveAudit(projectId, userId, response, null, true);

        String content = response.getContent();
        long totalTokens = response.getTokensUsed();

        HallucinationChecker.HallucinationResult hResult = hallucinationChecker.check(content, classes);
        if (hResult.hasHallucination()) {
            HallucinationChecker.Action action = hallucinationChecker.suggestAction(hResult);
            log.warn("{}: fakeClasses={}, action={}",
                    hallucinationCheckLabel(), hResult.getFakeClasses().size(), action);
            if (action == HallucinationChecker.Action.RETRY && classes.size() <= MAX_CLASSES_PER_CHUNK) {
                AiRequest retryReq = new AiRequest();
                retryReq.setPrompt(prompt);
                retryReq.setSystemPrompt(retrySystemPrompt());
                retryReq.setTemperature(0.1);
                retryReq.setMaxTokens(maxTokens());
                AiResponse retryResponse = aiAnalysisService.callAiWithResilience(retryReq);
                if (retryResponse != null && retryResponse.getContent() != null
                        && !hallucinationChecker.check(retryResponse.getContent(), classes).hasHallucination()) {
                    content = retryResponse.getContent();
                    totalTokens += retryResponse.getTokensUsed();
                    log.info("{} hallucination retry successful for projectId={}", logLabel(), projectId);
                } else {
                    log.warn("{} hallucination retry failed for projectId={}, using degraded result",
                            logLabel(), projectId);
                }
            }
        }

        InsightEntity insight = new InsightEntity();
        insight.setScanId(scan.getId());
        insight.setProjectId(projectId);
        insight.setType(insightType());
        insight.setTitle(insightTitle(project.getName()));
        insight.setContent(content);
        insight.setConfidence(aiAnalysisService.calculateConfidenceSimple(content, classes));
        insight.setSources("[]");
        insight.setMetadata(String.format("{\"tokensUsed\":%d,\"latencyMs\":%d,\"classCount\":%d%s}",
                totalTokens, response.getLatencyMs(), classes.size(), ctx.metadataExtra));
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
        aiAnalysisService.saveAiAuditLog(aiClient.getModelName(), stage(), projectId, userId,
                response != null ? response.getPromptTokens() : 0,
                response != null ? response.getCompletionTokens() : 0,
                response != null ? response.getTokensUsed() : 0,
                response != null ? response.getLatencyMs() : 0,
                errorMessage, success);
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

    protected int maxTokens() {
        return 2048;
    }
}
