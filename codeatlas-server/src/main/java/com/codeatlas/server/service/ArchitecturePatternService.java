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
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 架构模式识别：渲染 prompts/architecture-pattern.md，AI 识别主要架构/设计模式与分层合理性，
 * 结果落 insight 表（type=PATTERN）。与架构叙事同口径：Token 预算闸门 + 审计 + 生成侧防幻觉。
 */
@Service
public class ArchitecturePatternService {

    private static final Logger log = LoggerFactory.getLogger(ArchitecturePatternService.class);
    private static final String STAGE = "PATTERN_ANALYSIS";
    private static final int MAX_CLASSES_PER_CHUNK = 200;

    private final AiAnalysisService aiAnalysisService;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final InsightService insightService;
    private final HallucinationChecker hallucinationChecker;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiClient aiClient;

    public ArchitecturePatternService(AiAnalysisService aiAnalysisService,
                                      ProjectService projectService,
                                      ScanService scanService,
                                      InsightService insightService,
                                      HallucinationChecker hallucinationChecker,
                                      TokenBudgetManager tokenBudgetManager,
                                      @Autowired(required = false) AiClient aiClient) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.insightService = insightService;
        this.hallucinationChecker = hallucinationChecker;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiClient = aiClient;
    }

    @Timed(value = "ai.analysis.duration", description = "AI architecture pattern analysis duration")
    public InsightEntity analyzePatterns(Long projectId, Long userId) {
        if (aiClient == null) {
            log.info("Pattern analysis skipped: no AI client for projectId={}", projectId);
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

        Map<String, String> vars = aiAnalysisService.buildPromptVariables(project, scan, classes);
        PromptTemplate template = new PromptTemplate("prompts/architecture-pattern.md");
        String prompt = template.render(vars);
        if (prompt.isEmpty()) {
            log.warn("Pattern analysis skipped: empty prompt for projectId={}", projectId);
            return null;
        }

        int estimatedTokens = prompt.length() * 2 / 3 + 4096;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            log.warn("AI token monthly budget exceeded, rejecting pattern analysis for projectId={}", projectId);
            saveAudit(projectId, userId, null, "Monthly token budget exceeded", false);
            return null;
        }

        AiRequest request = new AiRequest();
        request.setPrompt(prompt);
        request.setSystemPrompt(
                "你是一位资深软件架构师。请识别代码库使用的主要架构模式与设计模式，评估分层合理性、跨层调用与整体一致性。用中文回答，类名与模式名保留英文。");
        request.setTemperature(0.3);
        request.setMaxTokens(2048);

        AiResponse response = aiAnalysisService.callAiWithResilience(request);
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            saveAudit(projectId, userId, null, "All AI models exhausted or no content", false);
            log.warn("Pattern analysis failed for projectId={}", projectId);
            return null;
        }
        saveAudit(projectId, userId, response, null, true);

        String content = response.getContent();
        long totalTokens = response.getTokensUsed();

        HallucinationChecker.HallucinationResult hResult = hallucinationChecker.check(content, classes);
        if (hResult.hasHallucination()) {
            HallucinationChecker.Action action = hallucinationChecker.suggestAction(hResult);
            log.warn("Pattern hallucination check: fakeClasses={}, action={}",
                    hResult.getFakeClasses().size(), action);
            if (action == HallucinationChecker.Action.RETRY && classes.size() <= MAX_CLASSES_PER_CHUNK) {
                AiRequest retryReq = new AiRequest();
                retryReq.setPrompt(prompt);
                retryReq.setSystemPrompt("你是一位资深软件架构师。请重新输出架构模式识别结果，只引用给定的类列表。用中文回答。");
                retryReq.setTemperature(0.1);
                retryReq.setMaxTokens(2048);
                AiResponse retryResponse = aiAnalysisService.callAiWithResilience(retryReq);
                if (retryResponse != null && retryResponse.getContent() != null
                        && !hallucinationChecker.check(retryResponse.getContent(), classes).hasHallucination()) {
                    content = retryResponse.getContent();
                    totalTokens += retryResponse.getTokensUsed();
                    log.info("Pattern hallucination retry successful for projectId={}", projectId);
                } else {
                    log.warn("Pattern hallucination retry failed for projectId={}, using degraded result", projectId);
                }
            }
        }

        InsightEntity insight = new InsightEntity();
        insight.setScanId(scan.getId());
        insight.setProjectId(projectId);
        insight.setType("PATTERN");
        insight.setTitle("架构模式识别 — " + project.getName());
        insight.setContent(content);
        insight.setConfidence(aiAnalysisService.calculateConfidenceSimple(content, classes));
        insight.setSources("[]");
        insight.setMetadata(String.format("{\"tokensUsed\":%d,\"latencyMs\":%d,\"classCount\":%d}",
                totalTokens, response.getLatencyMs(), classes.size()));
        insightService.saveInsight(insight);

        log.info("Pattern insight saved: id={}, projectId={}", insight.getId(), projectId);
        return insight;
    }

    private void saveAudit(Long projectId, Long userId, AiResponse response,
                           String errorMessage, boolean success) {
        aiAnalysisService.saveAiAuditLog(aiClient.getModelName(), STAGE, projectId, userId,
                response != null ? response.getPromptTokens() : 0,
                response != null ? response.getCompletionTokens() : 0,
                response != null ? response.getTokensUsed() : 0,
                response != null ? response.getLatencyMs() : 0,
                errorMessage, success);
    }
}
