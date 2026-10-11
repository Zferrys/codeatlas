package com.codeatlas.server.service;

import com.codeatlas.server.entity.ClassSummaryEntity;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 证据验证器 — AI 问答输出的多源交叉验证。
 *
 * <p>三层校验，每层对照一个独立数据源：
 * <ol>
 *   <li>L1 存在性：三形态提取类引用（FQN、项目已知简名、未知 CamelCase 候选），
 *       未知候选对照项目类摘要（MySQL）判定为项目外引用；</li>
 *   <li>L2 证据溯源：类引用必须出现在本轮工具返回结果（会话证据集，多工具多轮）中；</li>
 *   <li>L3 关系一致性："依赖/引用/注入"关系声明必须与类摘要 dependencies 字段一致。</li>
 * </ol>
 *
 * <p>已知边界：dependencies 字段只记录字段类型与参数/返回类型级别的依赖，
 * 不含方法级调用目标，因此 L3 不校验"调用/使用/访问"类声明；
 * 继承/实现关系无 MySQL 字段，不在本层校验范围内。
 */
@Component
public class EvidenceVerifier {

    private static final Logger log = LoggerFactory.getLogger(EvidenceVerifier.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** FQN 形态类引用：com.xxx.Yyy */
    private static final Pattern FQN_PATTERN =
            Pattern.compile("\\b([a-z_][a-z0-9_]*(?:\\.[a-z_][a-z0-9_]*)+\\.[A-Z][A-Za-z0-9_]*)\\b");

    /** 候选类名（CamelCase 形态，用于探测项目类集之外的编造引用） */
    private static final Pattern CANDIDATE_PATTERN = Pattern.compile("\\b([A-Z][A-Za-z0-9_]{3,})\\b");

    /** 类引用 token：简名或 FQN 形态（com.xxx.Yyy），两侧共用 */
    private static final String CLASS_TOKEN = "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*";

    /** 依赖关系声明：A 依赖/引用/注入 B（两侧均支持简名或 FQN，解析不到项目类时跳过） */
    private static final Pattern RELATION_PATTERN = Pattern.compile(
            "(" + CLASS_TOKEN + ")\\s*(?:类\\s*)?(?:直接)?(依赖|引用|注入)(?:于|了)?\\s*(" + CLASS_TOKEN + ")");

    /** 未溯源引用达到此数量即触发修正轮 */
    private static final int UNSOURCED_CORRECTION_THRESHOLD = 2;

    /** 通用技术名词（非项目类名），不计入引用统计 */
    private static final Set<String> TECH_STOPWORDS = new HashSet<>(Arrays.asList(
            "Spring", "SpringBoot", "SpringMVC", "SpringCloud", "MySQL", "Redis", "Neo4j", "MongoDB",
            "Docker", "Maven", "Gradle", "Tomcat", "Jetty", "Hikari", "HikariPool", "Jackson",
            "Fastjson", "Gson", "Lombok", "SLF4J", "Logback", "Log4j", "Swagger", "Knife4j",
            "MyBatis", "MyBatisPlus", "Vue", "React", "Axios", "Vite", "Webpack", "Node", "Nginx",
            "Linux", "Windows", "JavaParser", "JUnit", "Mockito", "TestNG", "Postman", "Git",
            "GitHub", "GitLab", "RESTful", "OAuth", "Kafka", "RabbitMQ", "Elasticsearch",
            "Prometheus", "Grafana", "Nacos", "Sentinel", "Feign", "Netty", "Shiro", "Thymeleaf",
            "Freemarker", "Groovy", "Kotlin", "Scala", "Python", "JavaScript", "TypeScript",
            "WebSocket", "WebFlux", "Markdown", "SwaggerUI",
            "Controller", "Service", "Repository", "Mapper", "Config",
            "Util", "Utils", "Exception", "Entity", "Dto", "Dao", "Vo", "Po",
            "Boot", "Plus", "Pinia", "Kubernetes", "OpenAI", "Servlet", "Dubbo", "ZooKeeper",
            "RocketMQ", "ActiveMQ", "ShardingSphere", "MinIO", "Jenkins", "SonarQube", "Gitee",
            "Vuex", "VueRouter", "ECharts", "Antd", "AntDesign", "RestController", "Actuator",
            "Mybatis", "ThreeJS", "WebGL", "Springboot", "Compose", "Desktop",
            "ObjectMapper", "RestTemplate", "ResponseEntity", "MultipartFile", "Optional", "Stream",
            "CompletableFuture", "StringBuilder", "LocalDateTime", "Collectors", "Pageable",
            "RedisTemplate", "StringRedisTemplate", "CacheManager"));

    /** 通用类名后缀词：作为候选词后缀出现时不视为技术名词（保留编造类名检出） */
    private static final Set<String> GENERIC_CLASS_WORDS = new HashSet<>(Arrays.asList(
            "Controller", "Service", "Repository", "Mapper", "Config", "Util", "Utils",
            "Exception", "Entity", "Dto", "Dao", "Vo", "Po"));

    /**
     * 校验 AI 答案。
     *
     * @param answer   最终答案文本
     * @param evidence 本轮全部工具返回结果（会话证据集）
     * @param classes  项目全量类摘要
     * @return 校验报告；answer/classes 为空时返回空报告
     */
    public VerificationReport verify(String answer, List<String> evidence, List<ClassSummaryEntity> classes) {
        if (answer == null || answer.isEmpty() || classes == null || classes.isEmpty()) {
            return VerificationReport.empty();
        }

        String corpus = evidence == null ? "" : String.join("\n", evidence);
        Set<String> knownSimpleNames = new HashSet<>();
        Map<String, ClassSummaryEntity> byFqn = new HashMap<>();
        Map<String, List<ClassSummaryEntity>> bySimpleName = new HashMap<>();
        for (ClassSummaryEntity cls : classes) {
            if (cls.getFqn() != null) {
                byFqn.put(cls.getFqn(), cls);
            }
            if (cls.getSimpleName() != null) {
                knownSimpleNames.add(cls.getSimpleName());
                bySimpleName.computeIfAbsent(cls.getSimpleName(), k -> new ArrayList<>()).add(cls);
            }
        }

        Set<String> refs = extractClassRefs(answer, knownSimpleNames);
        List<String> unsourced = new ArrayList<>();
        for (String ref : refs) {
            if (!corpus.contains(ref)) {
                unsourced.add(ref);
            }
        }

        List<String> relationMismatches = new ArrayList<>();
        int relationClaimsChecked = checkRelations(answer, byFqn, bySimpleName, relationMismatches);

        if (!unsourced.isEmpty() || !relationMismatches.isEmpty()) {
            log.warn("Evidence anomalies detected: unsourced={}, relationMismatches={}",
                    unsourced, relationMismatches);
        }
        return new VerificationReport(refs.size(), unsourced, relationClaimsChecked, relationMismatches);
    }

    /**
     * 构造修正轮提示词：把异常清单回给模型，要求对照工具数据自查重写。
     */
    public String buildCorrectionPrompt(VerificationReport report) {
        StringBuilder sb = new StringBuilder("你上一条回答未通过证据校验，请对照本轮工具返回的数据修正后重新输出完整回答：\n");
        if (!report.getUnsourced().isEmpty()) {
            sb.append("1. 以下类引用未出现在本轮工具检索结果中：")
                    .append(String.join("、", report.getUnsourced()))
                    .append("。请逐一核对：不能由工具数据支持的引用必须删除或改述。\n");
        }
        if (!report.getRelationMismatches().isEmpty()) {
            sb.append("2. 以下关系声明与项目依赖数据不一致：")
                    .append(String.join("、", report.getRelationMismatches()))
                    .append("。请以工具返回的真实依赖数据为准修正。\n");
        }
        sb.append("要求：仅基于以上工具返回结果作答；无法从检索数据确认的内容，明确写\"无法从当前检索数据确认\"。"
                + "直接输出修正后的完整回答，不要解释本修正请求。");
        return sb.toString();
    }

    // ========== private ==========

    /** 三形态提取：FQN 扫描 + 已知类简名精确扫描 + 未知 CamelCase 候选探测 */
    private Set<String> extractClassRefs(String answer, Set<String> knownSimpleNames) {
        Set<String> refs = new LinkedHashSet<>();

        Matcher fqnMatcher = FQN_PATTERN.matcher(answer);
        while (fqnMatcher.find()) {
            refs.add(fqnMatcher.group(1));
        }

        for (String simple : knownSimpleNames) {
            if (containsAsWord(answer, simple)) {
                refs.add(simple);
            }
        }

        Matcher candidateMatcher = CANDIDATE_PATTERN.matcher(answer);
        while (candidateMatcher.find()) {
            String token = candidateMatcher.group(1);
            if (refs.contains(token)) continue;
            // @ 前缀是注解名（@Autowired 等），不是类引用
            if (candidateMatcher.start(1) > 0 && answer.charAt(candidateMatcher.start(1) - 1) == '@') continue;
            // 全大写视为缩写（API/DTO/HTTP 等），不进入候选
            if (token.equals(token.toUpperCase())) continue;
            if (isTechTerm(token)) continue;
            if (knownSimpleNames.contains(token)) continue;
            if (isFqnSuffixOf(refs, token)) continue;
            // 与已知类名互为子串关系（如前缀/后缀变体）时按宽松处理，避免误报
            if (looksLikeKnownVariant(token, knownSimpleNames)) continue;
            refs.add(token);
        }

        return refs;
    }

    /**
     * 技术名词判定：精确匹配之外，容忍版本号后缀（Vue3→Vue）、缩写后缀（HikariCP→Hikari+CP）
     * 与连写复合（MyBatisPlus→MyBatis+Plus），避免把常见技术名词当编造类名误报。
     * 后缀为通用类名词（Service/Config/Util 等）时不豁免——"技术词前缀+类名后缀"正是
     * 编造类名（如 RedisService）的高发形态，必须保留检出能力。
     */
    private boolean isTechTerm(String token) {
        if (TECH_STOPWORDS.contains(token)) {
            return true;
        }
        String base = token.replaceAll("[0-9]+$", "");
        if (TECH_STOPWORDS.contains(base)) {
            return true;
        }
        for (String stopword : TECH_STOPWORDS) {
            if (token.startsWith(stopword) && token.length() > stopword.length()) {
                String suffix = token.substring(stopword.length());
                if (suffix.matches("[0-9]+|[A-Z]{1,4}")
                        || (TECH_STOPWORDS.contains(suffix) && !GENERIC_CLASS_WORDS.contains(suffix))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 依赖关系声明校验，返回已校验的声明数量，矛盾项写入 mismatches */
    private int checkRelations(String answer, Map<String, ClassSummaryEntity> byFqn,
                               Map<String, List<ClassSummaryEntity>> bySimpleName,
                               List<String> mismatches) {
        int checked = 0;
        Matcher matcher = RELATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            String subjectName = matcher.group(1);
            String verb = matcher.group(2);
            String objectName = matcher.group(3);

            ClassSummaryEntity subject = resolveUnique(subjectName, byFqn, bySimpleName);
            ClassSummaryEntity object = resolveUnique(objectName, byFqn, bySimpleName);
            // 任一侧无法唯一映射到项目类（未知或重名歧义）时跳过，不判定
            if (subject == null || object == null || subject.getFqn().equals(object.getFqn())) {
                continue;
            }

            checked++;
            List<String> dependencies = parseDependencies(subject.getDependencies());
            if (!dependencies.contains(object.getFqn())) {
                mismatches.add(subjectName + " " + verb + " " + objectName);
            }
        }
        return checked;
    }

    private ClassSummaryEntity resolveUnique(String name, Map<String, ClassSummaryEntity> byFqn,
                                             Map<String, List<ClassSummaryEntity>> bySimpleName) {
        ClassSummaryEntity exact = byFqn.get(name);
        if (exact != null) return exact;
        List<ClassSummaryEntity> candidates = bySimpleName.get(name);
        if (candidates != null && candidates.size() == 1) {
            return candidates.get(0);
        }
        return null;
    }

    private List<String> parseDependencies(String dependenciesJson) {
        if (dependenciesJson == null || dependenciesJson.isEmpty() || "[]".equals(dependenciesJson)) {
            return Collections.emptyList();
        }
        try {
            return OBJECT_MAPPER.readValue(dependenciesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private boolean containsAsWord(String text, String word) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(word) + "(?![A-Za-z0-9_])")
                .matcher(text).find();
    }

    private boolean isFqnSuffixOf(Set<String> refs, String token) {
        for (String ref : refs) {
            if (ref.endsWith("." + token)) return true;
        }
        return false;
    }

    private boolean looksLikeKnownVariant(String token, Set<String> knownSimpleNames) {
        for (String known : knownSimpleNames) {
            if (known.contains(token) || token.contains(known)) return true;
        }
        return false;
    }

    /**
     * 校验报告。
     */
    public static class VerificationReport {

        private final int classRefsChecked;
        private final List<String> unsourced;
        private final int relationClaimsChecked;
        private final List<String> relationMismatches;

        VerificationReport(int classRefsChecked, List<String> unsourced,
                           int relationClaimsChecked, List<String> relationMismatches) {
            this.classRefsChecked = classRefsChecked;
            this.unsourced = Collections.unmodifiableList(new ArrayList<>(unsourced));
            this.relationClaimsChecked = relationClaimsChecked;
            this.relationMismatches = Collections.unmodifiableList(new ArrayList<>(relationMismatches));
        }

        static VerificationReport empty() {
            return new VerificationReport(0, Collections.emptyList(), 0, Collections.emptyList());
        }

        public boolean needsCorrection() {
            return unsourced.size() >= UNSOURCED_CORRECTION_THRESHOLD || !relationMismatches.isEmpty();
        }

        public int anomalyCount() {
            return unsourced.size() + relationMismatches.size();
        }

        public List<String> getUnsourced() { return unsourced; }

        public List<String> getRelationMismatches() { return relationMismatches; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("classRefsChecked", classRefsChecked);
            map.put("unsourced", unsourced);
            map.put("relationClaimsChecked", relationClaimsChecked);
            map.put("relationMismatches", relationMismatches);
            map.put("needsCorrection", needsCorrection());
            return map;
        }
    }
}
