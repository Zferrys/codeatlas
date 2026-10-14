package com.codeatlas.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;

/**
 * 启动类单测：mockStatic 拦下 SpringApplication.run，验证入口用正确的应用类与原始参数启动，
 * 不真正启动上下文（否则需要 MySQL/Redis/Neo4j 等外部环境）。
 */
@DisplayName("CodeAtlasApplication 单元测试")
class CodeAtlasApplicationTest {

    @Test
    @DisplayName("main — 以本应用类与原始参数调用 SpringApplication.run")
    void mainInvokesSpringApplicationWithSameClassAndArgs() {
        String[] args = {"--spring.profiles.active=test"};

        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            CodeAtlasApplication.main(args);

            mocked.verify(() -> SpringApplication.run(CodeAtlasApplication.class, args));
        }
    }

    @Test
    @DisplayName("main — 空参数不抛异常")
    void mainToleratesEmptyArgs() {
        String[] args = new String[0];

        try (MockedStatic<SpringApplication> mocked = mockStatic(SpringApplication.class)) {
            CodeAtlasApplication.main(args);

            mocked.verify(() -> SpringApplication.run(CodeAtlasApplication.class, args));
        }
    }

    @Test
    @DisplayName("启动类 — 带 @SpringBootApplication 且扫描 com.codeatlas 包")
    void applicationClassCarriesBootAnnotation() {
        SpringBootApplication annotation = CodeAtlasApplication.class.getAnnotation(SpringBootApplication.class);

        assertNotNull(annotation);
        assertNotNull(annotation.scanBasePackages());
        assertEquals("com.codeatlas", annotation.scanBasePackages()[0]);
    }
}
