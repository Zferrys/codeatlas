package com.codeatlas.server.util;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 健康度评分标准唯一实现：四维度分项 + 加权总分。
 * ScanServiceImpl（扫描完成时落库 project.health_score）与 InsightController（/health-score 实时展示）
 * 共用本类，保证两处口径一致。
 */
public final class HealthScoreCalculator {

    /** 维度权重：总分 = 各维度加权求和 */
    public static final double WEIGHT_ARCHITECTURE = 0.4;
    public static final double WEIGHT_STRUCTURE = 0.3;
    public static final double WEIGHT_QUALITY = 0.2;
    public static final double WEIGHT_DEPENDENCY = 0.1;

    /** 每个违规扣 5 分，扣分上限 40 分 */
    private static final double VIOLATION_PENALTY_PER_ITEM = 5.0;
    private static final double MAX_VIOLATION_PENALTY = 40.0;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private HealthScoreCalculator() {
    }

    /**
     * 计算四维度分项（0-100 取整）与加权总分（0-100，保留两位小数）。
     * 无类数据时全部为 0。
     */
    public static Result compute(int totalClasses, int violations, List<ClassSummaryEntity> classes) {
        if (totalClasses <= 0 || classes == null || classes.isEmpty()) {
            return new Result(0, 0, 0, 0, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }

        // 架构合规：每个违规扣 5 分，扣分上限 40 分
        double architecture = clamp(100.0 - Math.min(violations * VIOLATION_PENALTY_PER_ITEM, MAX_VIOLATION_PENALTY));

        // 代码结构：已识别分层的类占比
        long layeredClasses = classes.stream()
                .filter(c -> c.getLayer() != null && !"UNKNOWN".equals(c.getLayer()))
                .count();
        double structure = clamp((double) layeredClasses / classes.size() * 100.0);

        // 代码质量：平均每类方法数 5–15 为满分；不足 5 每少 1 个扣 10 分；超过 15 每多 1 个扣 3 分
        double avgMethods = classes.stream()
                .mapToInt(c -> c.getTotalMethods() != null ? c.getTotalMethods() : 0)
                .average().orElse(0);
        double quality = avgMethods >= 5 && avgMethods <= 15 ? 100.0
                : avgMethods < 5 ? 100.0 - (5.0 - avgMethods) * 10.0
                : 100.0 - (avgMethods - 15.0) * 3.0;

        // 依赖健康：平均每类依赖数 1–20 为满分；少于 1 得 70 分；超过 20 每多 1 个扣 2 分
        double avgDependencies = classes.stream()
                .mapToInt(c -> countDependencies(c.getDependencies()))
                .average().orElse(0);
        double dependency = avgDependencies >= 1 && avgDependencies <= 20 ? 100.0
                : avgDependencies < 1 ? 70.0
                : 100.0 - (avgDependencies - 20.0) * 2.0;

        double total = WEIGHT_ARCHITECTURE * architecture + WEIGHT_STRUCTURE * structure
                + WEIGHT_QUALITY * quality + WEIGHT_DEPENDENCY * dependency;
        BigDecimal totalScore = BigDecimal.valueOf(clamp(total)).setScale(2, RoundingMode.HALF_UP);

        return new Result(round(architecture), round(structure), round(quality), round(dependency), totalScore);
    }

    private static int round(double value) {
        return (int) Math.round(clamp(value));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(100.0, value));
    }

    private static int countDependencies(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) {
            return 0;
        }
        try {
            List<String> deps = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {});
            return deps.size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 评分结果：四维度分项（取整）+ 加权总分 */
    public static final class Result {
        private final int architecture;
        private final int structure;
        private final int quality;
        private final int dependency;
        private final BigDecimal total;

        Result(int architecture, int structure, int quality, int dependency, BigDecimal total) {
            this.architecture = architecture;
            this.structure = structure;
            this.quality = quality;
            this.dependency = dependency;
            this.total = total;
        }

        public int getArchitecture() {
            return architecture;
        }

        public int getStructure() {
            return structure;
        }

        public int getQuality() {
            return quality;
        }

        public int getDependency() {
            return dependency;
        }

        public BigDecimal getTotal() {
            return total;
        }
    }
}
