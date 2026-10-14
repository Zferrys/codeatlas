package com.codeatlas.engine.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JavaParserService — 目录扫描、AST 提取、分层推断、依赖解析全链路测试。
 * 全部使用 @TempDir 真实写盘，不依赖外部环境。
 */
@DisplayName("JavaParserService — Java 源码目录解析")
class JavaParserServiceTest {

    private final JavaParserService service = new JavaParserService();

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path file = root.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, ClassSummaryResult> byFqn(List<ClassSummaryResult> results) {
        return results.stream()
                .filter(r -> r.getFqn() != null)
                .collect(Collectors.toMap(ClassSummaryResult::getFqn, Function.identity()));
    }

    // ==================== 分层推断 — 包名规则 ====================

    @Test
    @DisplayName("包名分层：controller/service.impl/service/repository/mapper/domain/dto/config/util/security/exception/filter 各归其层")
    void analyzeDirectory_packageBasedLayers_shouldInferCorrectLayers(@TempDir Path root) throws IOException {
        write(root, "com/demo/controller/UserController.java",
                "package com.demo.controller;\npublic class UserController {\n}\n");
        write(root, "com/demo/service/impl/UserServiceImpl.java",
                "package com.demo.service.impl;\npublic class UserServiceImpl {\n}\n");
        write(root, "com/demo/service/OrderService.java",
                "package com.demo.service;\npublic class OrderService {\n}\n");
        write(root, "com/demo/repository/UserRepository.java",
                "package com.demo.repository;\npublic interface UserRepository {\n}\n");
        write(root, "com/demo/mapper/UserMapper.java",
                "package com.demo.mapper;\npublic interface UserMapper {\n}\n");
        write(root, "com/demo/domain/Account.java",
                "package com.demo.domain;\npublic class Account {\n}\n");
        write(root, "com/demo/dto/UserDto.java",
                "package com.demo.dto;\npublic class UserDto {\n}\n");
        write(root, "com/demo/config/AppConfig.java",
                "package com.demo.config;\npublic class AppConfig {\n}\n");
        write(root, "com/demo/util/StrUtil.java",
                "package com.demo.util;\npublic class StrUtil {\n}\n");
        write(root, "com/demo/security/AuthGuard.java",
                "package com.demo.security;\npublic class AuthGuard {\n}\n");
        write(root, "com/demo/exception/BizException.java",
                "package com.demo.exception;\npublic class BizException {\n}\n");
        write(root, "com/demo/filter/AuthFilter.java",
                "package com.demo.filter;\npublic class AuthFilter {\n}\n");

        Map<String, ClassSummaryResult> results = byFqn(service.analyzeDirectory(root));

        assertEquals("CONTROLLER", results.get("com.demo.controller.UserController").getLayer());
        assertEquals("SERVICE", results.get("com.demo.service.impl.UserServiceImpl").getLayer(),
                ".service.impl 优先于 .service 命中");
        assertEquals("SERVICE", results.get("com.demo.service.OrderService").getLayer());
        assertEquals("REPOSITORY", results.get("com.demo.repository.UserRepository").getLayer());
        assertEquals("REPOSITORY", results.get("com.demo.mapper.UserMapper").getLayer());
        assertEquals("DOMAIN", results.get("com.demo.domain.Account").getLayer());
        assertEquals("DTO", results.get("com.demo.dto.UserDto").getLayer());
        assertEquals("CONFIG", results.get("com.demo.config.AppConfig").getLayer());
        assertEquals("UTIL", results.get("com.demo.util.StrUtil").getLayer());
        assertEquals("SECURITY", results.get("com.demo.security.AuthGuard").getLayer());
        assertEquals("EXCEPTION", results.get("com.demo.exception.BizException").getLayer());
        assertEquals("FILTER", results.get("com.demo.filter.AuthFilter").getLayer());
    }

    // ==================== 分层推断 — 注解规则 ====================

