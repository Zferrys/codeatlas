package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class ImpactAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ImpactAnalysisService.class);
    private static final int MAX_BFS_DEPTH = 5;
    private static final int MAX_INPUT_LENGTH = 2000;

    private final AiAnalysisService aiAnalysisService;
    private final ProjectService projectService;
    private final ScanService scanService;

    public ImpactAnalysisService(AiAnalysisService aiAnalysisService,
                                  ProjectService projectService,
                                  ScanService scanService) {
        this.aiAnalysisService = aiAnalysisService;
        this.projectService = projectService;
        this.scanService = scanService;
    }

    /**
     * 变更影响分析：BFS 遍历依赖链 + AI 风险评估。
     */
    public Map<String, Object> analyzeImpact(Long projectId, String targetClass,
                                              String changeType, String targetMethod,
                                              String description) {
        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        ScanRecord scan = scanService.getLatestScanEntity(projectId);
        if (scan == null) {
            throw new BusinessException(400, "请先触发一次扫描");
        }

        List<ClassSummaryEntity> classes = scanService.getClassSummaries(projectId);
        ClassSummaryEntity target = classes.stream()
                .filter(c -> c.getFqn().equals(targetClass) || c.getSimpleName().equals(targetClass))
                .findFirst().orElse(null);

        List<Map<String, Object>> impactPaths = buildImpactPaths(target, classes);
        int totalImpacted = countImpactedClasses(impactPaths);

        Map<String, Object> result = new HashMap<>();
        result.put("targetClass", target != null ? target.getFqn() : targetClass);
        result.put("changeType", changeType);
        result.put("targetMethod", targetMethod);
        result.put("totalImpacted", totalImpacted);
        result.put("impactPaths", impactPaths);

        if (target != null) {
            try {
                String prompt = buildImpactPrompt(project.getName(), target, classes, impactPaths,
                        changeType, targetMethod, description);
                AiRequest request = new AiRequest();
                request.setPrompt(prompt);
                request.setSystemPrompt(
                        "你是一位资深软件架构师。分析代码变更的影响范围，评估风险，给出建议。用中文回答，保留类名/方法名原文。");
                request.setTemperature(0.3);
                request.setMaxTokens(2048);

                AiResponse response = aiAnalysisService.callAiWithResilience(request);
                if (response != null) {
                    result.put("aiAnalysis", response.getContent());
                }
            } catch (Exception e) {
                log.warn("AI impact analysis failed: {}", e.getMessage());
                result.put("aiAnalysis", "AI 分析暂不可用，以下为静态依赖分析结果");
            }
        }

        return result;
    }

    /**
     * 上下文限定问答：基于项目代码上下文回答用户问题。
     */
    public Map<String, Object> contextualQa(Long projectId, String question, String classFqn) {
        question = sanitizeUserInput(question);

        Project project = projectService.getProjectEntity(projectId);
        if (project == null) {
            throw new BusinessException(404, "项目不存在");
        }

        List<ClassSummaryEntity> classes = scanService.getClassSummaries(projectId);

        StringBuilder context = new StringBuilder();
        context.append("项目: ").append(project.getName()).append("\n");
        context.append("语言: ").append(project.getLanguage() != null ? project.getLanguage() : "Java").append("\n");
        context.append("总类数: ").append(classes.size()).append("\n\n");

        if (classFqn != null && !classFqn.isEmpty()) {
            ClassSummaryEntity targetClass = classes.stream()
                    .filter(c -> c.getFqn().equals(classFqn))
                    .findFirst().orElse(null);
            if (targetClass != null) {
                context.append("当前关注的类:\n");
                context.append(formatClassDetail(targetClass));
                context.append("\n该类的直接依赖:\n");
                List<String> deps = aiAnalysisService.parseDeps(targetClass.getDependencies());
                for (String dep : deps) {
                    ClassSummaryEntity depClass = classes.stream()
                            .filter(c -> c.getFqn().equals(dep))
                            .findFirst().orElse(null);
                    if (depClass != null) {
                        context.append("- ").append(depClass.getSimpleName())
                                .append(" (").append(depClass.getLayer()).append(")\n");
                    }
                }
            }
        }

        String prompt = "## 项目上下文\n" + context + "\n## 用户问题\n" + question
                + "\n\n请基于以上项目上下文回答问题。如果问题超出上下文范围，请说明。回答要具体，引用类名和方法名。用中文回答。";

        try {
            AiRequest request = new AiRequest();
            request.setPrompt(prompt);
            request.setSystemPrompt("你是一位资深软件架构师，正在帮助开发者理解代码库。基于提供的项目上下文准确回答问题。");
            request.setTemperature(0.3);
            request.setMaxTokens(2048);

            AiResponse response = aiAnalysisService.callAiWithResilience(request);
            Map<String, Object> result = new HashMap<>();
            result.put("question", question);
            result.put("classFqn", classFqn);
            result.put("answer", response != null ? response.getContent() : "AI 服务暂不可用，请稍后重试");
            return result;
        } catch (Exception e) {
            log.error("Context QA failed: {}", e.getMessage());
            throw new BusinessException(500, "AI 问答服务暂时不可用，请稍后重试");
        }
    }

    // ========== private helpers ==========

    private List<Map<String, Object>> buildImpactPaths(ClassSummaryEntity target,
                                                        List<ClassSummaryEntity> allClasses) {
        List<Map<String, Object>> paths = new ArrayList<>();
        if (target == null) return paths;

        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        visited.add(target.getFqn());
        queue.add(target.getFqn());
        int depth = 0;

        while (!queue.isEmpty() && depth < MAX_BFS_DEPTH) {
            int levelSize = queue.size();
            for (int i = 0; i < levelSize; i++) {
                String currentFqn = queue.poll();
                for (ClassSummaryEntity cls : allClasses) {
                    if (visited.contains(cls.getFqn())) continue;
                    List<String> deps = aiAnalysisService.parseDeps(cls.getDependencies());
                    if (deps.contains(currentFqn)) {
                        visited.add(cls.getFqn());
                        queue.add(cls.getFqn());
                        Map<String, Object> pathEntry = new HashMap<>();
                        pathEntry.put("source", aiAnalysisService.shorten(cls.getFqn()));
                        pathEntry.put("sourceFqn", cls.getFqn());
                        pathEntry.put("target", aiAnalysisService.shorten(currentFqn));
                        pathEntry.put("targetFqn", currentFqn);
                        pathEntry.put("depth", depth + 1);
                        pathEntry.put("sourceLayer", cls.getLayer());
                        paths.add(pathEntry);
                    }
                }
            }
            depth++;
        }

        return paths;
    }

    private int countImpactedClasses(List<Map<String, Object>> paths) {
        Set<String> classes = new HashSet<>();
        for (Map<String, Object> p : paths) {
            classes.add((String) p.get("sourceFqn"));
            classes.add((String) p.get("targetFqn"));
        }
        return classes.size();
    }

    private String buildImpactPrompt(String projectName, ClassSummaryEntity target,
                                      List<ClassSummaryEntity> allClasses,
                                      List<Map<String, Object>> impactPaths,
                                      String changeType, String targetMethod, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 项目: ").append(projectName).append("\n");
        sb.append("## 目标类: ").append(target.getFqn()).append("\n");
        sb.append("- 分层: ").append(target.getLayer()).append("\n");
        sb.append("- 方法数: ").append(target.getTotalMethods()).append("\n");
        sb.append("- 行数: ").append(target.getLineCount()).append("\n");
        sb.append("\n## 变更类型: ").append(changeType).append("\n");
        if (targetMethod != null) {
            sb.append("## 目标方法: ").append(targetMethod).append("\n");
        }
        if (description != null && !description.isEmpty()) {
            sb.append("## 变更描述: ").append(description).append("\n");
        }
        sb.append("\n## 影响链路（BFS 遍历，depth ≤ 5）\n");
        for (Map<String, Object> path : impactPaths) {
            sb.append("- ").append(path.get("source")).append(" → ").append(path.get("target"))
                    .append(" (depth=").append(path.get("depth")).append(")\n");
        }
        sb.append("\n请分析此变更的风险等级(HIGH/MEDIUM/LOW)、影响范围、以及建议的测试范围。");
        return sb.toString();
    }

    private String formatClassDetail(ClassSummaryEntity cls) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 类名: ").append(cls.getFqn()).append("\n");
        sb.append("- 类型: ").append(cls.getClassType()).append("\n");
        sb.append("- 分层: ").append(cls.getLayer()).append("\n");
        sb.append("- 方法数: ").append(cls.getTotalMethods()).append("\n");
        sb.append("- public 方法数: ").append(cls.getPublicMethods()).append("\n");
        sb.append("- 行数: ").append(cls.getLineCount()).append("\n");
        return sb.toString();
    }

    /**
     * Prompt 注入防护: 限长、过滤控制字符、过滤已知注入模式。
     */
    private String sanitizeUserInput(String input) {
        if (input == null) return "";
        String sanitized = input.length() > MAX_INPUT_LENGTH ? input.substring(0, MAX_INPUT_LENGTH) : input;
        sanitized = sanitized.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        sanitized = sanitized
                .replaceAll("(?i)ignore (all )?(previous|above|prior) instructions?\\s*[:.]?", "[FILTERED]")
                .replaceAll("(?i)system prompt[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)you are now[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)new instructions?[\\s:]*", "[FILTERED]")
                .replaceAll("(?i)forget (everything|all)[\\s:]*", "[FILTERED]");
        return sanitized;
    }
}
