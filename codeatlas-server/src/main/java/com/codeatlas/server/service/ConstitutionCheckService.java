package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.entity.InsightEntity;
import io.micrometer.core.annotation.Timed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 规约合规检查：渲染 prompts/constitution-check.md，AI 对照项目已启用的架构规约逐条判定违规，
 * 结果落 insight 表（type=CONSTITUTION_CHECK）。骨架逻辑见 {@link AbstractInsightAnalysisService}。
 */
@Service
public class ConstitutionCheckService extends AbstractInsightAnalysisService {

    private static final String STAGE = "CONSTITUTION_CHECK";

    private final ConstitutionRuleService constitutionRuleService;

    public ConstitutionCheckService(AiAnalysisService aiAnalysisService,
                                    ProjectService projectService,
                                    ScanService scanService,
                                    InsightService insightService,
                                    ConstitutionRuleService constitutionRuleService,
                                    HallucinationChecker hallucinationChecker,
                                    TokenBudgetManager tokenBudgetManager,
                                    @Autowired(required = false) AiClient aiClient,
                                    AiTaskProgressRegistry progressRegistry) {
        super(aiAnalysisService, projectService, scanService, insightService,
                hallucinationChecker, tokenBudgetManager, aiClient, progressRegistry);
        this.constitutionRuleService = constitutionRuleService;
    }

    @Timed(value = "ai.analysis.duration", description = "AI constitution compliance check duration")
    public InsightEntity analyzeConstitution(Long projectId, Long userId) {
        return execute(projectId, userId);
    }

    /** @param taskId 前端进度任务 ID；非 null 时推送阶段事件并支持断开即取消 */
    public InsightEntity analyzeConstitution(Long projectId, Long userId, String taskId) {
        return execute(projectId, userId, taskId);
    }

    @Override
    protected void prepareVariables(AnalysisContext ctx) {
        super.prepareVariables(ctx);
        List<ConstitutionRuleEntity> rules = constitutionRuleService.getRules(ctx.project.getId());
        if (rules.isEmpty()) {
            throw new BusinessException(400, "当前项目没有启用的架构规则，请先在规约管理中启用");
        }
        ctx.vars.put("ruleDefinitions", formatRules(rules));
        ctx.metadataExtra = String.format(",\"ruleCount\":%d", rules.size());
    }

    @Override
    protected String stage() {
        return STAGE;
    }

    @Override
    protected String templatePath() {
        return "prompts/constitution-check.md";
    }

    @Override
    protected String systemPrompt() {
        return "你是一位资深软件架构师。请对照架构规约逐条判定项目的合规情况，给出违规类、原因、修复建议与严重度。用中文回答，类名保留原文。";
    }

    @Override
    protected String retrySystemPrompt() {
        return "你是一位资深软件架构师。请重新输出规约合规检查结果，只引用给定的类列表。用中文回答。";
    }

    @Override
    protected String insightType() {
        return "CONSTITUTION_CHECK";
    }

    @Override
    protected String insightTitle(String projectName) {
        return "规约合规检查 — " + projectName;
    }

    @Override
    protected String logLabel() {
        return "Constitution check";
    }

    @Override
    protected String hallucinationCheckLabel() {
        return "Constitution check hallucination";
    }

    @Override
    protected String insightLogLabel() {
        return "Constitution check insight";
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
}