    @Test
    @DisplayName("注解分层：无包名线索时按 @RestController/@Service/@Repository/@Mapper/@Component/@Entity 推断")
    void analyzeDirectory_annotationBasedLayers_shouldInferCorrectLayers(@TempDir Path root) throws IOException {
        write(root, "com/demo/app/ApiEntry.java",
                "package com.demo.app;\n@RestController\npublic class ApiEntry {\n}\n");
        write(root, "com/demo/app/WorkItem.java",
                "package com.demo.app;\n@Service\npublic class WorkItem {\n}\n");
        write(root, "com/demo/app/Storage.java",
                "package com.demo.app;\n@Repository\npublic class Storage {\n}\n");
        write(root, "com/demo/app/DaoThing.java",
                "package com.demo.app;\n@Mapper\npublic interface DaoThing {\n}\n");
        write(root, "com/demo/app/Wiring.java",
                "package com.demo.app;\n@Component\npublic class Wiring {\n}\n");
        write(root, "com/demo/app/TableThing.java",
                "package com.demo.app;\n@Entity\npublic class TableThing {\n}\n");
        write(root, "com/demo/app/Nothing.java",
                "package com.demo.app;\npublic class Nothing {\n}\n");
        write(root, "com/demo/app/Multi.java",
                "package com.demo.app;\n@Deprecated\n@Service\npublic class Multi {\n}\n");

        Map<String, ClassSummaryResult> results = byFqn(service.analyzeDirectory(root));

        assertEquals("CONTROLLER", results.get("com.demo.app.ApiEntry").getLayer());
        assertEquals("SERVICE", results.get("com.demo.app.WorkItem").getLayer());
        assertEquals("REPOSITORY", results.get("com.demo.app.Storage").getLayer());
        assertEquals("REPOSITORY", results.get("com.demo.app.DaoThing").getLayer());
        assertEquals("CONFIG", results.get("com.demo.app.Wiring").getLayer());
        assertEquals("DOMAIN", results.get("com.demo.app.TableThing").getLayer());
        assertEquals("UNKNOWN", results.get("com.demo.app.Nothing").getLayer(),
                "无包名线索且无注解时回退 UNKNOWN");
        assertEquals("SERVICE", results.get("com.demo.app.Multi").getLayer(),
                "多注解时首个不匹配也继续遍历后续注解（@Deprecated → @Service）");
    }

    // ==================== 类形态与方法/字段提取 ====================

    @Test
    @DisplayName("类形态：interface/abstract/enum implements/annotation 类型/普通类 均正确分类，方法与字段信息完整")
    void analyzeDirectory_classKindsAndMembers_shouldBeExtracted(@TempDir Path root) throws IOException {
        write(root, "com/demo/kinds/Marker.java",
                "package com.demo.kinds;\npublic interface Marker {\n    void mark();\n}\n");
        write(root, "com/demo/kinds/Base.java",
                "package com.demo.kinds;\npublic abstract class Base {\n    protected int x;\n}\n");
        write(root, "com/demo/kinds/Color.java",
                "package com.demo.kinds;\npublic enum Color implements Marker {\n    RED, GREEN;\n}\n");
        write(root, "com/demo/kinds/Tag.java",
                "package com.demo.kinds;\npublic @interface Tag {\n}\n");
        write(root, "com/demo/kinds/Child.java",
                "package com.demo.kinds;\n"
                        + "import java.util.Date;\n"
                        + "public class Child extends Base implements Marker, Comparable<Child> {\n"
                        + "    private int count;\n"
                        + "    private String[] tags;\n"
                        + "    private java.util.List<String> items;\n"
                        + "    private Date expiresAt;\n"
                        + "    public void run() {\n"
                        + "    }\n"
                        + "    void internal() {\n"
                        + "    }\n"
                        + "    public static int compute(int a, long b) {\n"
                        + "        return 0;\n"
                        + "    }\n"
                        + "    String label() {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n");

        Map<String, ClassSummaryResult> results = byFqn(service.analyzeDirectory(root));

        ClassSummaryResult marker = results.get("com.demo.kinds.Marker");
        assertEquals("INTERFACE", marker.getClassType());
        assertEquals(1, marker.getTotalMethods());
        assertEquals(1, marker.getPublicMethods(), "JavaParser 将接口方法视为隐式 public");
        assertEquals("mark", marker.getMethodNames().get(0));

        ClassSummaryResult base = results.get("com.demo.kinds.Base");
        assertEquals("ABSTRACT", base.getClassType());
        assertEquals(1, base.getFieldCount());
        assertEquals("int", base.getFieldTypes().get(0));

        ClassSummaryResult color = results.get("com.demo.kinds.Color");
        assertEquals("ENUM", color.getClassType());
        assertEquals(0, color.getTotalMethods());
        assertEquals(0, color.getExtendedTypes().size());
        assertEquals(1, color.getImplementedTypes().size());
        assertEquals("com.demo.kinds.Marker", color.getImplementedTypes().get(0),
                "枚举 implements 的同包接口解析为 FQN 并保留（存在于项目类清单）");

        ClassSummaryResult tag = results.get("com.demo.kinds.Tag");
        assertEquals("CLASS", tag.getClassType(), "注解声明走 else 分支按 CLASS 处理");
        assertEquals(0, tag.getExtendedTypes().size());
        assertEquals(0, tag.getImplementedTypes().size());

        ClassSummaryResult child = results.get("com.demo.kinds.Child");
        assertEquals("CLASS", child.getClassType());
        assertEquals(4, child.getTotalMethods());
        assertEquals(2, child.getPublicMethods(), "public 普通方法 + public static 方法");
        assertEquals(4, child.getFieldCount());
        assertEquals("String[]", child.getFieldTypes().get(1), "数组字段类型原样保留");
        assertTrue(child.getMethodNames().containsAll(
                Arrays.asList("run", "internal", "compute", "label")));

        assertEquals(Collections.singletonList("com.demo.kinds.Base"), child.getExtendedTypes());
        assertEquals(Collections.singletonList("com.demo.kinds.Marker"), child.getImplementedTypes(),
                "Comparable 非项目内类：第二遍过滤时剔除，仅保留项目内接口");
        assertFalse(child.getDependencies().contains("java.util.Date"),
                "java.* 依赖必须在 buildDependencies 中被剔除");
    }

