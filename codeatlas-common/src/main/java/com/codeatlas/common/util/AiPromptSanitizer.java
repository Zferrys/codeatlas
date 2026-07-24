package com.codeatlas.common.util;

import java.util.regex.Pattern;

/**
 * AI 提示注入过滤器。对用户输入做截断、控制字符清理、注入模式过滤，
 * 防止用户通过精心构造的输入操纵 AI 行为。
 */
public final class AiPromptSanitizer {

    private static final int MAX_INPUT_LENGTH = 8000;
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern INJECTION_PATTERNS = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?(previous|above|prior)\\s+instructions?"
                    + "|you\\s+are\\s+now\\s+\\w+"
                    + "|system\\s*(prompt|message|instruction)"
                    + "|<\\|.*?\\|>"
                    + "|\\[INST\\].*?\\[/INST\\]"
                    + "|<\\s*(script|iframe|object|embed)"
                    + ")");

    private AiPromptSanitizer() {}

    /**
     * 清洗用户输入，防止提示注入。返回安全的文本。
     *
     * @param input 原始用户输入
     * @return 清洗后的文本；null/空输入返回空字符串
     */
    public static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        // 1. 截断超长输入
        String result = input.length() > MAX_INPUT_LENGTH
                ? input.substring(0, MAX_INPUT_LENGTH) : input;

        // 2. 移除控制字符（保留换行和制表符）
        result = CONTROL_CHARS.matcher(result).replaceAll("");

        // 3. 替换注入特征为占位符
        result = INJECTION_PATTERNS.matcher(result).replaceAll("[filtered]");

        return result.trim();
    }

    /**
     * 检测输入是否包含可疑注入模式（不修改，仅检测）。
     *
     * @return true 如果检测到可疑模式
     */
    public static boolean hasSuspiciousPattern(String input) {
        if (input == null || input.isEmpty()) {
            return false;
        }
        return INJECTION_PATTERNS.matcher(input).find();
    }
}
