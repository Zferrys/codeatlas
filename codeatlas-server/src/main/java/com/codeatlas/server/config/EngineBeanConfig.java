package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.ClaudeClient;
import com.codeatlas.engine.ai.DeepSeekClient;
import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.parser.JavaParserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
    public AiClient aiClient() {
        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        String claudeKey = System.getenv("ANTHROPIC_API_KEY");

        List<AiClient> clients = new ArrayList<>();

        if (claudeKey != null && !claudeKey.isEmpty()) {
            clients.add(new ClaudeClient(claudeKey));
            log.info("AI: Claude client registered (primary)");
        }
        if (deepseekKey != null && !deepseekKey.isEmpty()) {
            clients.add(new DeepSeekClient(deepseekKey));
            log.info("AI: DeepSeek client registered (fallback)");
        }

        if (clients.isEmpty()) {
            log.warn("No AI API key found (ANTHROPIC_API_KEY or DEEPSEEK_API_KEY) — AI analysis will be unavailable");
            return null;
        }

        if (clients.size() == 1) {
            log.info("AI: single client mode — no fallback available");
            return clients.get(0);
        }

        return new AiClientFallbackChain(clients);
    }
}
