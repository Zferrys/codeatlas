package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.PromptTemplate;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
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
 * 规约合规检查：渲染 prompts/constitution-check.md，AI 对照项目已启用的架构规约逐条判定违规，
 * 结果落 insight 表（type=CONSTITUTION_CHECK）。与架构叙事同口径：Token 预算闸门 + 审计 + 生成侧防幻觉。
 */
@Service
public class ConstitutionCheckService {

    private static final Logger log = LoggerFactory.getLogger(ConstitutionCheckService.class);
    private static final String STAGE = "CONSTITUTION_CHECK";
    private static final int MAX_CLASSES_PER_CHUNK = 200;

    private final AiAnalysisService aiAnalysisService;
    private final ProjectService projectService;
    private final ScanService scanService;
    private final InsightService insightService;
    private final ConstitutionRuleService constitutionRuleService;
    private final HallucinationChecker hallucinationChecker;
    private final TokenBudgetManager tokenBudgetManager;
    private final AiClient aiClient;

    public ConstitutionCheckService(AiAnalysisService aiAnalysisService,
                                    ProjectService projectService,
                                    ScanService scanService,
                                    InsightService insightService,
                                    ConstitutionRuleService constitutionRuleService,
                                    HallucinationChecker hallucinationChecker,
                                    TokenBudgetManager tokenBudgetManager,
                                    @Autowired(required = false) AiClient aiClient) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
        this.insightService = insightService;
        this.constitutionRuleService = constitutionRuleService;
        this.hallucinationChecker = hallucinationChecker;
        this.tokenBudgetManager = tokenBudgetManager;
        this.aiClient = aiClient;
    }

    @Timed(value = "ai.analysis.duration", description = "AI constitution compliance check duration")
    public InsightEntity analyzeConstitution(Long projectId, Long userId) {
        if (aiClient == null) {
            log.info("Constitution check skipped: no AI client for projectId={}", projectId);
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

        List<ConstitutionRuleEntity> rules = constitutionRuleService.getRules(projectId);
        if (rules.isEmpty()) {
            throw new BusinessException(400, "当前项目没有启用的架构规则，请先在规约管理中启用");
        }

        Map<String, String> vars = aiAnalysisService.buildPromptVariables(project, scan, classes);
        vars.put("ruleDefinitions", formatRules(rules));
        PromptTemplate template = new PromptTemplate("prompts/constitution-check.md");
        String prompt = template.render(vars);
        if (prompt.isEmpty()) {
            log.warn("Constitution check skipped: empty prompt for projectId={}", projectId);
            return null;
        }

        int estimatedTokens = prompt.length() * 2 / 3 + 4096;
        if (!tokenBudgetManager.tryConsume(estimatedTokens)) {
            log.warn("AI token monthly budget exceeded, rejecting constitution check for projectId={}", projectId);
            saveAudit(projectId, userId, null, "Monthly token budget exceeded", false);
            return null;
        }

        AiRequest request = new AiRequest();
        request.setPrompt(prompt);
        request.setSystemPrompt(
                "你是一位资深软件架构师。请对照架构规约逐条判定项目的合规情况，给出违规类、原因、修复建议与严重度。用中文回答，类名保留原文。");
        request.setTemperature(0.3);
        request.setMaxTokens(2048);

        AiResponse response = aiAnalysisService.callAiWithResilience(request);
        if (response == null || response.getContent() == null || response.getContent().isEmpty()) {
            saveAudit(projectId, userId, null, "All AI models exhausted or no content", false);
            log.warn("Constitution check failed for projectId={}", projectId);
            return null;
        }
        saveAudit(projectId, userId, response, null, true);

        String content = response.getContent();
        long totalTokens = response.getTokensUsed();

        HallucinationChecker.HallucinationResult hResult = hallucinationChecker.check(content, classes);
        if (hResult.hasHallucination()) {
            HallucinationChecker.Action action = hallucinationChecker.suggestAction(hResult);
            log.warn("Constitution check hallucination: fakeClasses={}, action={}",
                    hResult.getFakeClasses().size(), action);
            if (action == HallucinationChecker.Action.RETRY && classes.size() <= MAX_CLASSES_PER_CHUNK) {
                AiRequest retryReq = new AiRequest();
                retryReq.setPrompt(prompt);
                retryReq.setSystemPrompt("你是一位资深软件架构师。请重新输出规约合规检查结果，只引用给定的类列表。用中文回答。");
                retryReq.setTemperature(0.1);
                retryReq.setMaxTokens(2048);
                AiResponse retryResponse = aiAnalysisService.callAiWithResilience(retryReq);
                if (retryResponse != null && retryResponse.getContent() != null
                        && !hallucinationChecker.check(retryResponse.getContent(), classes).hasHallucination()) {
                    content = retryResponse.getContent();
                    totalTokens += retryResponse.getTokensUsed();
                    log.info("Constitution check hallucination retry successful for projectId={}", projectId);
                } else {
                    log.warn("Constitution check hallucination retry failed for projectId={}, using degraded result", projectId);
                }
            }
        }

        InsightEntity insight = new InsightEntity();
        insight.setScanId(scan.getId());
        insight.setProjectId(projectId);
        insight.setType("CONSTITUTION_CHECK");
        insight.setTitle("规约合规检查 — " + project.getName());
        insight.setContent(content);
        insight.setConfidence(aiAnalysisService.calculateConfidenceSimple(content, classes));
        insight.setSources("[]");
        insight.setMetadata(String.format("{\"tokensUsed\":%d,\"latencyMs\":%d,\"classCount\":%d,\"ruleCount\":%d}",
                totalTokens, response.getLatencyMs(), classes.size(), rules.size()));
        insightService.saveInsight(insight);

        log.info("Constitution check insight saved: id={}, projectId={}", insight.getId(), projectId);
        return insight;
    }

    private String formatRules(List<ConstitutionRuleEntity> rules) {
        StringBuilder sb = new StringBuilder();
        for (ConstitutionRuleEntity rule : rules) {
            sb.append("- [").append(rule.getSeverity() != null ? rule.getSeverity() : "MAJOR").append("] ")
                    .append(rule.getName());
            if (rule.getDescription() != null && !rule.getDescription().isEmpty()) {
                sb.append("：").append(rule.getDescription());
            }
            sb.append("\n");
            if (rule.getRuleDefinition() != null && !rule.getRuleDefinition().isEmpty()) {
                sb.append("  定义: ").append(rule.getRuleDefinition()).append("\n");
            }
        }
        return sb.toString();
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
