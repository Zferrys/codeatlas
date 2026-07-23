package com.codeatlas.engine.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class JavaParserService {

    private static final Logger log = LoggerFactory.getLogger(JavaParserService.class);

    public List<ClassSummaryResult> analyzeDirectory(Path directory) throws IOException {
        List<Path> javaFiles = Files.walk(directory)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.getFileName().toString().equals("package-info.java"))
                .filter(p -> !p.getFileName().toString().equals("module-info.java"))
                .collect(Collectors.toList());

        // 第一遍: 提取所有类的 FQN, 用于依赖过滤
        Set<String> allFqns = new LinkedHashSet<>();
        List<ClassSummaryResult> results = new ArrayList<>();

        for (Path file : javaFiles) {
            try {
                ClassSummaryResult r = analyzeFile(file, Collections.emptySet());
                if (r != null) {
                    results.add(r);
                    allFqns.add(r.getFqn());
                }
            } catch (Exception e) {
                log.warn("Failed to parse {}: {}", file.getFileName(), e.getMessage());
            }
        }

        // 第二遍: 重新计算依赖，过滤出项目内部依赖
        for (ClassSummaryResult r : results) {
            List<String> filteredDeps = r.getDependencies().stream()
                    .filter(allFqns::contains)
                    .collect(Collectors.toList());
            r.setDependencies(filteredDeps);
        }

        log.info("Analyzed {} files, extracted {} classes", javaFiles.size(), results.size());
        return results;
    }

    private ClassSummaryResult analyzeFile(Path file, Set<String> knownFqns) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);

        String packageName = cu.getPackageDeclaration()
                .map(pd -> pd.getName().asString())
                .orElse("");

        List<String> imports = cu.getImports().stream()
                .map(i -> i.getNameAsString())
                .collect(Collectors.toList());

        ClassSummaryResult result = new ClassSummaryResult();
        result.setPackageName(packageName);
        result.setImports(imports);
        result.setLineCount(Files.readAllLines(file).size());

        cu.getPrimaryType().ifPresent(type -> {
            result.setSimpleName(type.getNameAsString());
            result.setFqn(packageName.isEmpty() ? type.getNameAsString() : packageName + "." + type.getNameAsString());
            result.setAnnotations(annotationStrings(type));

            if (type.isClassOrInterfaceDeclaration()) {
                ClassOrInterfaceDeclaration cid = type.asClassOrInterfaceDeclaration();
                if (cid.isInterface()) {
                    result.setClassType("INTERFACE");
                } else {
                    result.setClassType(cid.isAbstract() ? "ABSTRACT" : "CLASS");
                }
            } else if (type.isEnumDeclaration()) {
                result.setClassType("ENUM");
            } else {
                result.setClassType("CLASS");
            }

            // 方法信息
            List<MethodDeclaration> methods = type.getMethods();
            result.setTotalMethods(methods.size());
            result.setPublicMethods((int) methods.stream().filter(MethodDeclaration::isPublic).count());
            result.setMethodNames(methods.stream().map(m -> m.getNameAsString()).collect(Collectors.toList()));
            result.setFieldCount((int) type.getFields().size());

            // 字段类型
            List<String> fieldTypes = type.getFields().stream()
                    .map(f -> f.getCommonType().asString())
                    .collect(Collectors.toList());
            result.setFieldTypes(fieldTypes);

            // 方法参数类型和返回类型
            Set<String> paramTypes = new LinkedHashSet<>();
            for (MethodDeclaration m : methods) {
                if (!m.getType().isVoidType()) {
                    paramTypes.add(m.getType().asString());
                }
                m.getParameters().forEach(p -> paramTypes.add(p.getType().asString()));
            }

            // 推断架构分层
            result.setLayer(inferLayer(packageName, result.getSimpleName(), result.getAnnotations()));

            // 构建依赖列表 — 从 field types + param types 提取简单类名, 匹配 imports 解析为 FQN
            List<String> dependencies = buildDependencies(imports, fieldTypes, new ArrayList<>(paramTypes), packageName);
            result.setDependencies(dependencies);
        });

        return result;
    }

    private List<String> annotationStrings(TypeDeclaration<?> type) {
        return type.getAnnotations().stream()
                .map(a -> a.getNameAsString())
                .collect(Collectors.toList());
    }

    private String inferLayer(String packageName, String simpleName, List<String> annotations) {
        String lower = (packageName + "." + simpleName).toLowerCase();
        if (lower.contains(".controller") || lower.contains(".controller.")) return "CONTROLLER";
        if (lower.contains(".service.impl") || lower.contains(".serviceimpl")) return "SERVICE";
        if (lower.contains(".service") || lower.contains(".service.")) return "SERVICE";
        if (lower.contains(".mapper") || lower.contains(".dao") || lower.contains(".repository")) return "REPOSITORY";
        if (lower.contains(".entity") || lower.contains(".domain") || lower.contains(".model") || lower.contains(".pojo")) return "DOMAIN";
        if (lower.contains(".dto") || lower.contains(".vo") || lower.contains(".response") || lower.contains(".request")) return "DTO";
        if (lower.contains(".config") || lower.contains(".configuration")) return "CONFIG";
        if (lower.contains(".util") || lower.contains(".utils") || lower.contains(".helper")) return "UTIL";
        if (lower.contains(".security")) return "SECURITY";
        if (lower.contains(".exception")) return "EXCEPTION";
        if (lower.contains(".filter") || lower.contains(".interceptor")) return "FILTER";

        // 注解推断: @RestController/@Controller → CONTROLLER, @Service → SERVICE, @Repository → REPOSITORY
        for (String ann : annotations) {
            String annLower = ann.toLowerCase();
            if (annLower.contains("controller") || annLower.contains("restcontroller")) return "CONTROLLER";
            if (annLower.contains("service")) return "SERVICE";
            if (annLower.contains("repository")) return "REPOSITORY";
            if (annLower.contains("mapper")) return "REPOSITORY";
            if (annLower.contains("component") || annLower.contains("configuration")) return "CONFIG";
            if (annLower.contains("entity") || annLower.contains("domain")) return "DOMAIN";
        }

        return "UNKNOWN";
    }

    private List<String> buildDependencies(List<String> imports, List<String> fieldTypes,
                                            List<String> paramTypes, String packageName) {
        Set<String> deps = new LinkedHashSet<>();

        // 从字段类型提取简单类名
        for (String ft : fieldTypes) {
            String simple = extractSimpleName(ft);
            String fqn = resolveImport(imports, simple, packageName);
            if (fqn != null) deps.add(fqn);
        }

        // 从参数/返回类型提取简单类名
        for (String pt : paramTypes) {
            String simple = extractSimpleName(pt);
            String fqn = resolveImport(imports, simple, packageName);
            if (fqn != null) deps.add(fqn);
        }

        // 过滤掉 JDK 标准库和常见框架类
        deps.removeIf(dep ->
                dep.startsWith("java.") || dep.startsWith("javax.") ||
                dep.startsWith("jakarta.") || dep.startsWith("com.sun.") ||
                dep.startsWith("org.springframework.") || dep.startsWith("org.slf4j.") ||
                dep.startsWith("lombok.") || dep.startsWith("org.junit.") ||
                dep.equals(packageName + "." + dep.substring(dep.lastIndexOf('.') + 1))
        );

        return new ArrayList<>(deps);
    }

    private String extractSimpleName(String type) {
        if (type == null || type.isEmpty()) return null;
        // 处理泛型: List<User> → User
        int angleIdx = type.indexOf('<');
        String base = angleIdx > 0 ? type.substring(0, angleIdx) : type;
        // 处理数组: String[]
        base = base.replace("[]", "").trim();
        // 取最后一个点后面的部分作为简单类名
        int dotIdx = base.lastIndexOf('.');
        return dotIdx > 0 ? base.substring(dotIdx + 1) : base;
    }

    private String resolveImport(List<String> imports, String simpleName, String currentPackage) {
        if (simpleName == null || simpleName.isEmpty()) return null;
        // JDK 的 java.lang.* 默认导入
        if (isJavaLang(simpleName)) return null;
        // 基本类型
        if ("int long double float boolean byte short char void String".contains(simpleName)) return null;

        // 匹配 import 列表
        for (String imp : imports) {
            if (imp.endsWith("." + simpleName)) return imp;
        }
        // 默认同包
        return currentPackage + "." + simpleName;
    }

    private boolean isJavaLang(String simpleName) {
        return "Object Integer Long Double Float Boolean Byte Short Character String Class Enum".contains(simpleName);
    }
}
