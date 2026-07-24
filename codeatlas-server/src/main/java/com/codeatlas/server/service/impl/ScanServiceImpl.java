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
import com.codeatlas.server.config.CodeAtlasMetrics;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.AlertService;
import com.codeatlas.server.service.ConstitutionRuleService;
import com.codeatlas.server.service.Neo4jGraphService;
import com.codeatlas.server.service.ScanService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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
    private final Neo4jGraphService neo4jGraphService;
    private final GitService gitService;
    private final JavaParserService javaParserService;
    private final ApplicationEventPublisher eventPublisher;
    private final Executor scanExecutor;
    private final WorkspaceConfig workspaceConfig;
    private final CacheManager cacheManager;
    private final AlertService alertService;
    private final CodeAtlasMetrics metrics;

    public ScanServiceImpl(ScanMapper scanMapper, ProjectMapper projectMapper,
                           ClassSummaryMapper classSummaryMapper,
                           ViolationMapper violationMapper,
                           AiAnalysisService aiAnalysisService,
                           ConstitutionRuleService constitutionRuleService,
                           Neo4jGraphService neo4jGraphService,
                           GitService gitService,
                           JavaParserService javaParserService,
                           ApplicationEventPublisher eventPublisher,
                           @Qualifier("scanExecutor") Executor scanExecutor,
                           WorkspaceConfig workspaceConfig,
                           CacheManager cacheManager,
                           AlertService alertService,
                           CodeAtlasMetrics metrics) {
        this.scanMapper = scanMapper;
        this.projectMapper = projectMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.violationMapper = violationMapper;
        this.aiAnalysisService = aiAnalysisService;
        this.constitutionRuleService = constitutionRuleService;
        this.neo4jGraphService = neo4jGraphService;
        this.gitService = gitService;
        this.javaParserService = javaParserService;
        this.eventPublisher = eventPublisher;
        this.scanExecutor = scanExecutor;
        this.workspaceConfig = workspaceConfig;
        this.cacheManager = cacheManager;
        this.alertService = alertService;
        this.metrics = metrics;
    }

    @Override
    @Timed(value = "scan.duration", description = "Code scan duration")
    public ScanVO triggerScan(Long projectId, Long userId) {
        return doTriggerScan(projectId, userId, false);
    }

    @Override
    public ScanVO triggerIncrementalScan(Long projectId, Long userId) {
        ScanRecord previous = scanMapper.findLatestByProjectId(projectId);
        if (previous == null || !"COMPLETED".equals(previous.getStatus())) {
            throw new BusinessException(ErrorCode.SCAN_NOT_FOUND, "没有已完成的扫描，请先执行全量扫描");
        }
        return doTriggerScan(projectId, userId, true);
    }

    private ScanVO doTriggerScan(Long projectId, Long userId, boolean incremental) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        // 检查是否有正在运行的扫描
        ScanRecord latest = scanMapper.findLatestByProjectId(projectId);
        if (latest != null && "RUNNING".equals(latest.getStatus())) {
            throw new BusinessException(ErrorCode.SCAN_ALREADY_RUNNING, "已有扫描正在运行");
        }

        // GitHub 仓库预检：先查大小再决定能不能扫，避免网络传输太久
        if ("GIT_URL".equals(project.getSourceType()) && project.getSourceUrl() != null) {
            long estimatedBytes = gitService.estimateRepoSize(project.getSourceUrl());
            if (estimatedBytes > 100L * 1024 * 1024) {
                throw new BusinessException(ErrorCode.FILE_TOO_LARGE,
                        "仓库预估过大: ~" + (estimatedBytes / (1024 * 1024)) +
                        " MB (网络克隆上限 100 MB)。请先 git clone 到本地，再使用本地路径扫描");
            }
        }

        // 创建扫描记录并立即返回，实际扫描在后台线程执行
        ScanRecord scan = new ScanRecord();
        scan.setProjectId(projectId);
        scan.setStatus("RUNNING");
        scan.setStartedAt(LocalDateTime.now());
        scanMapper.insert(scan);

        metrics.recordScanTriggered();

        // 异步执行扫描，让 POST 立即返回，前端通过 SSE 获取实时进度
        final boolean isIncremental = incremental;
        CompletableFuture.runAsync(() -> executeScan(project, scan, userId, isIncremental), scanExecutor)
                .exceptionally(ex -> {
                    log.error("Unhandled scan error for projectId={}: {}", projectId, ex.getMessage(), ex);
                    return null;
                });

        return toVO(scan);
    }

    /**
     * 后台执行完整扫描流程：克隆 → 解析 → 规则检查 → AI 分析。
     * 通过 ApplicationEventPublisher 推送进度事件给 SSE 订阅者。
     */
    private void executeScan(Project project, ScanRecord scan, Long userId, boolean incremental) {
        long startTime = System.currentTimeMillis();
        Long projectId = project.getId();
        int totalClasses = 0;
        int totalLines = 0;
        int totalViolations = 0;
        String errorMessage = null;
        Path workDir = null;
        boolean cleanupWorkDir = false;

        try {
            emitProgress(projectId, scan.getId(), "CLONING", 10, "开始克隆仓库...");
            if ("GIT_URL".equals(project.getSourceType()) && project.getSourceUrl() != null) {
                workDir = workspaceConfig.getScansDir().resolve(projectId + "-" + scan.getId());
                cleanupWorkDir = true;
                GitResult gitResult = gitService.cloneRepository(
                        project.getSourceUrl(),
                        project.getDefaultBranch(),
                        workDir,
                        msg -> emitProgress(projectId, scan.getId(), "CLONING", 15, msg));
                if (!gitResult.isSuccess()) {
                    throw new RuntimeException("Git clone failed: " + gitResult.getMessage());
                }
                scan.setCommitHash(gitResult.getCommitHash());
                scan.setBranch(gitResult.getBranch());
            } else {
                if (project.getSourceUrl() != null) {
                    workDir = Path.of(project.getSourceUrl());
                }
            }

            if (workDir != null && Files.exists(workDir)) {
                emitProgress(projectId, scan.getId(), "PARSING", 30, "解析 Java 源码...");
                List<ClassSummaryResult> classes = javaParserService.analyzeDirectory(workDir);
                totalClasses = classes.size();
                totalLines = classes.stream().mapToInt(ClassSummaryResult::getLineCount).sum();

                int insertedCount = 0;
                int updatedCount = 0;
                int deletedCount = 0;

                if (incremental) {
                    // 加载上一次扫描的类列表，计算增量差异
                    ScanRecord prevScan = scanMapper.findLatestCompletedBefore(projectId, scan.getId());
                    Set<String> prevFqns = new java.util.HashSet<>();
                    if (prevScan != null) {
                        List<ClassSummaryEntity> prevClasses = classSummaryMapper.findByScanId(prevScan.getId());
                        for (ClassSummaryEntity pc : prevClasses) {
                            prevFqns.add(pc.getFqn());
                        }
                    }

                    Set<String> newFqns = classes.stream().map(ClassSummaryResult::getFqn).collect(Collectors.toSet());

                    // 删除已移除的类
                    Set<String> removedFqns = new java.util.HashSet<>(prevFqns);
                    removedFqns.removeAll(newFqns);
                    if (!removedFqns.isEmpty()) {
                        deletedCount = classSummaryMapper.deleteByFqns(projectId, new ArrayList<>(removedFqns));
                        log.info("Incremental scan: deleted {} removed classes for projectId={}", deletedCount, projectId);
                    }

                    // 插入新增和变更的类（replace via delete + insert）
                    List<ClassSummaryEntity> batch = new ArrayList<>();
                    for (ClassSummaryResult cls : classes) {
                        ClassSummaryEntity entity = toEntity(cls, scan.getId(), projectId);
                        batch.add(entity);
                        if (batch.size() >= 100) {
                            classSummaryMapper.insertBatch(batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        classSummaryMapper.insertBatch(batch);
                    }
                    insertedCount = classes.size();

                    emitProgress(projectId, scan.getId(), "PARSING", 40,
                            String.format("增量分析: 新增/更新 %d 类, 删除 %d 类", insertedCount, deletedCount));
                } else {
                    // 全量扫描：直接插入
                    List<ClassSummaryEntity> batch = new ArrayList<>();
                    for (ClassSummaryResult cls : classes) {
                        batch.add(toEntity(cls, scan.getId(), projectId));
                        if (batch.size() >= 100) {
                            classSummaryMapper.insertBatch(batch);
                            batch.clear();
                        }
                    }
                    if (!batch.isEmpty()) {
                        classSummaryMapper.insertBatch(batch);
                    }
                    insertedCount = classes.size();
                }

                try {
                    neo4jGraphService.importGraph(projectId, classes);
                } catch (Exception e) {
                    log.warn("Neo4j graph import failed for projectId={}: {}", projectId, e.getMessage());
                }

                log.info("Scan completed: projectId={}, classes={}, lines={}, incremental={}, inserted={}, updated={}, deleted={}",
                        projectId, totalClasses, totalLines, incremental, insertedCount, updatedCount, deletedCount);

                emitProgress(projectId, scan.getId(), "RULES", 60, "运行架构规则检查...");
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
            alertService.sendAlert("扫描失败 — " + project.getName(),
                    "项目 **" + project.getName() + "** (id=" + projectId + ") 扫描失败。\n错误: " + e.getMessage());
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

        // 驱逐地图缓存，确保下次查询加载最新数据
        try {
            Objects.requireNonNull(cacheManager.getCache("mapData")).evict(projectId);
        } catch (Exception e) {
            log.debug("Failed to evict mapData cache for projectId={}: {}", projectId, e.getMessage());
        }

        project.setTotalClasses(totalClasses);
        project.setTotalModules(1);
        project.setHealthScore(calculateHealthScore(totalViolations, totalClasses,
                scan.getTotalViolations() != null ? scan.getTotalViolations() : 0));
        project.setLastScanId(scan.getId());
        projectMapper.updateStats(project);

        if ("COMPLETED".equals(scan.getStatus()) && totalClasses > 0) {
            emitProgress(projectId, scan.getId(), "AI", 80, "开始 AI 架构分析...");
            aiAnalysisService.triggerAsync(projectId, scan.getId());
        }

        if ("COMPLETED".equals(scan.getStatus())) {
            metrics.recordScanCompleted(duration);
        } else {
            metrics.recordScanFailed();
        }

        emitProgress(projectId, scan.getId(),
                "COMPLETED".equals(scan.getStatus()) ? "COMPLETED" : "FAILED",
                100,
                "COMPLETED".equals(scan.getStatus()) ? "扫描完成" : "扫描失败: " + (errorMessage != null ? errorMessage : ""));
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

    @Override
    public ScanRecord getLatestScanEntity(Long projectId) {
        return scanMapper.findLatestByProjectId(projectId);
    }

    @Override
    public List<ClassSummaryEntity> getClassSummaries(Long projectId) {
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        if (scan == null) {
            return java.util.Collections.emptyList();
        }
        return classSummaryMapper.findByScanId(scan.getId());
    }

    private ClassSummaryEntity toEntity(ClassSummaryResult cls, Long scanId, Long projectId) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setScanId(scanId);
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
        return entity;
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
