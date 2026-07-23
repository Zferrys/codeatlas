package com.codeatlas.server.service;

import com.codeatlas.engine.parser.ClassSummaryResult;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.entity.ViolationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RuleEngine — 架构宪法规则检查")
class RuleEngineTest {

    private RuleEngine engine;
    private List<ConstitutionRuleEntity> rules;

    @BeforeEach
    void setUp() {
        engine = new RuleEngine();
        rules = new ArrayList<>();
    }

    // ---- 辅助方法 ----

    private ConstitutionRuleEntity rule(String name, String severity, String definition) {
        ConstitutionRuleEntity r = new ConstitutionRuleEntity();
        r.setId((long) rules.size() + 1);
        r.setName(name);
        r.setSeverity(severity);
        r.setRuleDefinition(definition);
        r.setIsEnabled(true);
        return r;
    }

    private ClassSummaryResult cls(String fqn, String layer, String classType,
                                    int publicMethods, int lineCount,
                                    List<String> deps, List<String> imports) {
        ClassSummaryResult r = new ClassSummaryResult();
        r.setFqn(fqn);
        r.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        r.setPackageName(fqn.substring(0, fqn.lastIndexOf('.')));
        r.setLayer(layer);
        r.setClassType(classType);
        r.setPublicMethods(publicMethods);
        r.setTotalMethods(publicMethods);
        r.setLineCount(lineCount);
        r.setDependencies(deps != null ? deps : Collections.emptyList());
        r.setImports(imports != null ? imports : Collections.emptyList());
        return r;
    }

    // ==================== no-controller-direct-dao ====================

    @Nested
    @DisplayName("no-controller-direct-dao")
    class NoControllerDirectDao {

