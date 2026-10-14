package com.codeatlas.engine.rule;

import com.codeatlas.engine.parser.ClassSummaryResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RuleEngine 补充测试 — 补齐既有 RuleEngineTest 未覆盖的防御分支与异常路径。
 */
@DisplayName("RuleEngine — 补充分支（异常容错与深度遍历）")
class RuleEngineExtraTest {

    private RuleEngine engine;
    private List<RuleDefinition> rules;

    @BeforeEach
    void setUp() {
        engine = new RuleEngine();
        rules = new ArrayList<>();
    }

    private RuleDefinition rule(String name, String definition) {
        RuleDefinition r = new RuleDefinition();
        r.setId((long) rules.size() + 1L);
        r.setName(name);
        r.setSeverity("WARN");
        r.setRuleDefinition(definition);
        r.setIsEnabled(true);
        return r;
    }

    private ClassSummaryResult cls(String fqn, String layer, String classType,
                                   int publicMethods, int lineCount,
                                   List<String> deps, List<String> imports) {
        ClassSummaryResult r = new ClassSummaryResult();
        r.setFqn(fqn);
        r.setSimpleName(fqn == null ? null : fqn.substring(fqn.lastIndexOf('.') + 1));
        r.setPackageName(fqn == null ? null : fqn.substring(0, Math.max(fqn.lastIndexOf('.'), 0)));
        r.setLayer(layer);
        r.setClassType(classType);
        r.setPublicMethods(publicMethods);
        r.setTotalMethods(publicMethods);
        r.setLineCount(lineCount);
        r.setDependencies(deps);
        r.setImports(imports);
        return r;
    }

    // ==================== no-controller-direct-dao 分支 ====================

    @Test
    @DisplayName("Controller 的 dependencies 为 null 时跳过不抛 NPE")
    void controllerWithNullDependencies_shouldBeSkipped() {
        rules.add(rule("no-controller-direct-dao", "{}"));
        ClassSummaryResult controller = cls("com.demo.controller.C", "CONTROLLER", "CLASS",
                3, 50, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(controller));

        assertEquals(0, violations.size());
    }

    @Test
    @DisplayName("依赖 .dao. 与 .mapper. 包且存在于类清单中时同样判违规")
    void controllerDependingOnDaoAndMapper_shouldBeFlagged() {
        rules.add(rule("no-controller-direct-dao", "{}"));
        ClassSummaryResult controller = cls("com.demo.controller.C", "CONTROLLER", "CLASS", 3, 50,
                Arrays.asList("com.demo.dao.UserDao", "com.demo.mapper.UserMapper"), null);
        ClassSummaryResult dao = cls("com.demo.dao.UserDao", "REPOSITORY", "INTERFACE", 2, 20, null, null);
        ClassSummaryResult mapper = cls("com.demo.mapper.UserMapper", "REPOSITORY", "INTERFACE", 2, 20, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(controller, dao, mapper));

        assertEquals(2, violations.size());
        assertTrue(violations.get(0).getMessage().contains("UserDao"));
        assertTrue(violations.get(1).getMessage().contains("UserMapper"));
    }

    @Test
    @DisplayName("依赖含 .repository. 但不在项目类清单（allFqns）中时不判违规")
    void controllerDependingOnExternalRepository_shouldNotBeFlagged() {
        rules.add(rule("no-controller-direct-dao", "{}"));
        ClassSummaryResult controller = cls("com.demo.controller.C", "CONTROLLER", "CLASS", 3, 50,
                Collections.singletonList("org.external.repository.ExternalRepo"), null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(controller));

        assertEquals(0, violations.size(), "外部依赖不属于本项目架构，不应计入违规");
    }

    @Test
    @DisplayName("小写/大小写混合的 layer=controller 也参与检查")
    void mixedCaseLayer_shouldBeChecked() {
        rules.add(rule("no-controller-direct-dao", "{}"));
        ClassSummaryResult controller = cls("com.demo.controller.C", "Controller", "CLASS", 3, 50,
                Collections.singletonList("com.demo.repository.R"), null);
        ClassSummaryResult repo = cls("com.demo.repository.R", "REPOSITORY", "INTERFACE", 2, 20, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(controller, repo));

        assertEquals(1, violations.size());
    }

    // ==================== service-interface-required 分支 ====================

    @Test
    @DisplayName("Service 类 simpleName 为 null 时跳过该项但不影响其他类")
    void serviceWithNullSimpleName_shouldBeSkipped() {
        rules.add(rule("service-interface-required", "{}"));
        ClassSummaryResult nameless = cls(null, "SERVICE", "CLASS", 3, 50, null, null);
        ClassSummaryResult impl = cls("com.demo.service.UserServiceImpl", "SERVICE", "CLASS", 3, 50, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(nameless, impl));

        assertEquals(1, violations.size(), "只有 UserServiceImpl 应被判缺接口");
        assertEquals("com.demo.service.UserServiceImpl", violations.get(0).getClassFqn());
    }