    // ==================== 依赖解析与项目内过滤 ====================

    @Test
    @DisplayName("依赖解析：字段/参数简单类名经 import 还原 FQN，同包依赖与外框架依赖被剔除，仅保留项目内类")
    void analyzeDirectory_dependencyResolution_shouldKeepOnlyProjectInternalFqns(@TempDir Path root) throws IOException {
        write(root, "com/demo/controller/UserController.java",
                "package com.demo.controller;\n"
                        + "import com.demo.repository.UserRepository;\n"
                        + "import com.demo.dto.UserDto;\n"
                        + "import org.springframework.stereotype.Component;\n"
                        + "public class UserController {\n"
                        + "    private UserRepository userRepository;\n"
                        + "    private Component marker;\n"
                        + "    public java.util.List<UserDto> listDtos(UserDto filter, String[] names) {\n"
                        + "        return null;\n"
                        + "    }\n"
                        + "}\n");
        write(root, "com/demo/repository/UserRepository.java",
                "package com.demo.repository;\npublic interface UserRepository {\n}\n");
        write(root, "com/demo/dto/UserDto.java",
                "package com.demo.dto;\npublic class UserDto {\n}\n");

        Map<String, ClassSummaryResult> results = byFqn(service.analyzeDirectory(root));
        ClassSummaryResult controller = results.get("com.demo.controller.UserController");

        assertTrue(controller.getDependencies().contains("com.demo.repository.UserRepository"),
                "字段类型经 import 还原为项目内 FQN");
        assertTrue(controller.getDependencies().contains("com.demo.dto.UserDto"),
                "参数类型经 import 还原为项目内 FQN");
        assertEquals(2, controller.getDependencies().size(),
                "org.springframework.* 与同包/未解析类型均被过滤，仅剩两个项目内依赖");
        assertEquals("com.demo.controller.UserController", controller.getFqn());
        assertEquals("UserController", controller.getSimpleName());
        assertEquals("com.demo.controller", controller.getPackageName());
        assertEquals(2, controller.getFieldCount());
        assertEquals(1, controller.getPublicMethods());
    }

    // ==================== 测试类识别 ====================

