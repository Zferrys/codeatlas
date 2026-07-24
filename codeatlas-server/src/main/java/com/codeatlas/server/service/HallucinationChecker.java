package com.codeatlas.server.service;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI 幻觉检测器。
 * 检查 AI 输出中是否引用了项目中不存在的类名，判断是否存在幻觉。
 */
@Component
public class HallucinationChecker {

    private static final Logger log = LoggerFactory.getLogger(HallucinationChecker.class);

    /** 匹配完整类名: com.xxx.Yyy 或 org.xxx.Yyy */
    private static final Pattern FQN_PATTERN =
            Pattern.compile("\\b([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*)\\b");

    /** 虚构类名超过此阈值则判定为幻觉 */
    private static final int FAKE_CLASS_THRESHOLD = 3;

    /**
     * 检查 AI 输出中是否存在幻觉（引用不存在的类）。
     *
     * @param aiOutput       AI 返回的文本内容
     * @param actualClasses  项目中实际存在的类列表
     * @return HallucinationResult 检测结果
     */
    public HallucinationResult check(String aiOutput, List<ClassSummaryEntity> actualClasses) {
        if (aiOutput == null || aiOutput.isEmpty()) {
            return new HallucinationResult(false, List.of());
        }

        Set<String> actualFqns = actualClasses.stream()
                .map(ClassSummaryEntity::getFqn)
                .collect(Collectors.toSet());
        Set<String> actualSimpleNames = actualClasses.stream()
                .map(ClassSummaryEntity::getSimpleName)
                .collect(Collectors.toSet());

        List<String> fakeReferences = new ArrayList<>();
        Matcher m = FQN_PATTERN.matcher(aiOutput);

        while (m.find()) {
            String fqn = m.group(1);
            if (actualFqns.contains(fqn)) continue;

            // 检查简名是否匹配（AI 可能只用简名引用）
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            if (actualSimpleNames.contains(simpleName)) continue;

            // 跳过常见 JDK/框架类名
            if (isCommonLibrary(fqn)) continue;

            fakeReferences.add(fqn);
        }

        boolean hasHallucination = fakeReferences.size() >= FAKE_CLASS_THRESHOLD;
        if (hasHallucination) {
            log.warn("Hallucination detected: {} fake class references: {}",
                    fakeReferences.size(),
                    fakeReferences.size() <= 10 ? fakeReferences : fakeReferences.subList(0, 10));
        }

        return new HallucinationResult(hasHallucination, fakeReferences);
    }

    /**
     * 常见的 JDK/框架类名，AI 引用这些不算幻觉。
     */
    private boolean isCommonLibrary(String fqn) {
        return fqn.startsWith("java.") || fqn.startsWith("javax.")
                || fqn.startsWith("jakarta.") || fqn.startsWith("org.springframework.")
                || fqn.startsWith("org.apache.") || fqn.startsWith("com.fasterxml.")
                || fqn.startsWith("org.slf4j.") || fqn.startsWith("lombok.");
    }

    /**
     * 检测到幻觉后的处理建议。
     */
    public enum Action {
        PASS,           // 无幻觉，正常使用
        RETRY,          // 轻度幻觉，低温重试
        DEGRADED        // 严重幻觉，标记降级
    }

    public Action suggestAction(HallucinationResult result) {
        if (!result.hasHallucination()) return Action.PASS;
        if (result.getFakeClasses().size() <= 5) return Action.RETRY;
        return Action.DEGRADED;
    }

    /**
     * 幻觉检测结果。
     */
    public static class HallucinationResult {

        private final boolean hasHallucination;
        private final List<String> fakeClasses;

        public HallucinationResult(boolean hasHallucination, List<String> fakeClasses) {
            this.hasHallucination = hasHallucination;
            this.fakeClasses = fakeClasses;
        }

        public boolean hasHallucination() { return hasHallucination; }
        public List<String> getFakeClasses() { return fakeClasses; }
    }
}