    @Test
    @DisplayName("小写 impl 结尾不触发：endsWith(\"Impl\") 区分大小写（replace 的 impl 分支为死代码）")
    void lowercaseImpl_shouldNotBeFlagged() {
        rules.add(rule("service-interface-required", "{}"));
        ClassSummaryResult impl = cls("com.demo.service.OrderServiceimpl", "SERVICE", "CLASS", 3, 50, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(impl));

        assertEquals(0, violations.size(), "仅以大写 Impl 结尾的实现类参与接口检查");
    }

    // ==================== no-star-import 分支 ====================

    @Test
    @DisplayName("imports 为 null 时跳过不抛 NPE")
    void nullImports_shouldBeSkipped() {
        rules.add(rule("no-star-import", "{}"));
        ClassSummaryResult c = cls("com.demo.Foo", "UTIL", "CLASS", 2, 30, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(c));

        assertEquals(0, violations.size());
    }

    // ==================== no-circular-dependency 深度分支 ====================

    @Test
    @DisplayName("菱形依赖 A→B、A→C、B→C：C 已入黑集时从 A 再次触达应直接跳过")
    void diamondDependency_shouldSkipBlackNode() {
        rules.add(rule("no-circular-dependency", "{}"));
        ClassSummaryResult a = cls("com.demo.A", "SERVICE", "CLASS", 2, 30,
                Arrays.asList("com.demo.B", "com.demo.C"), null);
        ClassSummaryResult b = cls("com.demo.B", "SERVICE", "CLASS", 2, 30,
                Collections.singletonList("com.demo.C"), null);
        ClassSummaryResult c = cls("com.demo.C", "SERVICE", "CLASS", 2, 30, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(a, b, c));

        assertEquals(0, violations.size(), "无环的菱形依赖不应误报");
    }

    @Test
    @DisplayName("同一节点重复指向同一条回边（B→A 出现两次）：去重后 B 只报告一次")
    void repeatedBackEdgeFromSameNode_shouldReportOnce() {
        rules.add(rule("no-circular-dependency", "{}"));
        ClassSummaryResult a = cls("com.demo.A", "SERVICE", "CLASS", 2, 30,
                Collections.singletonList("com.demo.B"), null);
        ClassSummaryResult b = cls("com.demo.B", "SERVICE", "CLASS", 2, 30,
                Arrays.asList("com.demo.A", "com.demo.A"), null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(a, b));

        assertEquals(1, violations.size(), "reported 去重后 B 只应报告一次");
        assertEquals("com.demo.B", violations.get(0).getClassFqn(),
                "违规归属为探测到回边的当前节点 B");
        assertTrue(violations.get(0).getMessage().contains("循环依赖"));
    }

    @Test
    @DisplayName("两条独立回路共享节点 A（A→B→A 与 A→C→A）：B、C 各报告一次")
    void twoIndependentCyclesSharingNode_shouldReportEachDetector() {
        rules.add(rule("no-circular-dependency", "{}"));
        ClassSummaryResult a = cls("com.demo.A", "SERVICE", "CLASS", 2, 30,
                Arrays.asList("com.demo.B", "com.demo.C"), null);
        ClassSummaryResult b = cls("com.demo.B", "SERVICE", "CLASS", 2, 30,
                Collections.singletonList("com.demo.A"), null);
        ClassSummaryResult c = cls("com.demo.C", "SERVICE", "CLASS", 2, 30,
                Collections.singletonList("com.demo.A"), null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(a, b, c));

        assertEquals(2, violations.size());
        assertEquals("com.demo.B", violations.get(0).getClassFqn());
        assertEquals("com.demo.C", violations.get(1).getClassFqn());
    }