    @Test
    @DisplayName("测试类识别：路径含 src/test/java 或类名以 Test/Tests/IT 结尾时 layer 覆盖为 TEST")
    void analyzeDirectory_testFileDetection_shouldOverrideLayerToTest(@TempDir Path root) throws IOException {
        write(root, "src/test/java/com/demo/Helper.java",
                "package com.demo;\npublic class Helper {\n}\n");
        write(root, "com/demo/FooTest.java",
                "package com.demo.service;\npublic class FooTest {\n}\n");
        write(root, "com/demo/FooTests.java",
                "package com.demo.service;\npublic class FooTests {\n}\n");
        write(root, "com/demo/FooIT.java",
                "package com.demo.service;\npublic class FooIT {\n}\n");
        write(root, "com/demo/Ab.java",
                "package com.demo.plain;\npublic class Ab {\n}\n");

        Map<String, ClassSummaryResult> results = byFqn(service.analyzeDirectory(root));

        assertEquals("TEST", results.get("com.demo.Helper").getLayer(), "src/test/java 路径优先判定为测试类");
        assertEquals("TEST", results.get("com.demo.service.FooTest").getLayer());
        assertEquals("TEST", results.get("com.demo.service.FooTests").getLayer());
        assertEquals("TEST", results.get("com.demo.service.FooIT").getLayer());
        assertEquals("UNKNOWN", results.get("com.demo.plain.Ab").getLayer(),
                "短类名（<=2 字符）不按测试命名误判，保持包名推断结果");
    }

    // ==================== 文件筛选与容错 ====================

    @Test
    @DisplayName("文件筛选：package-info/module-info 与非 .java 文件被跳过；解析失败的文件被容错跳过不中断整批")
    void analyzeDirectory_shouldSkipSpecialAndUnparseableFiles(@TempDir Path root) throws IOException {
        write(root, "com/demo/Ok.java", "package com.demo;\npublic class Ok {\n}\n");
        write(root, "com/demo/Broken.java", "public class Broken {\n");
        write(root, "com/demo/package-info.java", "package com.demo;\n");
        write(root, "com/demo/module-info.java", "module com.demo {\n}\n");
        write(root, "README.txt", "not java\n");

        List<ClassSummaryResult> results = service.analyzeDirectory(root);

        assertEquals(1, results.size(), "仅 Ok.java 被解析：Broken 解析失败、package-info/module-info/README 被过滤");
        assertEquals("com.demo.Ok", results.get(0).getFqn());
    }

    @Test
    @DisplayName("无包声明与无类型文件：fqn 退化为简单类名；仅注释的文件不产生类型信息且不抛异常")
    void analyzeDirectory_noPackageAndNoType_shouldDegradeGracefully(@TempDir Path root) throws IOException {
        write(root, "NoPkg.java", "public class NoPkg {\n}\n");
        write(root, "com/demo/Comments.java", "// 只有注释，没有类型声明\n");

        List<ClassSummaryResult> results = service.analyzeDirectory(root);

        assertEquals(2, results.size());
        ClassSummaryResult noPkg = byFqn(results).get("NoPkg");
        assertNotNull(noPkg);
        assertEquals("", noPkg.getPackageName());
        assertEquals("NoPkg", noPkg.getSimpleName());

        ClassSummaryResult comments = results.stream()
                .filter(r -> r.getFqn() == null)
                .findFirst().orElseThrow(() -> new AssertionError("应存在无类型的解析结果"));
        assertNull(comments.getSimpleName(), "无类型声明时 simpleName 保持 null");
        assertNull(comments.getDependencies(), "无类型声明时 dependencies 保持 null（第二遍过滤跳过）");
        assertEquals(1, comments.getLineCount());
    }

    @Test
    @DisplayName("行数统计：lineCount 等于文件实际行数")
    void analyzeDirectory_lineCount_shouldMatchFileLines(@TempDir Path root) throws IOException {
        write(root, "com/demo/repository/UserRepository.java",
                "package com.demo.repository;\n\npublic interface UserRepository {\n}\n");

        List<ClassSummaryResult> results = service.analyzeDirectory(root);

        assertEquals(4, results.get(0).getLineCount());
    }

    // ==================== 目录级边界 ====================

    @Test
    @DisplayName("空目录：返回空列表且不抛异常")
    void analyzeDirectory_emptyDirectory_shouldReturnEmptyList(@TempDir Path root) throws IOException {
        assertTrue(service.analyzeDirectory(root).isEmpty());
    }

    @Test
    @DisplayName("目录不存在：Files.walk 抛出 IOException 向外传播")
    void analyzeDirectory_missingDirectory_shouldThrowIOException(@TempDir Path root) {
        Path missing = root.resolve("no-such-dir");

        assertThrows(IOException.class, () -> service.analyzeDirectory(missing));
    }
}