        @Test
        @DisplayName("检测 Controller 直接依赖 Repository")
        void shouldDetectControllerDirectDao() {
            rules.add(rule("no-controller-direct-dao", "BLOCKER",
                    "{\"type\":\"forbidden_dependency\",\"from\":\"CONTROLLER\",\"to\":\"REPOSITORY\"}"));

            ClassSummaryResult controller = cls("com.example.controller.UserController", "CONTROLLER",
                    "CLASS", 5, 100,
                    Arrays.asList("com.example.repository.UserRepository", "com.example.service.UserService"),
                    null);
            ClassSummaryResult repo = cls("com.example.repository.UserRepository", "REPOSITORY",
                    "INTERFACE", 3, 50, null, null);

            List<ClassSummaryResult> classes = Arrays.asList(controller, repo);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).getMessage().contains("Controller"));
            assertTrue(violations.get(0).getMessage().contains("UserRepository"));
        }

        @Test
        @DisplayName("Controller 只依赖 Service 不触发违规")
        void shouldNotFlagWhenOnlyServiceDependency() {
            rules.add(rule("no-controller-direct-dao", "BLOCKER", "{}"));

            ClassSummaryResult controller = cls("com.example.controller.UserController", "CONTROLLER",
                    "CLASS", 5, 100,
                    Arrays.asList("com.example.service.UserService"),
                    null);

            List<ClassSummaryResult> classes = Collections.singletonList(controller);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertEquals(0, violations.size());
        }
    }

    // ==================== max-public-methods ====================

    @Nested
    @DisplayName("max-public-methods")
    class MaxPublicMethods {

        @Test
        @DisplayName("公开方法超过上限应被检测")
        void shouldDetectExcessivePublicMethods() {
            rules.add(rule("max-public-methods", "WARN", "{\"type\":\"method_limit\",\"max\":10}"));

            ClassSummaryResult godClass = cls("com.example.service.GodService", "SERVICE",
                    "CLASS", 15, 500, null, null);

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(godClass), 1L, 1L);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).getMessage().contains("15"));
        }

        @Test
        @DisplayName("公开方法未超过上限不触发")
        void shouldNotFlagWhenUnderLimit() {
            rules.add(rule("max-public-methods", "WARN", "{\"type\":\"method_limit\",\"max\":10}"));

            ClassSummaryResult good = cls("com.example.service.SmallService", "SERVICE",
                    "CLASS", 5, 100, null, null);

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(good), 1L, 1L);

            assertEquals(0, violations.size());
        }
    }

    // ==================== no-star-import ====================

    @Nested
    @DisplayName("no-star-import")
    class NoStarImport {

        @Test
        @DisplayName("检测星号导入")
        void shouldDetectStarImport() {
            rules.add(rule("no-star-import", "INFO",
                    "{\"type\":\"forbidden_pattern\",\"pattern\":\"import\\\\s+\\\\w+\\\\.\\\\*\"}"));

            ClassSummaryResult cls = cls("com.example.Foo", "UTIL", "CLASS", 3, 50, null,
                    Arrays.asList("java.util.*", "com.example.Bar"));

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(cls), 1L, 1L);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).getMessage().contains("星号导入"));
        }

        @Test
        @DisplayName("无星号导入不触发")
        void shouldNotFlagWithoutStarImport() {
            rules.add(rule("no-star-import", "INFO", "{}"));

            ClassSummaryResult cls = cls("com.example.Foo", "UTIL", "CLASS", 3, 50, null,
                    Arrays.asList("java.util.List", "java.util.Map", "com.example.Bar"));

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(cls), 1L, 1L);

            assertEquals(0, violations.size());
        }
    }

    // ==================== no-circular-dependency ====================

    @Nested
    @DisplayName("no-circular-dependency")
    class NoCircularDependency {

        @Test
        @DisplayName("检测直接循环依赖 A→B→A")
        void shouldDetectDirectCycle() {
            rules.add(rule("no-circular-dependency", "BLOCKER",
                    "{\"type\":\"no_cycle\",\"scope\":\"module\"}"));

            ClassSummaryResult a = cls("com.example.A", "SERVICE", "CLASS", 5, 100,
                    Collections.singletonList("com.example.B"), null);
            ClassSummaryResult b = cls("com.example.B", "SERVICE", "CLASS", 3, 80,
                    Collections.singletonList("com.example.A"), null);

            List<ClassSummaryResult> classes = Arrays.asList(a, b);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertTrue(violations.size() >= 1, "应检测到至少一个循环依赖");
            assertTrue(violations.get(0).getMessage().contains("循环依赖"));
        }

        @Test
        @DisplayName("无循环依赖不触发")
        void shouldNotFlagWithoutCycle() {
            rules.add(rule("no-circular-dependency", "BLOCKER", "{}"));

            ClassSummaryResult controller = cls("com.example.controller.C", "CONTROLLER", "CLASS", 5, 100,
                    Collections.singletonList("com.example.service.S"), null);
            ClassSummaryResult service = cls("com.example.service.S", "SERVICE", "CLASS", 3, 80,
                    Collections.singletonList("com.example.repository.R"), null);
            ClassSummaryResult repo = cls("com.example.repository.R", "REPOSITORY", "INTERFACE", 2, 30,
                    null, null);

            List<ClassSummaryResult> classes = Arrays.asList(controller, service, repo);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertEquals(0, violations.size());
        }

        @Test
        @DisplayName("自循环 A→A 应被检测")
        void shouldDetectSelfLoop() {
            rules.add(rule("no-circular-dependency", "BLOCKER", "{}"));

            ClassSummaryResult self = cls("com.example.SelfRef", "SERVICE", "CLASS", 3, 50,
                    Collections.singletonList("com.example.SelfRef"), null);

            List<ClassSummaryResult> classes = Collections.singletonList(self);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertTrue(violations.size() >= 1, "自循环应被检测");
        }
    }

    // ==================== service-interface-required ====================

    @Nested
    @DisplayName("service-interface-required")
    class ServiceInterfaceRequired {

        @Test
        @DisplayName("ServiceImpl 缺少接口应被检测")
        void shouldDetectMissingInterface() {
            rules.add(rule("service-interface-required", "ERROR",
                    "{\"type\":\"interface_required\",\"layer\":\"SERVICE\"}"));

            ClassSummaryResult impl = cls("com.example.service.UserServiceImpl", "SERVICE",
                    "CLASS", 8, 200, null, null);

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(impl), 1L, 1L);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).getMessage().contains("缺少对应接口"));
        }

        @Test
        @DisplayName("有对应接口不触发")
        void shouldNotFlagWhenInterfaceExists() {
            rules.add(rule("service-interface-required", "ERROR", "{}"));

            ClassSummaryResult iface = cls("com.example.service.UserService", "SERVICE",
                    "INTERFACE", 5, 30, null, null);
            ClassSummaryResult impl = cls("com.example.service.UserServiceImpl", "SERVICE",
                    "CLASS", 8, 200, null, null);

            List<ClassSummaryResult> classes = Arrays.asList(iface, impl);
            List<ViolationEntity> violations = engine.check(rules, classes, 1L, 1L);

            assertEquals(0, violations.size());
        }
    }

    // ==================== max-class-line-count ====================

    @Nested
    @DisplayName("max-class-line-count")
    class MaxClassLineCount {

        @Test
        @DisplayName("代码行数超限应被检测")
        void shouldDetectExcessiveLines() {
            rules.add(rule("max-class-line-count", "INFO", "{\"type\":\"line_limit\",\"max\":300}"));

            ClassSummaryResult big = cls("com.example.service.BigService", "SERVICE",
                    "CLASS", 10, 500, null, null);

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(big), 1L, 1L);

            assertEquals(1, violations.size());
            assertTrue(violations.get(0).getMessage().contains("500"));
        }

        @Test
        @DisplayName("代码行数未超限不触发")
        void shouldNotFlagWhenUnderLimit() {
            rules.add(rule("max-class-line-count", "INFO", "{\"type\":\"line_limit\",\"max\":300}"));

            ClassSummaryResult small = cls("com.example.service.SmallService", "SERVICE",
                    "CLASS", 5, 150, null, null);

            List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(small), 1L, 1L);

            assertEquals(0, violations.size());
        }
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("空输入不抛异常")
    void shouldHandleEmptyInput() {
        List<ViolationEntity> violations = engine.check(null, null, 1L, 1L);
        assertEquals(0, violations.size());

        violations = engine.check(Collections.emptyList(), Collections.emptyList(), 1L, 1L);
        assertEquals(0, violations.size());
    }

    @Test
    @DisplayName("禁用规则不检查")
    void shouldSkipDisabledRules() {
        ConstitutionRuleEntity disabled = rule("no-star-import", "INFO", "{}");
        disabled.setIsEnabled(false);
        rules.add(disabled);

        ClassSummaryResult cls = cls("com.example.Foo", "UTIL", "CLASS", 3, 50, null,
                Arrays.asList("java.util.*"));

        List<ViolationEntity> violations = engine.check(rules, Collections.singletonList(cls), 1L, 1L);

        assertEquals(0, violations.size());
    }
}
