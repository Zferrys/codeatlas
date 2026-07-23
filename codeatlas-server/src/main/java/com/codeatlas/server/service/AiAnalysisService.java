package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.ClaudeClient;
import com.codeatlas.engine.ai.DeepSeekClient;
import com.codeatlas.engine.ai.PromptTemplate;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class AiAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AiAnalysisService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final ClassSummaryMapper classSummaryMapper;
    private final InsightService insightService;
    private final AiClient aiClient;

    @Lazy
    @Autowired
    private AiAnalysisService self;

    public AiAnalysisService(ProjectMapper projectMapper, ScanMapper scanMapper,
                              ClassSummaryMapper classSummaryMapper, InsightService insightService) {
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.insightService = insightService;

        String deepseekKey = System.getenv("DEEPSEEK_API_KEY");
        String claudeKey = System.getenv("ANTHROPIC_API_KEY");

        if (deepseekKey != null && !deepseekKey.isEmpty()) {
            this.aiClient = new DeepSeekClient(deepseekKey);
            log.info("AiAnalysisService initialized with DeepSeek client (model: {})", aiClient.getModelName());
        } else if (claudeKey != null && !claudeKey.isEmpty()) {
            this.aiClient = new ClaudeClient(claudeKey);
            log.info("AiAnalysisService initialized with Claude client (model: {})", aiClient.getModelName());
        } else {
            this.aiClient = null;
            log.warn("No AI API key found (DEEPSEEK_API_KEY or ANTHROPIC_API_KEY) — AI analysis will be skipped");
        }
    }

    /**
     * 异步触发架构分析（不阻塞调用方）。
     */
    @Async("aiAnalysisExecutor")
    public void triggerAsync(Long projectId, Long scanId) {
        try {
            analyzeArchitecture(projectId, scanId);
        } catch (Exception e) {
            log.error("AI analysis failed for projectId={}, scanId={}", projectId, scanId, e);
        }
    }

    /**
     * 同步执行架构叙事分析。
     */
    @Timed(value = "ai.analysis.duration", description = "AI architecture analysis duration")
    public InsightEntity analyzeArchitecture(Long projectId, Long scanId) {
        if (aiClient == null) {
            log.info("AI analysis skipped: no AI client available for projectId={}", projectId);
            return null;
        }

        Project project = projectMapper.findById(projectId);
        ScanRecord scan = scanMapper.findById(scanId);
        List<ClassSummaryEntity> classes = classSummaryMapper.findByScanId(scanId);

        if (project == null || scan == null || classes.isEmpty()) {
            log.warn("AI analysis skipped: missing data projectId={}, scanId={}", projectId, scanId);
            return null;
        }

        // 构建 prompt
        Map<String, String> vars = buildPromptVariables(project, scan, classes);
        PromptTemplate template = new PromptTemplate("prompts/architecture-story.md");
        String prompt = template.render(vars);

        if (prompt.isEmpty()) {
            log.warn("AI analysis skipped: empty prompt for projectId={}", projectId);
            return null;
        }

        // 调用 AI（带重试 + 降级）
        AiRequest request = new AiRequest();
        request.setPrompt(prompt);
        request.setSystemPrompt("You are a senior software architect. Analyze codebases and produce insightful, accurate architectural narratives. Always reference specific classes and packages.");
        request.setTemperature(0.3);
        request.setMaxTokens(4096);

        AiResponse response = self.callAiWithResilience(request);
        if (response == null) {
            log.warn("AI analysis failed after retries for projectId={}, scanId={}", projectId, scanId);
            return null;
        }
        log.info("AI analysis completed: projectId={}, tokens={}, latency={}ms",
                projectId, response.getTokensUsed(), response.getLatencyMs());

        // 保存 insight
        InsightEntity insight = new InsightEntity();
        insight.setScanId(scanId);
        insight.setProjectId(projectId);
        insight.setType("ARCH_STORY");
        insight.setTitle("架构叙事 — " + project.getName());
        insight.setContent(response.getContent());
        insight.setConfidence(BigDecimal.valueOf(0.85));
        insight.setSources(toJson(Collections.emptyList()));
        insight.setMetadata(buildMetadata(project, scan, classes, response));
        insightService.saveInsight(insight);

        log.info("AI insight saved: id={}, projectId={}", insight.getId(), projectId);
        return insight;
    }

    /**
     * 带 Resilience4j 熔断、重试、隔离的 AI 调用。
     * 通过 self-injection 确保注解代理生效。
     */
    @CircuitBreaker(name = "aiClient", fallbackMethod = "aiFallback")
    @Retry(name = "aiClient")
    @Bulkhead(name = "aiClient")
    public AiResponse callAiWithResilience(AiRequest request) {
        long start = System.currentTimeMillis();
        AiResponse response = aiClient.chat(request);
        if (response.getLatencyMs() <= 0) {
            response.setLatencyMs(System.currentTimeMillis() - start);
        }
        return response;
    }

    /**
     * 熔断/重试耗尽时的降级方法。
     */
    public AiResponse aiFallback(AiRequest request, Exception e) {
        log.warn("AI call fallback: circuit breaker open or retries exhausted: {}", e.getMessage());
        return null;
    }

    private Map<String, String> buildPromptVariables(Project project, ScanRecord scan, List<ClassSummaryEntity> classes) {
        Map<String, String> vars = new HashMap<>();
        vars.put("projectName", project.getName());
        vars.put("language", project.getLanguage() != null ? project.getLanguage() : "Java");
        vars.put("classCount", String.valueOf(classes.size()));
        vars.put("totalLines", String.valueOf(scan.getTotalLines() != null ? scan.getTotalLines() : 0));

        // 分层分布
        Map<String, Long> layerDist = classes.stream()
                .filter(c -> c.getLayer() != null)
                .collect(Collectors.groupingBy(ClassSummaryEntity::getLayer, Collectors.counting()));
        StringBuilder layerInfo = new StringBuilder();
        for (Map.Entry<String, Long> entry : layerDist.entrySet()) {
            layerInfo.append(String.format("- %s: %d classes\n", entry.getKey(), entry.getValue()));
        }
        vars.put("layerDistribution", layerInfo.toString());

        // 关键类摘要：取有依赖最多的前 15 个类
        List<ClassSummaryEntity> topClasses = classes.stream()
                .sorted((a, b) -> Integer.compare(countDeps(b), countDeps(a)))
                .limit(15)
                .collect(Collectors.toList());
        StringBuilder keyClasses = new StringBuilder();
        for (ClassSummaryEntity cls : topClasses) {
            int depCount = countDeps(cls);
            keyClasses.append(String.format("| %s | %s | %s | %d methods | %d lines | %d deps |\n",
                    cls.getFqn(), cls.getLayer(), cls.getClassType(),
                    cls.getTotalMethods() != null ? cls.getTotalMethods() : 0,
                    cls.getLineCount() != null ? cls.getLineCount() : 0,
                    depCount));
        }
        vars.put("keyClasses", keyClasses.toString());

        // 依赖关系高亮：取前 10 条边
        StringBuilder depHighlights = new StringBuilder();
        int edgeCount = 0;
        Set<String> fqnSet = classes.stream().map(ClassSummaryEntity::getFqn).collect(Collectors.toSet());
        for (ClassSummaryEntity cls : classes) {
            if (edgeCount >= 10) break;
            List<String> deps = parseDeps(cls.getDependencies());
            for (String dep : deps) {
                if (fqnSet.contains(dep) && edgeCount < 10) {
                    depHighlights.append(String.format("%s → %s\n",
                            shorten(cls.getFqn()), shorten(dep)));
                    edgeCount++;
                }
            }
        }
        vars.put("dependencyHighlights", depHighlights.toString());

        return vars;
    }

    private int countDeps(ClassSummaryEntity cls) {
        List<String> deps = parseDeps(cls.getDependencies());
        return deps.size();
    }

    private List<String> parseDeps(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return Collections.emptyList();
        try {
            return OBJECT_MAPPER.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String shorten(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(idx + 1) : fqn;
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private String buildMetadata(Project project, ScanRecord scan, List<ClassSummaryEntity> classes, AiResponse response) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("tokensUsed", response.getTokensUsed());
        meta.put("latencyMs", response.getLatencyMs());
        meta.put("classCount", classes.size());
        meta.put("totalLines", scan.getTotalLines());
        try {
            return OBJECT_MAPPER.writeValueAsString(meta);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
