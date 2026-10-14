package com.codeatlas.server.util;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("HealthScoreCalculator 单元测试（四维度分项 + 加权总分 + 边界档位）")
class HealthScoreCalculatorTest {

    // ========== 测试数据构造 ==========

    private static ClassSummaryEntity clazz(String layer, Integer totalMethods, String dependencies) {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn("com.demo.Cls");
        cls.setLayer(layer);
        cls.setTotalMethods(totalMethods);
        cls.setDependencies(dependencies);
        return cls;
    }

    private static List<ClassSummaryEntity> repeated(String layer, Integer methods, String deps, int count) {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            classes.add(clazz(layer, methods, deps));
        }
        return classes;
    }

    private static String depsJson(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"com.demo.Dep").append(i).append('"');
        }
        return sb.append(']').toString();
    }

    private static int qualityOf(Integer methods) {
        return HealthScoreCalculator.compute(1, 0, repeated("service", methods, depsJson(2), 1)).getQuality();
    }

    private static int dependencyOf(String deps) {
        return HealthScoreCalculator.compute(1, 0, repeated("service", 10, deps, 1)).getDependency();
    }

    // ========== 前置守卫 ==========

    @Test
    @DisplayName("totalClasses <= 0：四维与总分全为 0.00，不看类数据")
    void compute_nonPositiveTotalClasses_shouldReturnZeros() {
        List<ClassSummaryEntity> classes = repeated("service", 10, depsJson(2), 4);
        for (int totalClasses : new int[] {0, -1, -100}) {
            HealthScoreCalculator.Result result = HealthScoreCalculator.compute(totalClasses, 3, classes);
            assertEquals(0, result.getArchitecture());
            assertEquals(0, result.getStructure());
            assertEquals(0, result.getQuality());
            assertEquals(0, result.getDependency());
            assertEquals(new BigDecimal("0.00"), result.getTotal());
        }
    }

    @Test
    @DisplayName("classes 为 null 或空列表：四维与总分全为 0.00")
    void compute_nullOrEmptyClasses_shouldReturnZeros() {
        HealthScoreCalculator.Result nullResult = HealthScoreCalculator.compute(5, 2, null);
        assertEquals(0, nullResult.getArchitecture());
        assertEquals(0, nullResult.getStructure());
        assertEquals(0, nullResult.getQuality());
        assertEquals(0, nullResult.getDependency());
        assertEquals(new BigDecimal("0.00"), nullResult.getTotal());

        HealthScoreCalculator.Result emptyResult = HealthScoreCalculator.compute(5, 2, Collections.emptyList());
        assertEquals(0, emptyResult.getArchitecture());
        assertEquals(0, emptyResult.getStructure());
        assertEquals(0, emptyResult.getQuality());
        assertEquals(0, emptyResult.getDependency());
        assertEquals(new BigDecimal("0.00"), emptyResult.getTotal());
    }

    // ========== 架构合规维度（每违规扣 5，上限 40） ==========

    @Test
    @DisplayName("架构合规：无违例 100，每违规扣 5 分，40 分封顶")
    void compute_violations_shouldDeductFiveEachUpToForty() {
        List<ClassSummaryEntity> classes = repeated("service", 10, depsJson(2), 4);
        assertEquals(100, HealthScoreCalculator.compute(4, 0, classes).getArchitecture());
        assertEquals(85, HealthScoreCalculator.compute(4, 3, classes).getArchitecture());
        assertEquals(65, HealthScoreCalculator.compute(4, 7, classes).getArchitecture());
        assertEquals(60, HealthScoreCalculator.compute(4, 8, classes).getArchitecture());
        assertEquals(60, HealthScoreCalculator.compute(4, 100, classes).getArchitecture());
    }

    // ========== 代码结构维度（已识别分层占比） ==========

    @Test
    @DisplayName("代码结构：null 与 UNKNOWN 层不计入，占比 3/5 → 60")
    void compute_structure_shouldCountOnlyKnownLayers() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                clazz("service", 10, depsJson(2)),
                clazz("controller", 10, depsJson(2)),
                clazz(null, 10, depsJson(2)),
                clazz("UNKNOWN", 10, depsJson(2)),
                clazz("controller", 10, depsJson(2)));

        HealthScoreCalculator.Result result = HealthScoreCalculator.compute(5, 0, classes);

        assertEquals(100, result.getArchitecture());
        assertEquals(60, result.getStructure());
        assertEquals(100, result.getQuality());
        assertEquals(100, result.getDependency());
        assertEquals(new BigDecimal("88.00"), result.getTotal());
    }

    @Test
    @DisplayName("代码结构：全部无分层 → 0 分，总分只含其余三维")
    void compute_structure_allUnlayered_shouldReturnZero() {
        HealthScoreCalculator.Result result =
                HealthScoreCalculator.compute(2, 0, repeated(null, 10, depsJson(2), 2));

        assertEquals(0, result.getStructure());
        assertEquals(new BigDecimal("70.00"), result.getTotal());
    }

    // ========== 代码质量维度（平均方法数 5-15 满分） ==========

    @Test
    @DisplayName("代码质量：平均方法数 5 与 15 为满分档（含边界）")
    void compute_quality_shouldGiveFullScoreWithinFiveToFifteen() {
        assertEquals(100, qualityOf(5));
        assertEquals(100, qualityOf(10));
        assertEquals(100, qualityOf(15));
    }

    @Test
    @DisplayName("代码质量：不足 5 每少 1 扣 10 分，超出 15 每多 1 扣 3 分")
    void compute_quality_shouldPenalizeOutsideRange() {
        assertEquals(90, qualityOf(4));
        assertEquals(50, qualityOf(0));
        assertEquals(97, qualityOf(16));
        assertEquals(85, qualityOf(20));
        assertEquals(70, qualityOf(25));
    }

    @Test
    @DisplayName("代码质量：极端偏差扣穿 0 分时钳制为 0")
    void compute_quality_shouldClampAtZero() {
        assertEquals(0, qualityOf(60));
    }

    @Test
    @DisplayName("代码质量档位下界：平均恰 5 个方法按满分档计入加权总分（85.00）")
    void compute_qualityExactlyAtFive_shouldContributeFullScoreToTotal() {
        // 单看 quality 分项会被 round(clamp(...)) 掩盖，必须用非饱和的加权总分观测档位边界：
        // 结构与依赖维度取非满分值，使总分远离 100 的钳制上限
        List<ClassSummaryEntity> classes = new ArrayList<>();
        classes.add(clazz("service", 5, depsJson(2)));
        classes.add(clazz(null, 5, depsJson(2)));

        HealthScoreCalculator.Result result = HealthScoreCalculator.compute(2, 0, classes);

        assertEquals(100, result.getQuality());
        assertEquals(50, result.getStructure());
        assertEquals(new BigDecimal("85.00"), result.getTotal());
    }

    @Test
    @DisplayName("代码质量：totalMethods 为 null 按 0 计（不拋 NPE）")
    void compute_quality_nullMethodsCountedAsZero() {
        assertEquals(50, qualityOf(null));
    }

    // ========== 依赖健康维度（平均依赖数 1-20 满分） ==========

    @Test
    @DisplayName("依赖健康：平均依赖数 1 与 20 为满分档（含边界）")
    void compute_dependency_shouldGiveFullScoreWithinOneToTwenty() {
        assertEquals(100, dependencyOf(depsJson(1)));
        assertEquals(100, dependencyOf(depsJson(20)));
    }

    @Test
    @DisplayName("依赖健康：超过 20 每多 1 扣 2 分，扣穿后钳制为 0")
    void compute_dependency_shouldPenalizeAboveTwenty() {
        assertEquals(98, dependencyOf(depsJson(21)));
        assertEquals(90, dependencyOf(depsJson(25)));
        assertEquals(0, dependencyOf(depsJson(100)));
    }

    @Test
    @DisplayName("依赖健康档位下界：平均恰 1 个依赖按满分档计入加权总分（85.00）")
    void compute_dependencyExactlyAtOne_shouldContributeFullScoreToTotal() {
        // 同 quality 档位：用非饱和加权总分观测“恰 1 个依赖”的下界边界
        List<ClassSummaryEntity> classes = new ArrayList<>();
        classes.add(clazz("service", 10, depsJson(1)));
        classes.add(clazz(null, 10, depsJson(1)));

        HealthScoreCalculator.Result result = HealthScoreCalculator.compute(2, 0, classes);

        assertEquals(100, result.getDependency());
        assertEquals(50, result.getStructure());
        assertEquals(new BigDecimal("85.00"), result.getTotal());
    }

    @Test
    @DisplayName("依赖健康：无依赖数据（null/空串/[]/非法 JSON）均按 0 依赖得 70 分")
    void compute_dependency_missingDataShouldScoreSeventy() {
        assertEquals(70, dependencyOf(null));
        assertEquals(70, dependencyOf(""));
        assertEquals(70, dependencyOf("[]"));
        assertEquals(70, dependencyOf("   "));
        assertEquals(70, dependencyOf("not-json"));
        assertEquals(70, dependencyOf("[{\"not\":\"a-string-list\"}]"));
    }

    @Test
    @DisplayName("依赖健康：合法 JSON 字符串列表按元素个数计数")
    void compute_dependency_validJsonShouldCountElements() {
        assertEquals(100, dependencyOf(depsJson(2)));
        assertEquals(70, dependencyOf(depsJson(0)));
    }

    // ========== 加权总分与舍入 ==========

    @Test
    @DisplayName("全优项目：四维全 100，总分 100.00")
    void compute_perfectProject_shouldReturnHundredAcrossBoard() {
        HealthScoreCalculator.Result result =
                HealthScoreCalculator.compute(4, 0, repeated("service", 10, depsJson(2), 4));

        assertEquals(100, result.getArchitecture());
        assertEquals(100, result.getStructure());
        assertEquals(100, result.getQuality());
        assertEquals(100, result.getDependency());
        assertEquals(new BigDecimal("100.00"), result.getTotal());
    }

    @Test
    @DisplayName("加权总分：第三位小数按 HALF_UP 进位（75.625 → 75.63）")
    void compute_total_shouldRoundHalfUpAtTwoDecimals() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            classes.add(clazz(i < 3 ? "service" : null, 10, depsJson(2)));
        }

        HealthScoreCalculator.Result result = HealthScoreCalculator.compute(16, 0, classes);

        assertEquals(100, result.getArchitecture());
        assertEquals(19, result.getStructure());
        assertEquals(100, result.getQuality());
        assertEquals(100, result.getDependency());
        assertEquals(new BigDecimal("75.63"), result.getTotal());
    }

    @Test
    @DisplayName("加权总分：结构占比为循环小数（1/7）时总分正确舍入（74.29）")
    void compute_total_shouldHandleRepeatingStructureRatio() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        classes.add(clazz("service", 10, depsJson(2)));
        for (int i = 0; i < 6; i++) {
            classes.add(clazz(null, 10, depsJson(2)));
        }

        HealthScoreCalculator.Result result = HealthScoreCalculator.compute(7, 0, classes);

        assertEquals(14, result.getStructure());
        assertEquals(new BigDecimal("74.29"), result.getTotal());
    }
}
