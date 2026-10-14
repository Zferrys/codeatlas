package com.codeatlas.server.service;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("EvidenceVerifier 三层证据校验单元测试")
class EvidenceVerifierTest {

    private EvidenceVerifier verifier;

    @BeforeEach
    void setUp() {
        verifier = new EvidenceVerifier();
    }

    @Test
    @DisplayName("答案类引用出现在证据集中时不标记未溯源")
    void verify_classSourcedInEvidence_shouldNotFlag() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));
        List<String> evidence = Collections.singletonList(
                "找到 1 个匹配类:\n1. com.demo.RealService [SERVICE] 方法3 行40");

        EvidenceVerifier.VerificationReport report =
                verifier.verify("RealService 是核心服务类。", evidence, classes);

        assertTrue(report.getUnsourced().isEmpty(), "已溯源的类引用不应被标记");
        assertFalse(report.needsCorrection());
    }

    @Test
    @DisplayName("编造类名（不在项目且不在证据中）超过阈值触发修正")
    void verify_unknownClassesNotInEvidence_shouldFlag() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));
        List<String> evidence = Collections.singletonList("com.demo.RealService");

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "GhostService 与 PhantomService 是核心类。", evidence, classes);

        assertEquals(2, report.getUnsourced().size());
        assertTrue(report.getUnsourced().contains("GhostService"));
        assertTrue(report.getUnsourced().contains("PhantomService"));
        assertTrue(report.needsCorrection());
    }

    @Test
    @DisplayName("单个未溯源引用低于阈值，不触发修正")
    void verify_singleUnsourced_belowThreshold_shouldNotCorrect() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));

        EvidenceVerifier.VerificationReport report =
                verifier.verify("GhostService 是什么？", Collections.emptyList(), classes);

        assertEquals(1, report.getUnsourced().size());
        assertFalse(report.needsCorrection());
    }

    @Test
    @DisplayName("通用技术名词不计入未溯源引用")
    void verify_techStopwords_shouldBeIgnored() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "项目使用 SpringBoot 与 MySQL 构建，通过 Docker 部署，Redis 做缓存。",
                Collections.emptyList(), classes);

        assertTrue(report.getUnsourced().isEmpty(), "技术名词不应被视为类引用："
                + report.getUnsourced());
    }

    @Test
    @DisplayName("技术名词版本/缩写/连写变体不误报（Vue3/Pinia/Spring Boot/HikariCP/MyBatisPlus/Kubernetes）")
    void verify_techTermVariants_shouldNotBeFlagged() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "前端使用 Vue3 与 Pinia 管理状态；后端基于 Spring Boot，持久层用 MyBatisPlus，"
                        + "连接池 HikariCP，部署到 Kubernetes，接入 OpenAI 接口。",
                Collections.emptyList(), classes);

        assertTrue(report.getUnsourced().isEmpty(), "技术名词变体不应误报：" + report.getUnsourced());
        assertFalse(report.needsCorrection());

        EvidenceVerifier.VerificationReport concatReport = verifier.verify(
                "ORM 采用 MybatisPlus 框架。", Collections.emptyList(), classes);
        assertTrue(concatReport.getUnsourced().isEmpty(),
                "连写变体（MybatisPlus）不应误报：" + concatReport.getUnsourced());
    }

    @Test
    @DisplayName("注解名（@ 前缀）与常见 JDK/Spring 类型名不误报")
    void verify_annotationsAndJdkTypes_shouldNotBeFlagged() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "启动类标注 @SpringBootApplication，控制器用 @RequestMapping 与 @Autowired 注入依赖，"
                        + "返回值用 ResponseEntity 包装，Optional 判空，Stream 做集合转换，缓存经 RedisTemplate 读写。",
                Collections.emptyList(), classes);

        assertTrue(report.getUnsourced().isEmpty(), "注解与 JDK 类型名不应误报：" + report.getUnsourced());
        assertFalse(report.needsCorrection());
    }

    @Test
    @DisplayName("FQN 形态引用双通道校验：存在的通过，编造包路径的被标记")
    void verify_fqnForm_shouldCheckBothKnownAndUnknown() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.RealService", "RealService", null));
        List<String> evidence = Collections.singletonList("com.demo.RealService [SERVICE]");

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "com.demo.RealService 与 com.fake.GhostService 均存在。", evidence, classes);

        assertEquals(1, report.getUnsourced().size());
        assertEquals("com.fake.GhostService", report.getUnsourced().get(0));
    }

    @Test
    @DisplayName("关系声明与依赖数据矛盾时标记；一致时通过")
    void verify_relationClaims_shouldCompareWithDependencies() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                classEntity("com.demo.AService", "AService", "[\"com.demo.BService\"]"),
                classEntity("com.demo.BService", "BService", null),
                classEntity("com.demo.CService", "CService", null));
        List<String> evidence = Arrays.asList(
                "com.demo.AService", "com.demo.BService", "com.demo.CService");

        EvidenceVerifier.VerificationReport okReport =
                verifier.verify("AService 依赖 BService。", evidence, classes);
        assertTrue(okReport.getRelationMismatches().isEmpty());
        assertFalse(okReport.needsCorrection());

        EvidenceVerifier.VerificationReport badReport =
                verifier.verify("AService 依赖 CService。", evidence, classes);
        assertEquals(1, badReport.getRelationMismatches().size());
        assertTrue(badReport.getRelationMismatches().get(0).contains("AService"));
        assertTrue(badReport.needsCorrection(), "关系矛盾应直接触发修正");
    }

    @Test
    @DisplayName("FQN 措辞的关系声明同样参与校验：一致通过，矛盾标记")
    void verify_fqnPhrasedRelation_shouldBeChecked() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                classEntity("com.demo.AService", "AService", "[\"com.demo.BService\"]"),
                classEntity("com.demo.BService", "BService", null));
        List<String> evidence = Arrays.asList("com.demo.AService", "com.demo.BService");

        EvidenceVerifier.VerificationReport okReport = verifier.verify(
                "com.demo.AService 依赖 com.demo.BService。", evidence, classes);
        assertTrue(okReport.getRelationMismatches().isEmpty());
        assertEquals(1, okReport.toMap().get("relationClaimsChecked"), "FQN 措辞声明应进入校验计数");

        EvidenceVerifier.VerificationReport badReport = verifier.verify(
                "com.demo.BService 依赖 com.demo.AService。", evidence, classes);
        assertEquals(1, badReport.getRelationMismatches().size(), "FQN 措辞的虚假依赖声明必须被校验");
        assertTrue(badReport.needsCorrection());
    }

    @Test
    @DisplayName("简名重名歧义时跳过关系校验，不误判")
    void verify_ambiguousSimpleName_shouldSkipRelation() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                classEntity("com.x1.PayService", "PayService", null),
                classEntity("com.x2.PayService", "PayService", null),
                classEntity("com.demo.ClientService", "ClientService", null));
        List<String> evidence = Collections.singletonList("com.x1.PayService com.x2.PayService");

        EvidenceVerifier.VerificationReport report =
                verifier.verify("ClientService 依赖 PayService。", evidence, classes);

        assertTrue(report.getRelationMismatches().isEmpty(), "歧义简名必须跳过而非误判");
    }

    @Test
    @DisplayName("答案中已知类的子串变体按宽松处理，不误报")
    void verify_knownVariantSubstring_shouldNotFlag() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.UserServiceImpl", "UserServiceImpl", null));

        EvidenceVerifier.VerificationReport report =
                verifier.verify("UserService 负责用户管理。", Collections.emptyList(), classes);

        assertTrue(report.getUnsourced().isEmpty(), "子串变体不应误报：" + report.getUnsourced());
    }

    @Test
    @DisplayName("空类集或空答案返回空报告，不触发修正")
    void verify_emptyInputs_shouldReturnEmptyReport() {
        EvidenceVerifier.VerificationReport emptyClasses =
                verifier.verify("GhostService", Collections.emptyList(), Collections.emptyList());
        assertFalse(emptyClasses.needsCorrection());

        EvidenceVerifier.VerificationReport emptyAnswer =
                verifier.verify("", Collections.emptyList(),
                        Collections.singletonList(classEntity("com.demo.RealService", "RealService", null)));
        assertFalse(emptyAnswer.needsCorrection());
    }

    @Test
    @DisplayName("修正提示词包含异常明细与自查约束")
    void buildCorrectionPrompt_shouldContainAnomaliesAndConstraints() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                classEntity("com.demo.AService", "AService", null),
                classEntity("com.demo.BService", "BService", null));
        EvidenceVerifier.VerificationReport report = verifier.verify(
                "GhostService 与 PhantomService 是核心类，AService 依赖 BService。",
                Collections.singletonList("com.demo.AService com.demo.BService"), classes);

        String prompt = verifier.buildCorrectionPrompt(report);

        assertTrue(prompt.contains("GhostService"));
        assertTrue(prompt.contains("PhantomService"));
        assertTrue(prompt.contains("工具"));
    }

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    @Test
    @DisplayName("自反关系声明（同类依赖自身）跳过校验，不计数不误判")
    void verify_selfDependencyClaim_shouldSkipRelation() {
        List<ClassSummaryEntity> classes = Collections.singletonList(
                classEntity("com.demo.AService", "AService", "[\"com.demo.BService\"]"));

        EvidenceVerifier.VerificationReport report = verifier.verify(
                "AService 依赖 AService。",
                Collections.singletonList("com.demo.AService"), classes);

        assertEquals(0, report.toMap().get("relationClaimsChecked"), "自反关系应跳过而非判定");
        assertTrue(report.getRelationMismatches().isEmpty());
        assertFalse(report.needsCorrection());
    }

    @Test
    @DisplayName("依赖字段 JSON 非法：解析降级为空依赖并标记关系矛盾")
    void verify_invalidDependenciesJson_shouldTreatAsNoDependenciesAndFlagMismatch() {
        List<ClassSummaryEntity> classes = Arrays.asList(
                classEntity("com.demo.AService", "AService", "{not-json"),
                classEntity("com.demo.BService", "BService", null));
        List<String> evidence = Arrays.asList("com.demo.AService", "com.demo.BService");

        EvidenceVerifier.VerificationReport report =
                verifier.verify("AService 依赖 BService。", evidence, classes);

        assertEquals(1, report.toMap().get("relationClaimsChecked"));
        assertEquals(1, report.getRelationMismatches().size());
        assertTrue(report.getRelationMismatches().get(0).contains("AService 依赖 BService"),
                "非法 JSON 应降级为空依赖列表，导致声明被判矛盾：" + report.getRelationMismatches());
        assertTrue(report.needsCorrection());
    }

    private ClassSummaryEntity classEntity(String fqn, String simpleName, String dependencies) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setFqn(fqn);
        entity.setSimpleName(simpleName);
        entity.setDependencies(dependencies);
        return entity;
    }
}
