package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.parser.JavaParserService;
import com.codeatlas.server.mapper.SystemConfigMapper;
import com.codeatlas.server.service.AiModelService;
import com.codeatlas.server.service.impl.AiModelServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 engine 模块的类注册为 Spring Bean，供依赖注入使用。
 */
@Configuration
public class EngineBeanConfig {

    private static final Logger log = LoggerFactory.getLogger(EngineBeanConfig.class);

    @Bean
    public GitService gitService() {
        return new GitService();
    }

    @Bean
    public JavaParserService javaParserService() {
        return new JavaParserService();
    }

    @Bean
    public AiModelService aiModelService(
            SystemConfigMapper systemConfigMapper,
            @Value("${codeatlas.ai.clients.claude.base-url:https://api.anthropic.com}") String claudeBaseUrl,
            @Value("${codeatlas.ai.clients.deepseek.base-url:https://api.deepseek.com}") String deepseekBaseUrl,
            @Value("${codeatlas.ai.clients.claude.model:claude-sonnet-4-6}") String claudeModel,
            @Value("${codeatlas.ai.clients.deepseek.model:deepseek-v4-pro}") String deepseekModel) {
        String claudeKey = System.getenv("ANTHROPIC_API_KEY");
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");

        Map<String, String> providerKeys = new LinkedHashMap<>();
        Map<String, String> baseUrls = new LinkedHashMap<>();
        List<String> fallbackOrder = new ArrayList<>();

        // 保持原有优先级语义：Claude 主、DeepSeek 备
        if (claudeKey != null && !claudeKey.isEmpty()) {
            providerKeys.put(AiModelServiceImpl.PROVIDER_CLAUDE, claudeKey);
            baseUrls.put(AiModelServiceImpl.PROVIDER_CLAUDE, claudeBaseUrl);
            fallbackOrder.add(claudeModel);
            log.info("AI: Claude client registered (primary)");
        }
        if (deepseekKey != null && !deepseekKey.isEmpty()) {
            providerKeys.put(AiModelServiceImpl.PROVIDER_DEEPSEEK, deepseekKey);
            baseUrls.put(AiModelServiceImpl.PROVIDER_DEEPSEEK, deepseekBaseUrl);
            fallbackOrder.add(deepseekModel);
            log.info("AI: DeepSeek client registered (fallback)");
        }

        String defaultModel = fallbackOrder.isEmpty() ? "" : fallbackOrder.get(0);
        return new AiModelServiceImpl(providerKeys, baseUrls, fallbackOrder, defaultModel, systemConfigMapper);
    }

    @Bean
    public AiClient aiClient(AiModelService aiModelService) {
        if (!aiModelService.hasAnyModel()) {
            log.warn("No AI API key found (ANTHROPIC_API_KEY or DEEPSEEK_API_KEY) — AI analysis will be unavailable");
            return null;
        }
        AiClient router = new AiModelRouter(aiModelService);
        log.info("AI: model router registered (current model: {})", aiModelService.getCurrentModelId());
        return router;
    }
}
