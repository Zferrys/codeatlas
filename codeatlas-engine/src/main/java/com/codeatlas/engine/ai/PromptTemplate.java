package com.codeatlas.engine.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Prompt 模板引擎 — 从 classpath 加载 .md 模板，用 {{key}} 占位符替换。
 */
public class PromptTemplate {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplate.class);
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*(\\w+)\\s*\\}\\}");

    private static final Map<String, String> CACHE = new HashMap<>();

    private final String templatePath;
    private final String templateContent;

    public PromptTemplate(String templatePath) {
        this.templatePath = templatePath;
        this.templateContent = loadTemplate(templatePath);
    }

    private static synchronized String loadTemplate(String path) {
        if (CACHE.containsKey(path)) {
            return CACHE.get(path);
        }
        try (InputStream is = PromptTemplate.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                log.warn("Prompt template not found: {}", path);
                CACHE.put(path, "");
                return "";
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            String content = sb.toString();
            CACHE.put(path, content);
            log.debug("Loaded prompt template: {} ({} chars)", path, content.length());
            return content;
        } catch (IOException e) {
            log.error("Failed to load prompt template: {}", path, e);
            CACHE.put(path, "");
            return "";
        }
    }

    /**
     * 渲染模板 — 将 {{key}} 替换为 variables 中对应的值。
     */
    public String render(Map<String, String> variables) {
        if (templateContent.isEmpty()) {
            return "";
        }
        return VAR_PATTERN.matcher(templateContent).replaceAll(match -> {
            String key = match.group(1);
            String value = variables.getOrDefault(key, "");
            return value.replace("$", "\\$");
        });
    }

    public String getTemplatePath() { return templatePath; }
}