    @Test
    @DisplayName("无包名前缀的类名（fqn 无点号）参与循环检测：shorten 原样返回类名")
    void fqnWithoutPackage_shouldBeShortenedAsIs() {
        rules.add(rule("no-circular-dependency", "{}"));
        ClassSummaryResult self = cls("SelfRef", "SERVICE", "CLASS", 2, 30,
                Collections.singletonList("SelfRef"), null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(self));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("SelfRef"));
        assertTrue(violations.get(0).getSuggestion().contains("SelfRef"));
    }

    @Test
    @DisplayName("循环检测中 dependencies 为 null 的节点视为无出边")
    void nullDependenciesInCycleCheck_shouldBeTreatedAsLeaf() {
        rules.add(rule("no-circular-dependency", "{}"));
        ClassSummaryResult leaf = cls("com.demo.Leaf", "SERVICE", "CLASS", 2, 30, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(leaf));

        assertEquals(0, violations.size());
    }

    // ==================== 规则执行异常容错 ====================

    @Test
    @DisplayName("规则检查内部抛异常（fqn 为 null 触发 NPE）时被捕获：不中断后续规则，violations 不含脏数据")
    void internalException_shouldBeCaughtAndNotBreakOtherRules() {
        rules.add(rule("no-circular-dependency", "{}"));
        // fqn=null + dependencies=[null]：DFS 内 shorten(null) 抛 NPE，必须被 check 的 catch 吞掉
        // publicMethods=1 保证该对象在 max-public-methods（max=1）下不产生额外违规
        ClassSummaryResult broken = cls(null, "SERVICE", "CLASS", 1, 30,
                Collections.singletonList(null), null);
        rules.add(rule("max-public-methods", "{\"max\":1}"));
        ClassSummaryResult tooMany = cls("com.demo.service.Big", "SERVICE", "CLASS", 5, 100, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(broken, tooMany));

        assertEquals(1, violations.size(), "异常规则被跳过，后续 max-public-methods 规则仍应生效");
        assertEquals("com.demo.service.Big", violations.get(0).getClassFqn());
        assertTrue(violations.get(0).getMessage().contains("5"));
    }

    // ==================== 早返回列表可变性 / 接口名单过滤 / 行数边界（变异补杀） ====================

    @Test
    @DisplayName("空规则或空类列表触发早返回：返回真实可变的 ArrayList，调用方可继续追加")
    void check_earlyReturn_shouldReturnMutableList() {
        ClassSummaryResult cls = cls("com.demo.Foo", "UTIL", "CLASS", 2, 30, null, null);

        List<ViolationResult> fromEmptyRules = engine.check(rules, Collections.singletonList(cls));
        fromEmptyRules.add(new ViolationResult(97L, "WARN", "com.demo.Manual1", "手工追加1", "验证可变性"));
        assertEquals(1, fromEmptyRules.size(), "早返回必须是可变列表（Collections.emptyList 不可追加）");

        rules.add(rule("no-star-import", "{}"));
        List<ViolationResult> fromEmptyClasses = engine.check(rules, Collections.emptyList());
        fromEmptyClasses.add(new ViolationResult(98L, "WARN", "com.demo.Manual2", "手工追加2", "验证可变性"));
        assertEquals(1, fromEmptyClasses.size());
        assertEquals("com.demo.Manual2", fromEmptyClasses.get(0).getClassFqn());
    }

    @Test
    @DisplayName("接口名单只收真实 INTERFACE：同名普通类不能顶替接口契约，FooImpl 仍判缺接口")
    void serviceImplWithSameNamedPlainClass_shouldStillBeFlagged() {
        rules.add(rule("service-interface-required", "{}"));
        ClassSummaryResult impl = cls("com.demo.service.FooImpl", "SERVICE", "CLASS", 3, 50, null, null);
        ClassSummaryResult sameNamedPlainClass = cls("com.demo.service.Foo", "SERVICE", "CLASS", 3, 50, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(impl, sameNamedPlainClass));

        assertEquals(1, violations.size(), "普通类 Foo 不是接口，不能被计入 interfaceNames");
        assertEquals("com.demo.service.FooImpl", violations.get(0).getClassFqn());
        assertTrue(violations.get(0).getMessage().contains("缺少对应接口 Foo"));
    }

    @Test
    @DisplayName("checkMaxLineCount 边界：行数恰好等于上限不违规，超出 1 行才违规")
    void checkMaxLineCount_atExactLimit_shouldNotFlag() {
        rules.add(rule("max-class-line-count", "{\"max\":10}"));
        ClassSummaryResult atLimit = cls("com.demo.service.Exact", "SERVICE", "CLASS", 2, 10, null, null);
        ClassSummaryResult overLimit = cls("com.demo.service.Over", "SERVICE", "CLASS", 2, 11, null, null);

        List<ViolationResult> atLimitResult = engine.check(rules, Collections.singletonList(atLimit));
        assertEquals(0, atLimitResult.size(), "lineCount == max 时 > 判定为 false，不应违规");

        List<ViolationResult> overLimitResult = engine.check(rules, Collections.singletonList(overLimit));
        assertEquals(1, overLimitResult.size());
    }

    private static Method shortenMethod() throws Exception {
        Method method = RuleEngine.class.getDeclaredMethod("shorten", String.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    @DisplayName("shorten 边界：点位于下标 0 或不含点时原样返回，完整 FQN 只取末段（反射驱动）")
    void shorten_boundaries_shouldKeepExactOutput() throws Exception {
        assertEquals(".Foo", shortenMethod().invoke(engine, ".Foo"),
                "lastIndexOf('.') == 0 时不截断");
        assertEquals("SelfRef", shortenMethod().invoke(engine, "SelfRef"));
        assertEquals("B", shortenMethod().invoke(engine, "com.a.B"),
                "取末段必须从索引+1 处开始，不能吞掉首字符");
    }

    // ==================== parseMaxFromRule 分支 ====================

    @Test
    @DisplayName("ruleDefinition 为非法 JSON 时回退默认上限（max-public-methods → 20）")
    void invalidJsonDefinition_shouldFallbackToDefaultMax() {
        rules.add(rule("max-public-methods", "这不是JSON"));
        ClassSummaryResult cls = cls("com.demo.service.God", "SERVICE", "CLASS", 21, 100, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("超过上限 20"), "默认上限应为 20");
    }

    @Test
    @DisplayName("ruleDefinition 为 null 时回退默认上限（max-class-line-count → 500）")
    void nullDefinition_shouldFallbackToDefaultMax() {
        rules.add(rule("max-class-line-count", null));
        ClassSummaryResult cls = cls("com.demo.service.Big", "SERVICE", "CLASS", 2, 501, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("超过上限 500"), "默认上限应为 500");
    }

    @Test
    @DisplayName("max 字段不是数字（字符串）时回退默认上限")
    void nonNumericMax_shouldFallbackToDefaultMax() {
        rules.add(rule("max-public-methods", "{\"max\":\"many\"}"));
        ClassSummaryResult cls = cls("com.demo.service.God", "SERVICE", "CLASS", 21, 100, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(1, violations.size());
        assertTrue(violations.get(0).getMessage().contains("超过上限 20"));
    }

    @Test
    @DisplayName("max 字段为 JSON 小数时按 intValue 截断（10.9 → 10）")
    void fractionalMax_shouldBeTruncated() {
        rules.add(rule("max-public-methods", "{\"max\":10.9}"));
        ClassSummaryResult over = cls("com.demo.service.God", "SERVICE", "CLASS", 11, 100, null, null);
        ClassSummaryResult atLimit = cls("com.demo.service.Fit", "SERVICE", "CLASS", 10, 100, null, null);

        List<ViolationResult> violations = engine.check(rules, Arrays.asList(over, atLimit));

        assertEquals(1, violations.size(), "10.9 截断为 10：11 违规、10 不违规");
        assertEquals("com.demo.service.God", violations.get(0).getClassFqn());
    }

    // ==================== 未知规则与禁用规则 ====================

    @Test
    @DisplayName("未知规则名走 default 分支：不产生违规也不抛异常")
    void unknownRuleName_shouldBeIgnored() {
        rules.add(rule("some-unknown-rule", "{}"));
        ClassSummaryResult cls = cls("com.demo.Foo", "UTIL", "CLASS", 300, 9999, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(0, violations.size());
    }

    @Test
    @DisplayName("isEnabled 为 null（未显式设置）时规则照常执行")
    void nullIsEnabled_shouldStillRunRule() {
        RuleDefinition rule = rule("max-public-methods", "{\"max\":2}");
        rule.setIsEnabled(null);
        rules.add(rule);
        ClassSummaryResult cls = cls("com.demo.service.Big", "SERVICE", "CLASS", 3, 100, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(1, violations.size(), "仅 Boolean.FALSE 才跳过，null 应视为启用");
        assertNotNull(violations.get(0).getSeverity());
    }

    @Test
    @DisplayName("空规则列表或空类列表均返回空结果且不抛异常")
    void emptyInputs_shouldReturnEmpty() {
        assertTrue(engine.check(rules, Collections.singletonList(new ClassSummaryResult())).isEmpty());
        rules.add(rule("no-star-import", "{}"));
        assertTrue(engine.check(rules, Collections.emptyList()).isEmpty());
    }

    @Test
    @DisplayName("违规行内容完整：message/suggestion 均为具体文案而非占位")
    void violationContent_shouldBeSpecific() {
        rules.add(rule("max-class-line-count", "{\"max\":10}"));
        ClassSummaryResult cls = cls("com.demo.service.Big", "SERVICE", "CLASS", 2, 11, null, null);

        List<ViolationResult> violations = engine.check(rules, Collections.singletonList(cls));

        assertEquals(1, violations.size());
        assertEquals("代码行数 11 超过上限 10", violations.get(0).getMessage());
        assertEquals("按职责拆分类，单一职责原则 — 每个类只做一件事", violations.get(0).getSuggestion());
        assertFalse(violations.get(0).getMessage().contains("null"));
    }
}
