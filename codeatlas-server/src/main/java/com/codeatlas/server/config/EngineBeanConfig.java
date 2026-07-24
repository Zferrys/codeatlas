package com.codeatlas.server.config;

import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.parser.JavaParserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 将 engine 模块的类注册为 Spring Bean，供 ScanServiceImpl 依赖注入使用。
 */
@Configuration
public class EngineBeanConfig {

    @Bean
    public GitService gitService() {
        return new GitService();
    }

    @Bean
    public JavaParserService javaParserService() {
        return new JavaParserService();
    }
}
