package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.InsightEntity;
import io.micrometer.core.annotation.Timed;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 架构模式识别：渲染 prompts/architecture-pattern.md，AI 识别主要架构/设计模式与分层合理性，
 * 结果落 insight 表（type=PATTERN）。骨架逻辑见 {@link AbstractInsightAnalysisService}。
 */
@Service
public class ArchitecturePatternService extends AbstractInsightAnalysisService {

    private static final String STAGE = "PATTERN_ANALYSIS";

    public ArchitecturePatternService(AiAnalysisService aiAnalysisService,
                                      ProjectService projectService,
                                      ScanService scanService,
                                      InsightService insightService,
                                      HallucinationChecker hallucinationChecker,
                                      TokenBudgetManager tokenBudgetManager,
                                      @Autowired(required = false) AiClient aiClient) {
        super(aiAnalysisService, projectService, scanService, insightService,
                hallucinationChecker, tokenBudgetManager, aiClient);
    }

    @Timed(value = "ai.analysis.duration", description = "AI architecture pattern analysis duration")
    public InsightEntity analyzePatterns(Long projectId, Long userId) {
        return execute(projectId, userId);
    }

    @Override
    protected String stage() {
        return STAGE;
    }

    @Override
    protected String templatePath() {
        return "prompts/architecture-pattern.md";
    }

    @Override
    protected String systemPrompt() {
        return "你是一位资深软件架构师。请识别代码库使用的主要架构模式与设计模式，评估分层合理性、跨层调用与整体一致性。用中文回答，类名与模式名保留英文。";
    }

    @Override
    protected String retrySystemPrompt() {
        return "你是一位资深软件架构师。请重新输出架构模式识别结果，只引用给定的类列表。用中文回答。";
    }

    @Override
    protected String insightType() {
        return "PATTERN";
    }

    @Override
    protected String insightTitle(String projectName) {
        return "架构模式识别 — " + projectName;
    }

    @Override
    protected String logLabel() {
        return "Pattern analysis";
    }

    @Override
    protected String hallucinationCheckLabel() {
        return "Pattern hallucination check";
    }

    @Override
    protected String insightLogLabel() {
        return "Pattern insight";
    }
}
