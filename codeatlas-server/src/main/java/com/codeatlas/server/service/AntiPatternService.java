package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.InsightEntity;
import io.micrometer.core.annotation.Timed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 反模式检测：渲染 prompts/anti-pattern-detect.md，AI 按 8 个维度检出架构反模式与代码异味，
 * 结果落 insight 表（type=ANTI_PATTERN）。骨架逻辑见 {@link AbstractInsightAnalysisService}。
 */
@Service
public class AntiPatternService extends AbstractInsightAnalysisService {

    private static final String STAGE = "ANTI_PATTERN_DETECT";

    public AntiPatternService(AiAnalysisService aiAnalysisService,
                              ProjectService projectService,
                              ScanService scanService,
                              InsightService insightService,
                              HallucinationChecker hallucinationChecker,
                              TokenBudgetManager tokenBudgetManager,
                              @Autowired(required = false) AiClient aiClient) {
        super(aiAnalysisService, projectService, scanService, insightService,
                hallucinationChecker, tokenBudgetManager, aiClient);
    }

    @Timed(value = "ai.analysis.duration", description = "AI anti-pattern detection duration")
    public InsightEntity analyzeAntiPatterns(Long projectId, Long userId) {
        return execute(projectId, userId);
    }

    @Override
    protected String stage() {
        return STAGE;
    }

    @Override
    protected String templatePath() {
        return "prompts/anti-pattern-detect.md";
    }

    @Override
    protected String systemPrompt() {
        return "你是一位资深软件架构师。请检测代码库中的反模式与架构异味（循环依赖、上帝类、跨层调用、包循环、Util 膨胀、接口缺失、硬编码、异常处理），逐条给出严重级别与修复建议。用中文回答，类名保留原文。";
    }

    @Override
    protected String retrySystemPrompt() {
        return "你是一位资深软件架构师。请重新输出反模式检测结果，只引用给定的类列表。用中文回答。";
    }

    @Override
    protected String insightType() {
        return "ANTI_PATTERN";
    }

    @Override
    protected String insightTitle(String projectName) {
        return "反模式检测 — " + projectName;
    }

    @Override
    protected String logLabel() {
        return "Anti-pattern analysis";
    }

    @Override
    protected String hallucinationCheckLabel() {
        return "Anti-pattern hallucination check";
    }

    @Override
    protected String insightLogLabel() {
        return "Anti-pattern insight";
    }

    @Override
    protected int maxTokens() {
        return 8192;
    }
}
