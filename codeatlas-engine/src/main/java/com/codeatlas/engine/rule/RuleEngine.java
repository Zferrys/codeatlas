package com.codeatlas.engine.rule;

import com.codeatlas.engine.parser.ClassSummaryResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 纯 Java 规则引擎 — 无 Spring 依赖，由调用方手动实例化。
 * 检查类摘要列表，返回违反规则的违规结果列表。
 */
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public List<ViolationResult> check(List<RuleDefinition> rules,
                                        List<ClassSummaryResult> classes) {
        List<ViolationResult> violations = new ArrayList<>();
        if (rules == null || rules.isEmpty() || classes == null || classes.isEmpty()) {
            return violations;
        }

        Set<String> allFqns = classes.stream()
                .map(ClassSummaryResult::getFqn).collect(Collectors.toSet());

        for (RuleDefinition rule : rules) {
            if (Boolean.FALSE.equals(rule.getIsEnabled())) continue;

            try {
                switch (rule.getName()) {
                    case "no-controller-direct-dao":
                        violations.addAll(checkControllerDirectDao(rule, classes, allFqns));
                        break;
                    case "max-public-methods":
                        violations.addAll(checkMaxPublicMethods(rule, classes));
                        break;
                    case "service-interface-required":
                        violations.addAll(checkServiceInterface(rule, classes));
                        break;
                    case "max-class-line-count":
                        violations.addAll(checkMaxLineCount(rule, classes));
                        break;
                    case "no-star-import":
                        violations.addAll(checkNoStarImport(rule, classes));
                        break;
                    case "no-circular-dependency":
                        violations.addAll(checkNoCircularDependency(rule, classes, allFqns));
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                log.warn("Rule check failed: rule={}, error={}", rule.getName(), e.getMessage());
            }
        }

        return violations;
    }

    private List<ViolationResult> checkControllerDirectDao(RuleDefinition rule,
                                                            List<ClassSummaryResult> classes,
                                                            Set<String> allFqns) {
        List<ViolationResult> violations = new ArrayList<>();
        for (ClassSummaryResult cls : classes) {
            if (!"controller".equalsIgnoreCase(cls.getLayer())) continue;

            List<String> deps = cls.getDependencies();
            if (deps == null) continue;
            for (String dep : deps) {
                String lowerDep = dep.toLowerCase();
                if (lowerDep.contains(".repository.") || lowerDep.contains(".dao.")
                        || lowerDep.contains(".mapper.")) {
                    if (allFqns.contains(dep)) {
                        violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                                "Controller 直接依赖 DAO/Repository: " + shorten(dep),
                                "通过 Service 层封装对 " + shorten(dep) + " 的调用"));
                    }
                }
            }
        }
        return violations;
    }

    private List<ViolationResult> checkMaxPublicMethods(RuleDefinition rule,
                                                         List<ClassSummaryResult> classes) {
        List<ViolationResult> violations = new ArrayList<>();
        int max = parseMaxFromRule(rule.getRuleDefinition(), 20);
        for (ClassSummaryResult cls : classes) {
            if (cls.getPublicMethods() > max) {
                violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                        String.format("公开方法数 %d 超过上限 %d", cls.getPublicMethods(), max),
                        "考虑拆分类或将相关方法提取到独立的 Service 中"));
            }
        }
        return violations;
    }

    private List<ViolationResult> checkServiceInterface(RuleDefinition rule,
                                                         List<ClassSummaryResult> classes) {
        List<ViolationResult> violations = new ArrayList<>();
        Set<String> interfaceNames = classes.stream()
                .filter(c -> "INTERFACE".equalsIgnoreCase(c.getClassType()))
                .map(ClassSummaryResult::getSimpleName)
                .collect(Collectors.toSet());

        for (ClassSummaryResult cls : classes) {
            if (!"service".equalsIgnoreCase(cls.getLayer())) continue;
            if ("INTERFACE".equalsIgnoreCase(cls.getClassType())) continue;

            String name = cls.getSimpleName();
            if (name == null) continue;
            String expectedInterface = name.replace("Impl", "").replace("impl", "");
            if (name.endsWith("Impl") && !interfaceNames.contains(expectedInterface)) {
                violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                        "Service 实现类 " + name + " 缺少对应接口 " + expectedInterface,
                        "为 " + name + " 提取接口 " + expectedInterface));
            }
        }
        return violations;
    }

    private List<ViolationResult> checkMaxLineCount(RuleDefinition rule,
                                                     List<ClassSummaryResult> classes) {
        List<ViolationResult> violations = new ArrayList<>();
        int max = parseMaxFromRule(rule.getRuleDefinition(), 500);
        for (ClassSummaryResult cls : classes) {
            if (cls.getLineCount() > max) {
                violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                        String.format("代码行数 %d 超过上限 %d", cls.getLineCount(), max),
                        "按职责拆分类，单一职责原则 — 每个类只做一件事"));
            }
        }
        return violations;
    }

    private List<ViolationResult> checkNoStarImport(RuleDefinition rule,
                                                      List<ClassSummaryResult> classes) {
        List<ViolationResult> violations = new ArrayList<>();
        for (ClassSummaryResult cls : classes) {
            List<String> imports = cls.getImports();
            if (imports == null) continue;
            for (String imp : imports) {
                if (imp.endsWith(".*")) {
                    violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                            "使用了星号导入: " + imp,
                            "将 " + imp + " 替换为具体的单类导入"));
                    break;
                }
            }
        }
        return violations;
    }

    private List<ViolationResult> checkNoCircularDependency(RuleDefinition rule,
                                                             List<ClassSummaryResult> classes,
                                                             Set<String> allFqns) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Map<String, ClassSummaryResult> classMap = new LinkedHashMap<>();
        for (ClassSummaryResult cls : classes) {
            classMap.put(cls.getFqn(), cls);
            Set<String> filtered = new LinkedHashSet<>();
            List<String> deps = cls.getDependencies();
            if (deps != null) {
                for (String dep : deps) {
                    if (allFqns.contains(dep)) {
                        filtered.add(dep);
                    }
                }
            }
            graph.put(cls.getFqn(), filtered);
        }

        Set<String> whiteSet = new LinkedHashSet<>(graph.keySet());
        Set<String> graySet = new LinkedHashSet<>();
        Set<String> blackSet = new LinkedHashSet<>();
        List<ViolationResult> violations = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        while (!whiteSet.isEmpty()) {
            String current = whiteSet.iterator().next();
            dfsDetect(current, whiteSet, graySet, blackSet, graph,
                    rule, classMap, violations, reported);
        }

        return violations;
    }

    private void dfsDetect(String current, Set<String> whiteSet, Set<String> graySet,
                           Set<String> blackSet, Map<String, Set<String>> graph,
                           RuleDefinition rule,
                           Map<String, ClassSummaryResult> classMap,
                           List<ViolationResult> violations, Set<String> reported) {
        whiteSet.remove(current);
        graySet.add(current);

        for (String neighbor : graph.getOrDefault(current, Collections.emptySet())) {
            if (blackSet.contains(neighbor)) continue;
            if (graySet.contains(neighbor)) {
                if (!reported.contains(current)) {
                    reported.add(current);
                    ClassSummaryResult cls = classMap.get(current);
                    if (cls != null) {
                        violations.add(new ViolationResult(rule.getId(), rule.getSeverity(), cls.getFqn(),
                                "检测到循环依赖: " + shorten(current) + " ⇄ " + shorten(neighbor),
                                "引入接口或提取公共模块解耦 " + shorten(current) + " 和 " + shorten(neighbor)));
                    }
                }
            } else if (whiteSet.contains(neighbor)) {
                dfsDetect(neighbor, whiteSet, graySet, blackSet, graph,
                        rule, classMap, violations, reported);
            }
        }

        graySet.remove(current);
        blackSet.add(current);
    }

    private int parseMaxFromRule(String ruleDefinition, int defaultMax) {
        if (ruleDefinition == null) return defaultMax;
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(ruleDefinition,
                    new TypeReference<Map<String, Object>>() {});
            Object maxObj = map.get("max");
            if (maxObj instanceof Number) {
                return ((Number) maxObj).intValue();
            }
        } catch (Exception ignored) {
        }
        return defaultMax;
    }

    private String shorten(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(idx + 1) : fqn;
    }
}
