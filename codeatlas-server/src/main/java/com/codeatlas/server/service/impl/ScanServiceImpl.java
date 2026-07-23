package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.git.GitResult;
import com.codeatlas.engine.parser.ClassSummaryResult;
import com.codeatlas.engine.parser.JavaParserService;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.engine.rule.RuleDefinition;
import com.codeatlas.engine.rule.RuleEngine;
import com.codeatlas.engine.rule.ViolationResult;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.ConstitutionRuleService;
import com.codeatlas.server.service.ScanService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScanServiceImpl implements ScanService {

    private static final Logger log = LoggerFactory.getLogger(ScanServiceImpl.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ScanMapper scanMapper;
    private final ProjectMapper projectMapper;
    private final ClassSummaryMapper classSummaryMapper;
    private final ViolationMapper violationMapper;
    private final AiAnalysisService aiAnalysisService;
    private final ConstitutionRuleService constitutionRuleService;
    private final GitService gitService;
    private final JavaParserService javaParserService;
    private final ApplicationEventPublisher eventPublisher;

    public ScanServiceImpl(ScanMapper scanMapper, ProjectMapper projectMapper,
                           ClassSummaryMapper classSummaryMapper,
                           ViolationMapper violationMapper,
                           AiAnalysisService aiAnalysisService,
                           ConstitutionRuleService constitutionRuleService,
                           ApplicationEventPublisher eventPublisher) {
        this.scanMapper = scanMapper;
        this.projectMapper = projectMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.violationMapper = violationMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.constitutionRuleService = constitutionRuleService;
        this.gitService = new GitService();
        this.javaParserService = new JavaParserService();
        this.eventPublisher = eventPublisher;
    }

    @Override
    public ScanVO triggerScan(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        // 检查是否有正在运行的扫描
        ScanRecord latest = scanMapper.findLatestByProjectId(projectId);
        if (latest != null && "RUNNING".equals(latest.getStatus())) {
            throw new BusinessException(ErrorCode.SCAN_ALREADY_RUNNING, "已有扫描正在运行");
        }

        // 创建扫描记录
        ScanRecord scan = new ScanRecord();
        scan.setProjectId(projectId);
        scan.setStatus("RUNNING");
        scan.setStartedAt(LocalDateTime.now());
        scanMapper.insert(scan);

        long startTime = System.currentTimeMillis();
        int totalClasses = 0;
        int totalLines = 0;
        int totalViolations = 0;
        String errorMessage = null;
        Path workDir = null;
        boolean cleanupWorkDir = false;

        try {
            emitProgress(projectId, scan.getId(), "CLONING", 10, "开始克隆仓库...");
            if ("GIT_URL".equals(project.getSourceType()) && project.getSourceUrl() != null) {
                workDir = Files.createTempDirectory("codeatlas-scan-");
                cleanupWorkDir = true;
                GitResult gitResult = gitService.cloneRepository(
                        project.getSourceUrl(),
                        project.getDefaultBranch(),
                        workDir);
                if (!gitResult.isSuccess()) {
                    throw new RuntimeException("Git clone failed: " + gitResult.getMessage());
                }
                scan.setCommitHash(gitResult.getCommitHash());
                scan.setBranch(gitResult.getBranch());
            } else {
                // 使用本地路径（如果指定）或使用临时测试目录
                if (project.getSourceUrl() != null) {
                    workDir = Path.of(project.getSourceUrl());
                }
            }

            if (workDir != null && Files.exists(workDir)) {
                emitProgress(projectId, scan.getId(), "PARSING", 30, "解析 Java 源码...");
                List<ClassSummaryResult> classes = javaParserService.analyzeDirectory(workDir);
                totalClasses = classes.size();
                totalLines = classes.stream().mapToInt(ClassSummaryResult::getLineCount).sum();

                // 批量持久化类摘要到 class_summary 表
                List<ClassSummaryEntity> batch = new ArrayList<>();
                for (ClassSummaryResult cls : classes) {
                    ClassSummaryEntity entity = new ClassSummaryEntity();
                    entity.setScanId(scan.getId());
                    entity.setProjectId(projectId);
                    entity.setFqn(cls.getFqn());
                    entity.setSimpleName(cls.getSimpleName());
                    entity.setPackageName(cls.getPackageName());
                    entity.setClassType(cls.getClassType());
                    entity.setLayer(cls.getLayer());
                    entity.setPublicMethods(cls.getPublicMethods());
                    entity.setTotalMethods(cls.getTotalMethods());
                    entity.setLineCount(cls.getLineCount());
                    entity.setAnnotations(toJson(cls.getAnnotations()));
                    entity.setDependencies(toJson(cls.getDependencies()));
                    batch.add(entity);
                    if (batch.size() >= 100) {
                        classSummaryMapper.insertBatch(batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) {
                    classSummaryMapper.insertBatch(batch);
                }

                log.info("Scan completed: projectId={}, classes={}, lines={}", projectId, totalClasses, totalLines);

                emitProgress(projectId, scan.getId(), "RULES", 60, "运行架构规则检查...");
                // 规则违规检测
                List<ConstitutionRuleEntity> rules = constitutionRuleService.getRules(projectId);
                List<RuleDefinition> ruleDefs = rules.stream().map(r -> {
                    RuleDefinition def = new RuleDefinition();
                    def.setId(r.getId());
                    def.setName(r.getName());
                    def.setIsEnabled(r.getIsEnabled());
                    def.setSeverity(r.getSeverity());
                    def.setRuleDefinition(r.getRuleDefinition());
                    return def;
                }).collect(Collectors.toList());

                RuleEngine ruleEngine = new RuleEngine();
                List<ViolationResult> results = ruleEngine.check(ruleDefs, classes);
                totalViolations = results.size();
                List<ViolationEntity> violationBatch = new ArrayList<>();
                for (ViolationResult vr : results) {
                    ViolationEntity v = new ViolationEntity();
                    v.setScanId(scan.getId());
                    v.setProjectId(projectId);
                    v.setRuleId(vr.getRuleId());
                    v.setSeverity(vr.getSeverity());
                    v.setClassFqn(vr.getClassFqn());
                    v.setMessage(vr.getMessage());
                    v.setSuggestion(vr.getSuggestion());
                    v.setIsResolved(false);
                    violationBatch.add(v);
                    if (violationBatch.size() >= 100) {
                        violationMapper.insertBatch(violationBatch);
                        violationBatch.clear();
                    }
                }
                if (!violationBatch.isEmpty()) {
                    violationMapper.insertBatch(violationBatch);
                }
                scan.setTotalViolations(totalViolations);
                log.info("Violation check completed: projectId={}, violations={}", projectId, totalViolations);
            } else {
                log.info("Scan skipped: no source to analyze for projectId={}", projectId);
            }
        } catch (Exception e) {
            log.error("Scan failed for projectId={}: {}", projectId, e.getMessage());
            errorMessage = e.getMessage();
            scan.setStatus("FAILED");
            scan.setErrorMessage(errorMessage);
        } finally {
            if (cleanupWorkDir && workDir != null) {
                gitService.deleteLocalRepo(workDir);
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        if (!"FAILED".equals(scan.getStatus())) {
            scan.setStatus("COMPLETED");
        }
        scan.setTotalClasses(totalClasses);
        scan.setTotalLines(totalLines);
        scan.setTotalViolations(totalViolations);
        scan.setDurationMs(duration);
        scan.setCompletedAt(LocalDateTime.now());
        scanMapper.updateStats(scan);

        // 更新项目统计
        project.setTotalClasses(totalClasses);
        project.setTotalModules(1);
        project.setHealthScore(calculateHealthScore(totalViolations, totalClasses,
                scan.getTotalViolations() != null ? scan.getTotalViolations() : 0));
        project.setLastScanId(scan.getId());
        projectMapper.updateStats(project);

        // 异步触发 AI 分析
        if ("COMPLETED".equals(scan.getStatus()) && totalClasses > 0) {
            emitProgress(projectId, scan.getId(), "AI", 80, "开始 AI 架构分析...");
            aiAnalysisService.triggerAsync(projectId, scan.getId());
        }

        emitProgress(projectId, scan.getId(),
                "COMPLETED".equals(scan.getStatus()) ? "COMPLETED" : "FAILED",
                100,
                "COMPLETED".equals(scan.getStatus()) ? "扫描完成" : "扫描失败: " + (errorMessage != null ? errorMessage : ""));

        return toVO(scan);
    }

    private void emitProgress(Long projectId, Long scanId, String stage, int progress, String message) {
        try {
            eventPublisher.publishEvent(new ScanProgressEvent(projectId, scanId, stage, progress, message));
        } catch (Exception e) {
            log.debug("Failed to emit progress event: {}", e.getMessage());
        }
    }

    @Override
    public PageResult<ScanVO> getScanHistory(Long projectId, Long userId, int page, int size) {
        long total = scanMapper.countByProjectId(projectId);
        int offset = (page - 1) * size;
        List<ScanRecord> scans = scanMapper.findByProjectIdPaged(projectId, offset, size);
        List<ScanVO> records = scans.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public ScanVO getLatestScan(Long projectId, Long userId) {
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        return scan != null ? toVO(scan) : null;
    }

    private String toJson(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        try {
            return OBJECT_MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private BigDecimal calculateHealthScore(int violations, int totalClasses, int knownViolations) {
        if (totalClasses == 0) return BigDecimal.ZERO;

        // Architecture compliance (40%): based on violations
        double violationPenalty = Math.min(violations * 5.0, 40.0);

        // Code structure (30%): classes with proper layering
        double structureScore = 30.0;

        // Code quality (20%): reasonable class sizes
        double qualityScore = 20.0;

        // Dependency health (10%): no circular dependencies
        double dependencyScore = 10.0;

        double score = 100.0 - violationPenalty;
        double finalScore = Math.max(0.0, Math.min(100.0, score));
        return BigDecimal.valueOf(Math.round(finalScore * 100.0) / 100.0);
    }

    private ScanVO toVO(ScanRecord s) {
        ScanVO vo = new ScanVO();
        vo.setId(s.getId());
        vo.setProjectId(s.getProjectId());
        vo.setCommitHash(s.getCommitHash());
        vo.setBranch(s.getBranch());
        vo.setStatus(s.getStatus());
        vo.setTotalClasses(s.getTotalClasses());
        vo.setTotalLines(s.getTotalLines());
        vo.setTotalViolations(s.getTotalViolations());
        vo.setDurationMs(s.getDurationMs());
        vo.setStartedAt(s.getStartedAt());
        vo.setCompletedAt(s.getCompletedAt());
        vo.setCreatedAt(s.getCreatedAt());
        return vo;
    }
}
