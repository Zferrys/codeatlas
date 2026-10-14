package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.engine.git.GitResult;
import com.codeatlas.engine.git.GitService;
import com.codeatlas.engine.parser.ClassSummaryResult;
import com.codeatlas.engine.parser.JavaParserService;
import com.codeatlas.engine.rule.RuleDefinition;
import com.codeatlas.engine.rule.RuleEngine;
import com.codeatlas.server.config.CodeAtlasMetrics;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.ConstitutionRuleEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.AiAnalysisService;
import com.codeatlas.server.service.AlertService;
import com.codeatlas.server.service.ConstitutionRuleService;
import com.codeatlas.server.service.Neo4jGraphService;
import com.codeatlas.server.service.ProjectAccessChecker;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.objenesis.ObjenesisStd;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScanServiceImpl 单元测试（触发校验/执行全阶段/增量/失败清理/查询映射）")
class ScanServiceImplTest {

    private static final Long PROJECT_ID = 7L;
    private static final Long USER_ID = 1L;
    private static final Long SCAN_ID = 100L;
    private static final long MAX_CLONE_ESTIMATE_BYTES = 150L * 1024 * 1024;
    private static final String GIT_URL = "https://example.invalid/repo.git";

    @Mock
    private ScanMapper scanMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ClassSummaryMapper classSummaryMapper;
    @Mock
    private ViolationMapper violationMapper;
    @Mock
    private AiAnalysisService aiAnalysisService;
    @Mock
    private ConstitutionRuleService constitutionRuleService;
    @Mock
    private Neo4jGraphService neo4jGraphService;
    @Mock
    private GitService gitService;
    @Mock
    private JavaParserService javaParserService;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private CacheManager cacheManager;
    @Mock
    private Cache cache;
    @Mock
    private AlertService alertService;
    @Mock
    private CodeAtlasMetrics metrics;
    @Mock
    private ProjectAccessChecker projectAccessChecker;

    @TempDir
    private Path tempDir;

    private WorkspaceConfig workspaceConfig;
    private ScanServiceImpl service;

    @BeforeEach
    void setUp() {
        workspaceConfig = new WorkspaceConfig();
        workspaceConfig.setDir(tempDir.toString());
        workspaceConfig.init();
        Executor syncExecutor = Runnable::run;
        service = new ScanServiceImpl(scanMapper, projectMapper, classSummaryMapper, violationMapper,
                aiAnalysisService, constitutionRuleService, neo4jGraphService, gitService, javaParserService,
                eventPublisher, syncExecutor, workspaceConfig, cacheManager, alertService, metrics,
                projectAccessChecker);
    }

    // ==================== 工厂与夹具 ====================

    private static Project project(Long id, String name, String sourceType, String sourceUrl) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setSourceType(sourceType);
        project.setSourceUrl(sourceUrl);
        project.setDefaultBranch("main");
        return project;
    }

    private static ScanRecord scanRecord(Long id, Long projectId, String status) {
        ScanRecord record = new ScanRecord();
        record.setId(id);
        record.setProjectId(projectId);
        record.setStatus(status);
        return record;
    }

    private static ClassSummaryResult classResult(String fqn, String simpleName, String layer,
                                                  int publicMethods, int totalMethods, int lineCount) {
        ClassSummaryResult cls = new ClassSummaryResult();
        cls.setFqn(fqn);
        cls.setSimpleName(simpleName);
        cls.setPackageName(fqn.substring(0, fqn.lastIndexOf('.')));
        cls.setClassType("CLASS");
        cls.setLayer(layer);
        cls.setPublicMethods(publicMethods);
        cls.setTotalMethods(totalMethods);
        cls.setLineCount(lineCount);
        return cls;
    }

    /** 健康分输入：分层 service、平均 10 方法、0 依赖（依赖维度 70 分） */
    private static ClassSummaryEntity scoreEntity(String fqn) {
        ClassSummaryEntity entity = new ClassSummaryEntity();
        entity.setFqn(fqn);
        entity.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        entity.setLayer("service");
        entity.setTotalMethods(10);
        entity.setLineCount(50);
        entity.setDependencies("[]");
        return entity;
    }

    private static ConstitutionRuleEntity ruleEntity(Long id, String name, String severity, String definition) {
        ConstitutionRuleEntity rule = new ConstitutionRuleEntity();
        rule.setId(id);
        rule.setName(name);
        rule.setSeverity(severity);
        rule.setRuleDefinition(definition);
        rule.setIsEnabled(true);
        return rule;
    }

    private Path createLocalSourceDir(String name) throws IOException {
        Path dir = workspaceConfig.getUploadsDir().resolve(name);
        Files.createDirectories(dir);
        return dir.toRealPath();
    }

    private void stubInsertAssignsId() {
        when(scanMapper.insert(any(ScanRecord.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ScanRecord.class).setId(SCAN_ID);
            return 1;
        });
    }

    private List<ScanProgressEvent> capturedEvents() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher, atLeastOnce()).publishEvent(captor.capture());
        return captor.getAllValues().stream()
                .map(value -> (ScanProgressEvent) value)
                .collect(Collectors.toList());
    }

    private static void assertEvent(ScanProgressEvent event, String stage, int progress, String message) {
        assertEquals(PROJECT_ID, event.getProjectId());
        assertEquals(SCAN_ID, event.getScanId());
        assertEquals(stage, event.getStage());
        assertEquals(progress, event.getProgress());
        assertEquals(message, event.getMessage());
    }

    private static List<ClassSummaryResult> manyClasses(int count) {
        List<ClassSummaryResult> classes = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            classes.add(classResult("com.demo.C" + i, "C" + i, "service", 1, 10, 5));
        }
        return classes;
    }

    // ==================== triggerScan 同步校验 ====================

    @Test
    @DisplayName("GIT_URL 仓库预估值超上限：抛 FILE_TOO_LARGE 且不落库")
    void triggerScan_gitUrlRepoOversized_shouldThrowFileTooLarge() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL));
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(200L * 1024 * 1024);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(30001, ex.getCode());
        assertEquals("仓库预估过大: ~200 MB (网络克隆上限 150 MB)。请先 git clone 到本地，再使用本地路径扫描",
                ex.getMessage());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
        verify(metrics, never()).recordScanTriggered();
        verify(projectAccessChecker).check(any(Project.class), eq(PROJECT_ID), eq(USER_ID));
    }

    @Test
    @DisplayName("已有 RUNNING 扫描：抛 SCAN_ALREADY_RUNNING 且不落库")
    void triggerScan_latestScanRunning_shouldThrowAlreadyRunning() {
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", null);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "RUNNING"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(10003, ex.getCode());
        assertEquals("已有扫描正在运行", ex.getMessage());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
        verify(gitService, never()).estimateRepoSize(anyString());
    }

    @Test
    @DisplayName("项目鉴权失败：异常原样上抛且不查扫描记录")
    void triggerScan_projectNotFound_shouldPropagateAccessCheck() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(null);
        doThrow(new BusinessException(ErrorCode.NOT_FOUND, "项目不存在"))
                .when(projectAccessChecker).check(isNull(), eq(PROJECT_ID), eq(USER_ID));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        verify(scanMapper, never()).findLatestByProjectId(anyLong());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
    }

    @Test
    @DisplayName("本地路径位于工作区之外：同步校验抛 400")
    void triggerScan_localPathOutsideWorkspace_shouldThrowBadRequest() throws IOException {
        Path outside = tempDir.resolve("outside-src");
        Files.createDirectories(outside);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", outside.toString()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("本地路径超出允许范围（仅支持工作区内的上传目录）", ex.getMessage());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
    }

    @Test
    @DisplayName("本地路径不存在：同步校验抛 400")
    void triggerScan_localPathMissing_shouldThrowBadRequest() {
        Path ghost = workspaceConfig.getUploadsDir().resolve("ghost-src");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", ghost.toString()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("本地源码路径无效或不存在", ex.getMessage());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
    }

    // ==================== 全量扫描执行 ====================

    @Test
    @DisplayName("本地路径全量扫描：全阶段事件顺序、类摘要/违规落库、项目统计与健康分刷新、AI 触发")
    void triggerScan_localPath_shouldCompleteScanAndRefreshStats() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString());
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();

        ClassSummaryResult a = classResult("com.x.A", "A", "service", 5, 10, 100);
        a.setAnnotations(Collections.singletonList("@Service"));
        a.setDependencies(null);
        a.setMethodNames(Collections.singletonList("run"));
        ClassSummaryResult b = classResult("com.x.B", "B", "controller", 1, 10, 50);
        b.setAnnotations(Collections.emptyList());
        b.setDependencies(Collections.emptyList());
        b.setMethodNames(null);
        List<ClassSummaryResult> classes = Arrays.asList(a, b);
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(classes);

        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(1L, "max-public-methods", "ERROR", "{\"max\":3}")));
        when(classSummaryMapper.findByScanId(SCAN_ID))
                .thenReturn(Arrays.asList(scoreEntity("com.x.A"), scoreEntity("com.x.B")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals(SCAN_ID, vo.getId());
        assertEquals(PROJECT_ID, vo.getProjectId());
        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(2, vo.getTotalClasses());
        assertEquals(150, vo.getTotalLines());
        assertEquals(1, vo.getTotalViolations());
        assertNotNull(vo.getStartedAt());
        assertNotNull(vo.getCompletedAt());
        assertTrue(vo.getDurationMs() >= 0);

        List<ScanProgressEvent> events = capturedEvents();
        assertEquals(6, events.size());
        assertEvent(events.get(0), "PENDING", 1, "扫描任务已创建，等待执行...");
        assertEvent(events.get(1), "CLONING", 10, "开始克隆仓库...");
        assertEvent(events.get(2), "PARSING", 30, "解析 Java 源码...");
        assertEvent(events.get(3), "RULES", 60, "运行架构规则检查...");
        assertEvent(events.get(4), "AI", 80, "开始 AI 架构分析...");
        assertEvent(events.get(5), "COMPLETED", 100, "扫描完成");

        ArgumentCaptor<List<ClassSummaryEntity>> classCaptor = ArgumentCaptor.forClass(List.class);
        verify(classSummaryMapper).insertBatch(classCaptor.capture());
        List<ClassSummaryEntity> entities = classCaptor.getValue();
        assertEquals(2, entities.size());
        ClassSummaryEntity entityA = entities.get(0);
        assertEquals(SCAN_ID, entityA.getScanId());
        assertEquals(PROJECT_ID, entityA.getProjectId());
        assertEquals("com.x.A", entityA.getFqn());
        assertEquals("A", entityA.getSimpleName());
        assertEquals("com.x", entityA.getPackageName());
        assertEquals("CLASS", entityA.getClassType());
        assertEquals("service", entityA.getLayer());
        assertEquals(5, entityA.getPublicMethods());
        assertEquals(10, entityA.getTotalMethods());
        assertEquals(100, entityA.getLineCount());
        assertEquals("[\"@Service\"]", entityA.getAnnotations());
        assertEquals("[]", entityA.getDependencies());
        assertEquals("[\"run\"]", entityA.getMethodNames());
        ClassSummaryEntity entityB = entities.get(1);
        assertEquals("com.x.B", entityB.getFqn());
        assertEquals("[]", entityB.getAnnotations());
        assertEquals("[]", entityB.getMethodNames());

        ArgumentCaptor<List<ViolationEntity>> violationCaptor = ArgumentCaptor.forClass(List.class);
        verify(violationMapper).insertBatch(violationCaptor.capture());
        List<ViolationEntity> violations = violationCaptor.getValue();
        assertEquals(1, violations.size());
        ViolationEntity violation = violations.get(0);
        assertEquals(SCAN_ID, violation.getScanId());
        assertEquals(PROJECT_ID, violation.getProjectId());
        assertEquals(1L, violation.getRuleId());
        assertEquals("ERROR", violation.getSeverity());
        assertEquals("com.x.A", violation.getClassFqn());
        assertEquals("公开方法数 5 超过上限 3", violation.getMessage());
        assertEquals("考虑拆分类或将相关方法提取到独立的 Service 中", violation.getSuggestion());
        assertEquals(false, violation.getIsResolved());

        ArgumentCaptor<ScanRecord> scanCaptor = ArgumentCaptor.forClass(ScanRecord.class);
        verify(scanMapper).updateStats(scanCaptor.capture());
        ScanRecord updated = scanCaptor.getValue();
        assertEquals("COMPLETED", updated.getStatus());
        assertEquals(2, updated.getTotalClasses());
        assertEquals(150, updated.getTotalLines());
        assertEquals(1, updated.getTotalViolations());
        assertNull(updated.getErrorMessage());
        assertNotNull(updated.getCompletedAt());
        assertTrue(updated.getDurationMs() >= 0);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateStats(projectCaptor.capture());
        Project stats = projectCaptor.getValue();
        assertEquals(2, stats.getTotalClasses());
        assertEquals(1, stats.getTotalModules());
        assertEquals(new BigDecimal("95.00"), stats.getHealthScore());
        assertEquals(SCAN_ID, stats.getLastScanId());

        verify(neo4jGraphService).importGraph(PROJECT_ID, classes);
        verify(javaParserService).analyzeDirectory(sourceDir);
        verify(aiAnalysisService).triggerAsync(PROJECT_ID, SCAN_ID);
        verify(cache).evict(PROJECT_ID);
        verify(metrics).recordScanTriggered();
        verify(metrics).recordScanCompleted(anyLong());
        verify(metrics, never()).recordScanFailed();
        verify(violationMapper, never()).deleteByScanId(anyLong());
        verify(alertService, never()).sendAlert(anyString(), anyString());
        verify(gitService, never()).estimateRepoSize(anyString());
        verify(gitService, never()).cloneRepository(any(), any(), any(), any());
    }

    @Test
    @DisplayName("解析阶段异常：扫描标记 FAILED、告警内容精确、清理部分数据、记录失败指标")
    void triggerScan_parserThrows_shouldMarkFailedAndAlert() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString());
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(javaParserService.analyzeDirectory(any(Path.class)))
                .thenThrow(new RuntimeException("解析器崩溃"));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("FAILED", vo.getStatus());
        assertEquals(0, vo.getTotalClasses());

        List<ScanProgressEvent> events = capturedEvents();
        assertEquals(4, events.size());
        assertEvent(events.get(0), "PENDING", 1, "扫描任务已创建，等待执行...");
        assertEvent(events.get(1), "CLONING", 10, "开始克隆仓库...");
        assertEvent(events.get(2), "PARSING", 30, "解析 Java 源码...");
        assertEvent(events.get(3), "FAILED", 100, "扫描失败: 解析器崩溃");

        verify(alertService).sendAlert("扫描失败 — 演示项目",
                "项目 **演示项目** (id=7) 扫描失败。\n错误: 解析器崩溃");

        ArgumentCaptor<ScanRecord> scanCaptor = ArgumentCaptor.forClass(ScanRecord.class);
        verify(scanMapper).updateStats(scanCaptor.capture());
        ScanRecord updated = scanCaptor.getValue();
        assertEquals("FAILED", updated.getStatus());
        assertEquals("解析器崩溃", updated.getErrorMessage());
        assertEquals(0, updated.getTotalClasses());
        assertEquals(0, updated.getTotalViolations());
        assertNotNull(updated.getCompletedAt());

        verify(classSummaryMapper).deleteByScanId(SCAN_ID);
        verify(violationMapper).deleteByScanId(SCAN_ID);
        verify(metrics).recordScanFailed();
        verify(metrics, never()).recordScanCompleted(anyLong());
        verify(projectMapper, never()).updateStats(any(Project.class));
        verify(aiAnalysisService, never()).triggerAsync(anyLong(), anyLong());
        verify(gitService, never()).deleteLocalRepo(any());
        verify(cache).evict(PROJECT_ID);
    }

    @Test
    @DisplayName("GIT_URL 扫描（预估值恰为上限边界）：克隆目录成功解析、完成后清理工作目录")
    void triggerScan_gitUrl_shouldCloneParseAndCleanup() throws IOException {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(MAX_CLONE_ESTIMATE_BYTES);
        stubInsertAssignsId();

        when(gitService.cloneRepository(eq(GIT_URL), eq("main"), any(Path.class), any()))
                .thenAnswer(invocation -> {
                    Path target = invocation.getArgument(2);
                    Files.createDirectories(target);
                    java.util.function.Consumer<String> callback = invocation.getArgument(3);
                    callback.accept("正在克隆仓库... (已耗时 42 秒, 大仓库可能较慢)");
                    return new GitResult(target.toString(), "main", "abc123def", true, "clone ok");
                });
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals("abc123def", vo.getCommitHash());
        assertEquals("main", vo.getBranch());
        assertEquals(0, vo.getTotalClasses());

        ArgumentCaptor<Path> workDirCaptor = ArgumentCaptor.forClass(Path.class);
        verify(gitService).cloneRepository(eq(GIT_URL), eq("main"), workDirCaptor.capture(), any());
        Path workDir = workDirCaptor.getValue();
        assertEquals("7-100", workDir.getFileName().toString());
        assertTrue(workDir.startsWith(workspaceConfig.getScansDir()));
        verify(javaParserService).analyzeDirectory(workDir);

        List<ScanProgressEvent> events = capturedEvents();
        assertEquals(6, events.size());
        assertEvent(events.get(0), "PENDING", 1, "扫描任务已创建，等待执行...");
        assertEvent(events.get(1), "CLONING", 10, "开始克隆仓库...");
        assertEvent(events.get(2), "CLONING", 15, "正在克隆仓库... (已耗时 42 秒, 大仓库可能较慢)");
        assertEvent(events.get(3), "PARSING", 30, "解析 Java 源码...");
        assertEvent(events.get(4), "RULES", 60, "运行架构规则检查...");
        assertEvent(events.get(5), "COMPLETED", 100, "扫描完成");

        ArgumentCaptor<ScanRecord> scanCaptor = ArgumentCaptor.forClass(ScanRecord.class);
        verify(scanMapper).updateStats(scanCaptor.capture());
        assertEquals("abc123def", scanCaptor.getValue().getCommitHash());
        assertEquals("main", scanCaptor.getValue().getBranch());

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateStats(projectCaptor.capture());
        assertEquals(new BigDecimal("0.00"), projectCaptor.getValue().getHealthScore());

        verify(gitService).deleteLocalRepo(workDir);
        verify(aiAnalysisService, never()).triggerAsync(anyLong(), anyLong());
        verify(classSummaryMapper, never()).insertBatch(anyList());
        verify(violationMapper, never()).insertBatch(anyList());
        verify(metrics).recordScanCompleted(anyLong());
        verify(alertService, never()).sendAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("GIT_URL 克隆失败：抛克隆错误信息、FAILED 清理后删除工作目录")
    void triggerScan_gitCloneFails_shouldFailWithCloneError() throws IOException {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(-1L);
        stubInsertAssignsId();
        when(gitService.cloneRepository(eq(GIT_URL), eq("main"), any(Path.class), any()))
                .thenReturn(new GitResult(null, "main", null, false, "认证失败"));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("FAILED", vo.getStatus());
        verify(alertService).sendAlert("扫描失败 — 远程项目",
                "项目 **远程项目** (id=7) 扫描失败。\n错误: Git clone failed: 认证失败");
        verify(classSummaryMapper).deleteByScanId(SCAN_ID);
        verify(violationMapper).deleteByScanId(SCAN_ID);
        ArgumentCaptor<Path> workDirCaptor = ArgumentCaptor.forClass(Path.class);
        verify(gitService).deleteLocalRepo(workDirCaptor.capture());
        assertEquals("7-100", workDirCaptor.getValue().getFileName().toString());
        verify(javaParserService, never()).analyzeDirectory(any(Path.class));
        verify(metrics).recordScanFailed();
    }

    @Test
    @DisplayName("GIT_URL 克隆成功但目录缺失：FAILED 并说明临时文件已被清理")
    void triggerScan_gitCloneSucceededButDirMissing_shouldFailWithMissingPath() throws IOException {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(1024L);
        stubInsertAssignsId();
        when(gitService.cloneRepository(eq(GIT_URL), eq("main"), any(Path.class), any()))
                .thenReturn(new GitResult("/tmp/fake", "main", "abc", true, "clone ok"));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("FAILED", vo.getStatus());
        String expectedDir = workspaceConfig.getScansDir().resolve("7-100").toAbsolutePath().toString();
        verify(alertService).sendAlert("扫描失败 — 远程项目",
                "项目 **远程项目** (id=7) 扫描失败。\n错误: 源码路径不存在: " + expectedDir
                        + "（可能是临时文件已被清理，请重新上传）");
        verify(javaParserService, never()).analyzeDirectory(any(Path.class));
        verify(gitService).deleteLocalRepo(any(Path.class));
    }

    @Test
    @DisplayName("GIT_URL 但未配置 URL：不校验不克隆，直接以“未配置源码路径”失败")
    void triggerScan_gitUrlNullSourceUrl_shouldFailWithNoSourcePath() {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", null);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("FAILED", vo.getStatus());
        verify(alertService).sendAlert("扫描失败 — 远程项目",
                "项目 **远程项目** (id=7) 扫描失败。\n错误: 未配置源码路径，请使用 Git URL 或 ZIP 上传");
        verify(gitService, never()).estimateRepoSize(anyString());
        verify(gitService, never()).cloneRepository(any(), any(), any(), any());
        verify(gitService, never()).deleteLocalRepo(any(Path.class));
    }

    @Test
    @DisplayName("Neo4j 导入失败：仅告警日志，扫描仍完成并落违规")
    void triggerScan_neo4jImportFails_shouldStillComplete() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString());
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        ClassSummaryResult a = classResult("com.x.A", "A", "service", 5, 10, 100);
        List<ClassSummaryResult> classes = Collections.singletonList(a);
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(classes);
        doThrow(new RuntimeException("neo4j down")).when(neo4jGraphService).importGraph(PROJECT_ID, classes);
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(1L, "max-public-methods", "ERROR", "{\"max\":3}")));
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(scoreEntity("com.x.A")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(1, vo.getTotalClasses());
        assertEquals(1, vo.getTotalViolations());
        verify(violationMapper).insertBatch(anyList());
        verify(metrics).recordScanCompleted(anyLong());
        List<ScanProgressEvent> events = capturedEvents();
        assertEvent(events.get(events.size() - 1), "COMPLETED", 100, "扫描完成");
    }

    @Test
    @DisplayName("SSE 发布失败：进度事件全部吞掉，扫描流程不受影响")
    void triggerScan_eventPublisherFails_shouldSwallowAndComplete() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(cacheManager.getCache("mapData")).thenReturn(cache);
        doThrow(new RuntimeException("sse down")).when(eventPublisher).publishEvent(any(Object.class));

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        verify(eventPublisher, atLeastOnce()).publishEvent(any(Object.class));
        verify(metrics).recordScanCompleted(anyLong());
    }

    @Test
    @DisplayName("缓存管理器返回 null Cache：evict 失败被吞，扫描仍完成")
    void triggerScan_cacheUnavailable_shouldSwallowEvictFailure() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(cacheManager.getCache("mapData")).thenReturn(null);

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        verify(cacheManager).getCache("mapData");
        verify(metrics).recordScanCompleted(anyLong());
    }

    @Test
    @DisplayName("统计落库异常逃逸出执行体：由 CompletableFuture 兜底不向上抛")
    void triggerScan_updateStatsThrows_shouldBeCaughtByAsyncHandler() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(scanMapper.updateStats(any(ScanRecord.class))).thenThrow(new RuntimeException("db down"));

        ScanVO vo = assertDoesNotThrow(() -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals("COMPLETED", vo.getStatus());
        verify(scanMapper).updateStats(any(ScanRecord.class));
        verify(metrics).recordScanTriggered();
        verify(metrics, never()).recordScanCompleted(anyLong());
        verify(metrics, never()).recordScanFailed();
    }

    @Test
    @DisplayName("超过 100 个类与违规：分批落库 100+尾批，健康分含 40 分违规扣分上限")
    void triggerScan_moreThan100Classes_shouldFlushBatches() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(manyClasses(101));
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(2L, "max-class-line-count", "BLOCKER", "{\"max\":0}")));
        when(classSummaryMapper.findByScanId(SCAN_ID))
                .thenReturn(Arrays.asList(scoreEntity("com.demo.C0"), scoreEntity("com.demo.C1")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        List<Integer> classBatchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            classBatchSizes.add(((List<?>) invocation.getArgument(0)).size());
            return 1;
        }).when(classSummaryMapper).insertBatch(anyList());
        List<Integer> violationBatchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            violationBatchSizes.add(((List<?>) invocation.getArgument(0)).size());
            return 1;
        }).when(violationMapper).insertBatch(anyList());

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(101, vo.getTotalClasses());
        assertEquals(505, vo.getTotalLines());
        assertEquals(101, vo.getTotalViolations());
        assertEquals(Arrays.asList(100, 1), classBatchSizes);
        assertEquals(Arrays.asList(100, 1), violationBatchSizes);

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateStats(projectCaptor.capture());
        assertEquals(new BigDecimal("81.00"), projectCaptor.getValue().getHealthScore());
        verify(aiAnalysisService).triggerAsync(PROJECT_ID, SCAN_ID);
        verify(metrics).recordScanCompleted(anyLong());
    }

    // ==================== 增量扫描 ====================

    @Test
    @DisplayName("增量扫描：无历史扫描时抛 SCAN_NOT_FOUND，不落库")
    void triggerIncrementalScan_noPreviousCompletedScan_shouldThrowScanNotFound() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerIncrementalScan(PROJECT_ID, USER_ID));

        assertEquals(10004, ex.getCode());
        assertEquals("没有已完成的扫描，请先执行全量扫描", ex.getMessage());
        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        verify(scanMapper, never()).insert(any(ScanRecord.class));
        verify(projectMapper, never()).findById(anyLong());
        verify(metrics, never()).recordScanTriggered();
    }

    @Test
    @DisplayName("增量扫描：上一次扫描非 COMPLETED 时同样拒绝")
    void triggerIncrementalScan_previousNotCompleted_shouldThrowScanNotFound() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "FAILED"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerIncrementalScan(PROJECT_ID, USER_ID));

        assertEquals(10004, ex.getCode());
        verify(scanMapper, times(1)).findLatestByProjectId(PROJECT_ID);
        verify(projectMapper, never()).findById(anyLong());
    }

    @Test
    @DisplayName("增量扫描：删除消失类、批量写入新增/更新类、增量进度文案精确")
    void triggerIncrementalScan_shouldDeleteRemovedAndInsertEntities() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString());
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "COMPLETED"));
        stubInsertAssignsId();
        when(scanMapper.findLatestCompletedBefore(PROJECT_ID, SCAN_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "COMPLETED"));

        ClassSummaryEntity prevA = new ClassSummaryEntity();
        prevA.setFqn("com.x.A");
        ClassSummaryEntity prevB = new ClassSummaryEntity();
        prevB.setFqn("com.x.B");
        when(classSummaryMapper.findByScanId(50L)).thenReturn(Arrays.asList(prevA, prevB));

        ClassSummaryResult newA = classResult("com.x.A", "A", "service", 1, 10, 100);
        newA.setAnnotations(Collections.singletonList("@Service"));
        newA.setDependencies(Collections.singletonList("com.x.C"));
        newA.setMethodNames(Collections.singletonList("run"));
        ClassSummaryResult newC = classResult("com.x.C", "C", "service", 2, 4, 30);
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Arrays.asList(newA, newC));

        when(classSummaryMapper.deleteByFqns(PROJECT_ID, Collections.singletonList("com.x.B"))).thenReturn(1);
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID))
                .thenReturn(Arrays.asList(scoreEntity("com.x.A"), scoreEntity("com.x.C")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerIncrementalScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(2, vo.getTotalClasses());

        ArgumentCaptor<List<ClassSummaryEntity>> classCaptor = ArgumentCaptor.forClass(List.class);
        verify(classSummaryMapper).insertBatch(classCaptor.capture());
        List<ClassSummaryEntity> inserted = classCaptor.getValue();
        assertEquals(2, inserted.size());
        assertEquals("com.x.A", inserted.get(0).getFqn());
        assertEquals(SCAN_ID, inserted.get(0).getScanId());
        assertEquals("[\"@Service\"]", inserted.get(0).getAnnotations());
        assertEquals("[\"com.x.C\"]", inserted.get(0).getDependencies());
        assertEquals("com.x.C", inserted.get(1).getFqn());

        ArgumentCaptor<List<String>> fqnCaptor = ArgumentCaptor.forClass(List.class);
        verify(classSummaryMapper).deleteByFqns(eq(PROJECT_ID), fqnCaptor.capture());
        assertEquals(Collections.singletonList("com.x.B"), fqnCaptor.getValue());

        List<ScanProgressEvent> events = capturedEvents();
        ScanProgressEvent incrementalEvent = events.stream()
                .filter(event -> "PARSING".equals(event.getStage()) && event.getProgress() == 40)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("增量分析: 新增/更新 2 类, 删除 1 类", incrementalEvent.getMessage());

        ArgumentCaptor<Project> projectCaptor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).updateStats(projectCaptor.capture());
        assertEquals(new BigDecimal("97.00"), projectCaptor.getValue().getHealthScore());
        verify(violationMapper, never()).insertBatch(anyList());
    }

    @Test
    @DisplayName("增量扫描：无历史快照 + 101 个类：跳过删除、分批 100+尾批")
    void triggerIncrementalScan_prevScanMissingWithManyClasses_shouldFlushWithoutDelete() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "COMPLETED"));
        stubInsertAssignsId();
        when(scanMapper.findLatestCompletedBefore(PROJECT_ID, SCAN_ID)).thenReturn(null);
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(manyClasses(101));
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID))
                .thenReturn(Arrays.asList(scoreEntity("com.demo.C0"), scoreEntity("com.demo.C1")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        List<Integer> classBatchSizes = new ArrayList<>();
        doAnswer(invocation -> {
            classBatchSizes.add(((List<?>) invocation.getArgument(0)).size());
            return 1;
        }).when(classSummaryMapper).insertBatch(anyList());

        ScanVO vo = service.triggerIncrementalScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(101, vo.getTotalClasses());
        assertEquals(Arrays.asList(100, 1), classBatchSizes);
        verify(classSummaryMapper, never()).deleteByFqns(anyLong(), anyList());
        List<ScanProgressEvent> events = capturedEvents();
        ScanProgressEvent incrementalEvent = events.stream()
                .filter(event -> "PARSING".equals(event.getStage()) && event.getProgress() == 40)
                .findFirst().orElseThrow(AssertionError::new);
        assertEquals("增量分析: 新增/更新 101 类, 删除 0 类", incrementalEvent.getMessage());
    }

    @Test
    @DisplayName("增量扫描：0 个类时不写类摘要、不写违规，完成事件仍为 COMPLETED")
    void triggerIncrementalScan_zeroClasses_shouldSkipBatchInsert() throws IOException {
        Path sourceDir = createLocalSourceDir("src-7");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "COMPLETED"));
        stubInsertAssignsId();
        when(scanMapper.findLatestCompletedBefore(PROJECT_ID, SCAN_ID)).thenReturn(null);
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo = service.triggerIncrementalScan(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(0, vo.getTotalClasses());
        verify(classSummaryMapper, never()).insertBatch(anyList());
        verify(classSummaryMapper, never()).deleteByFqns(anyLong(), anyList());
        verify(aiAnalysisService, never()).triggerAsync(anyLong(), anyLong());
        verify(metrics).recordScanCompleted(anyLong());
        List<ScanProgressEvent> events = capturedEvents();
        assertEvent(events.get(events.size() - 1), "COMPLETED", 100, "扫描完成");
    }

    // ==================== 查询方法 ====================

    @Test
    @DisplayName("扫描历史分页：offset 换算正确、VO 字段逐项映射")
    void getScanHistory_shouldPageAndMapAllFields() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        LocalDateTime completedAt = LocalDateTime.of(2026, 1, 2, 3, 5, 6);
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 2, 3, 0, 0);
        ScanRecord record = scanRecord(9L, PROJECT_ID, "COMPLETED");
        record.setCommitHash("c0ffee");
        record.setBranch("dev");
        record.setTotalClasses(3);
        record.setTotalLines(300);
        record.setTotalViolations(2);
        record.setDurationMs(1234L);
        record.setStartedAt(startedAt);
        record.setCompletedAt(completedAt);
        record.setCreatedAt(createdAt);
        when(scanMapper.countByProjectId(PROJECT_ID)).thenReturn(12L);
        when(scanMapper.findByProjectIdPaged(PROJECT_ID, 20, 10)).thenReturn(Collections.singletonList(record));

        PageResult<ScanVO> result = service.getScanHistory(PROJECT_ID, USER_ID, 3, 10);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertEquals(12L, result.getTotal());
        assertEquals(3, result.getPage());
        assertEquals(10, result.getSize());
        assertEquals(1, result.getRecords().size());
        ScanVO vo = result.getRecords().get(0);
        assertEquals(9L, vo.getId());
        assertEquals(PROJECT_ID, vo.getProjectId());
        assertEquals("c0ffee", vo.getCommitHash());
        assertEquals("dev", vo.getBranch());
        assertEquals("COMPLETED", vo.getStatus());
        assertEquals(3, vo.getTotalClasses());
        assertEquals(300, vo.getTotalLines());
        assertEquals(2, vo.getTotalViolations());
        assertEquals(1234L, vo.getDurationMs());
        assertEquals(startedAt, vo.getStartedAt());
        assertEquals(completedAt, vo.getCompletedAt());
        assertEquals(createdAt, vo.getCreatedAt());
    }

    @Test
    @DisplayName("最近扫描：无记录返回 null，有记录映射字段")
    void getLatestScan_shouldMapOrReturnNull() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        assertNull(service.getLatestScan(PROJECT_ID, USER_ID));

        ScanRecord record = scanRecord(9L, PROJECT_ID, "RUNNING");
        record.setBranch("main");
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(record);
        ScanVO vo = service.getLatestScan(PROJECT_ID, USER_ID);

        assertEquals(9L, vo.getId());
        assertEquals("RUNNING", vo.getStatus());
        assertEquals("main", vo.getBranch());
    }

    @Test
    @DisplayName("最近扫描实体：原样返回 Mapper 结果")
    void getLatestScanEntity_shouldReturnMapperResult() {
        ScanRecord record = scanRecord(9L, PROJECT_ID, "COMPLETED");
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(record);

        assertEquals(record, service.getLatestScanEntity(PROJECT_ID));
    }

    @Test
    @DisplayName("类摘要：无扫描记录返回空列表，有扫描记录按 scanId 透传")
    void getClassSummaries_shouldReturnEmptyOrDelegate() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        assertTrue(service.getClassSummaries(PROJECT_ID).isEmpty());
        verify(classSummaryMapper, never()).findByScanId(anyLong());

        ClassSummaryEntity entity = scoreEntity("com.x.A");
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scanRecord(50L, PROJECT_ID, "COMPLETED"));
        when(classSummaryMapper.findByScanId(50L)).thenReturn(Collections.singletonList(entity));

        List<ClassSummaryEntity> result = service.getClassSummaries(PROJECT_ID);

        assertEquals(1, result.size());
        assertEquals("com.x.A", result.get(0).getFqn());
    }

    @Test
    @DisplayName("扫描状态：无扫描时返回 NONE 且 AI 标志取自服务")
    void getScanStatus_whenNoScan_shouldReportNone() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(aiAnalysisService.isAiAnalysisRunning(PROJECT_ID)).thenReturn(false);

        Map<String, Object> status = service.getScanStatus(PROJECT_ID, USER_ID);

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
        assertEquals(4, status.size());
        assertEquals("NONE", status.get("scanStatus"));
        assertNull(status.get("scanId"));
        assertNull(status.get("lastScanTime"));
        assertEquals(false, status.get("aiAnalysisRunning"));
    }

    @Test
    @DisplayName("扫描状态：有扫描时返回状态/ID/时间字符串与 AI 运行标志")
    void getScanStatus_withScan_shouldReportLatestAndAiFlag() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 2, 3, 4, 5, 6);
        ScanRecord record = scanRecord(9L, PROJECT_ID, "RUNNING");
        record.setCreatedAt(createdAt);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(record);
        when(aiAnalysisService.isAiAnalysisRunning(PROJECT_ID)).thenReturn(true);

        Map<String, Object> status = service.getScanStatus(PROJECT_ID, USER_ID);

        assertEquals("RUNNING", status.get("scanStatus"));
        assertEquals(9L, status.get("scanId"));
        assertEquals("2026-02-03T04:05:06", status.get("lastScanTime"));
        assertEquals(true, status.get("aiAnalysisRunning"));
    }

    @Test
    @DisplayName("扫描状态：createdAt 为空时 lastScanTime 返回 null")
    void getScanStatus_withNullCreatedAt_shouldReportNullTime() {
        ScanRecord record = scanRecord(9L, PROJECT_ID, "COMPLETED");
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(record);
        when(aiAnalysisService.isAiAnalysisRunning(PROJECT_ID)).thenReturn(false);

        Map<String, Object> status = service.getScanStatus(PROJECT_ID, USER_ID);

        assertEquals("COMPLETED", status.get("scanStatus"));
        assertEquals(9L, status.get("scanId"));
        assertNull(status.get("lastScanTime"));
    }

    // ==================== 以下为变异体杀灭追加（PIT 幸存者定点清除） ====================

    private static final long MB = 1024L * 1024L;

    @Test
    @DisplayName("GIT_URL 指向本机地址：同步拒绝（SSRF 校验调用不得被移除）")
    void triggerScan_gitUrlInternalHost_shouldRejectBeforeEstimate() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "内网项目", "GIT_URL", "http://127.0.0.1/repo.git"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.triggerScan(PROJECT_ID, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("不允许访问本机或内网地址", ex.getMessage());
        verify(gitService, never()).estimateRepoSize(anyString());
        verify(scanMapper, never()).insert(any(ScanRecord.class));
    }

    @Test
    @DisplayName("落库即 RUNNING：insert 时刻状态与启动时间已写入（setStatus 调用不得被移除）")
    void triggerScan_shouldInsertScanWithRunningStatus() throws IOException {
        Path sourceDir = createLocalSourceDir("src-status");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        AtomicReference<String> statusAtInsert = new AtomicReference<>();
        AtomicReference<LocalDateTime> startedAtAtInsert = new AtomicReference<>();
        when(scanMapper.insert(any(ScanRecord.class))).thenAnswer(invocation -> {
            ScanRecord record = invocation.getArgument(0, ScanRecord.class);
            statusAtInsert.set(record.getStatus());
            startedAtAtInsert.set(record.getStartedAt());
            record.setId(SCAN_ID);
            return 1;
        });
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.emptyList());
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.emptyList());
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals("RUNNING", statusAtInsert.get(), "insert 落库时扫描状态必须为 RUNNING");
        assertNotNull(startedAtAtInsert.get(), "insert 落库时须已写入启动时间");
    }

    @Test
    @DisplayName("内存检查边界：堆使用率恰为 85%（1700/2000MB）走正常分支，不发出 -1 降速事件")
    void checkMemory_exactlyEightyFivePercent_shouldLogNormalWithoutEvent() {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            try (MockedStatic<Runtime> mocked = mockStatic(Runtime.class)) {
                mocked.when(Runtime::getRuntime).thenReturn(stubRuntime(2000 * MB, 2000 * MB, 300 * MB));

                ReflectionTestUtils.invokeMethod(service, "checkMemory", PROJECT_ID, SCAN_ID, "PARSING");
            }

            verify(eventPublisher, never()).publishEvent(any());
            assertEquals(1, appender.list.stream().filter(e ->
                            "内存正常: PARSING 阶段堆使用率 85% (1700MB / 2000MB)".equals(e.getFormattedMessage()))
                    .count(), "85% 边界必须走正常分支且已用/上限数值精确");
        } finally {
            detachLogAppender(appender);
        }
    }

    @Test
    @DisplayName("高内存全量扫描：三个阶段精确降速告警（-1 事件 + 消息数值），durationMs 为真实差值")
    void triggerScan_localPathHighHeap_shouldWarnAllThreeStages() throws IOException {
        Path sourceDir = createLocalSourceDir("src-mem");
        Project project = project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString());
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        ClassSummaryResult a = classResult("com.x.A", "A", "service", 5, 10, 100);
        a.setAnnotations(Collections.emptyList());
        a.setDependencies(Collections.emptyList());
        a.setMethodNames(Collections.emptyList());
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.singletonList(a));
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(1L, "max-public-methods", "ERROR", "{\"max\":3}")));
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(scoreEntity("com.x.A")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        ScanVO vo;
        try (MockedStatic<Runtime> mocked = mockStatic(Runtime.class)) {
            mocked.when(Runtime::getRuntime).thenReturn(stubRuntime(1000 * MB, 1000 * MB, 100 * MB));

            vo = service.triggerScan(PROJECT_ID, USER_ID);
        }

        assertEquals("COMPLETED", vo.getStatus());

        List<ScanProgressEvent> events = capturedEvents();
        assertEquals(9, events.size());
        assertEvent(events.get(0), "PENDING", 1, "扫描任务已创建，等待执行...");
        assertEvent(events.get(1), "CLONING", 10, "开始克隆仓库...");
        assertEvent(events.get(2), "PARSING", -1, "内存紧张: PARSING 阶段堆使用率 90% (已用 900MB / 上限 1000MB)");
        assertEvent(events.get(3), "PARSING", 30, "解析 Java 源码...");
        assertEvent(events.get(4), "NEO4J_IMPORT", -1, "内存紧张: NEO4J_IMPORT 阶段堆使用率 90% (已用 900MB / 上限 1000MB)");
        assertEvent(events.get(5), "RULES", 60, "运行架构规则检查...");
        assertEvent(events.get(6), "AI_ANALYSIS", -1, "内存紧张: AI_ANALYSIS 阶段堆使用率 90% (已用 900MB / 上限 1000MB)");
        assertEvent(events.get(7), "AI", 80, "开始 AI 架构分析...");
        assertEvent(events.get(8), "COMPLETED", 100, "扫描完成");

        ArgumentCaptor<ScanRecord> scanCaptor = ArgumentCaptor.forClass(ScanRecord.class);
        verify(scanMapper).updateStats(scanCaptor.capture());
        Long durationMs = scanCaptor.getValue().getDurationMs();
        assertNotNull(durationMs);
        assertTrue(durationMs >= 0 && durationMs < 60_000L,
                "durationMs 必须为当前时间与开始时间的差值而非时间戳相加，实际=" + durationMs);
    }

    @Test
    @DisplayName("规则映射：RuleDefinition 字段逐项拷贝，isEnabled 不得丢失（setIsEnabled 调用保留）")
    void executeScan_shouldCopyRuleDefinitionFieldsIncludingEnabled() throws IOException {
        Path sourceDir = createLocalSourceDir("src-rule");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(
                project(PROJECT_ID, "演示项目", "LOCAL", sourceDir.toString()));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        stubInsertAssignsId();
        ClassSummaryResult a = classResult("com.x.A", "A", "service", 5, 10, 100);
        a.setAnnotations(Collections.emptyList());
        a.setDependencies(Collections.emptyList());
        a.setMethodNames(Collections.emptyList());
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.singletonList(a));
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(1L, "max-public-methods", "ERROR", "{\"max\":3}")));
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(scoreEntity("com.x.A")));
        when(cacheManager.getCache("mapData")).thenReturn(cache);

        try (MockedConstruction<RuleEngine> constructed = mockConstruction(RuleEngine.class,
                (engine, context) -> when(engine.check(anyList(), anyList())).thenReturn(Collections.emptyList()))) {
            service.triggerScan(PROJECT_ID, USER_ID);

            ArgumentCaptor<List<RuleDefinition>> defsCaptor = ArgumentCaptor.forClass(List.class);
            verify(constructed.constructed().get(0)).check(defsCaptor.capture(), anyList());
            assertEquals(1, defsCaptor.getValue().size());
            RuleDefinition def = defsCaptor.getValue().get(0);
            assertEquals(1L, def.getId());
            assertEquals("max-public-methods", def.getName());
            assertEquals(Boolean.TRUE, def.getIsEnabled(), "isEnabled 必须从实体拷贝，丢失会静默禁用规则");
            assertEquals("ERROR", def.getSeverity());
            assertEquals("{\"max\":3}", def.getRuleDefinition());
        }
    }

    @Test
    @DisplayName("工作目录清理抛异常：截断 updateStats，但扫描对象已带真实违规数（setTotalViolations 调用保留）")
    void triggerScan_cleanupThrows_shouldStillExposeViolationCount() throws IOException {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(1024L);
        stubInsertAssignsId();
        when(gitService.cloneRepository(eq(GIT_URL), eq("main"), any(Path.class), any()))
                .thenAnswer(invocation -> {
                    Path target = invocation.getArgument(2);
                    Files.createDirectories(target);
                    return new GitResult(target.toString(), "main", "abc123def", true, "clone ok");
                });
        ClassSummaryResult a = classResult("com.x.A", "A", "service", 5, 10, 100);
        a.setAnnotations(Collections.emptyList());
        a.setDependencies(Collections.emptyList());
        a.setMethodNames(Collections.emptyList());
        when(javaParserService.analyzeDirectory(any(Path.class))).thenReturn(Collections.singletonList(a));
        when(constitutionRuleService.getRules(PROJECT_ID)).thenReturn(Collections.singletonList(
                ruleEntity(1L, "max-public-methods", "ERROR", "{\"max\":3}")));
        doThrow(new RuntimeException("cleanup exploded")).when(gitService).deleteLocalRepo(any(Path.class));

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        assertEquals(1, vo.getTotalViolations(),
                "清理异常在 updateStats 之前截断流程，违规数只能来自执行段内的写入");
        verify(gitService).deleteLocalRepo(any(Path.class));
        verify(scanMapper, never()).updateStats(any(ScanRecord.class));
    }

    @Test
    @DisplayName("catch 段内告警抛异常：异常传播副本的 finally 仍删除工作目录（catch 收尾被截断）")
    void triggerScan_alertThrows_shouldStillDeleteWorkDirViaExceptionCopy() {
        Project project = project(PROJECT_ID, "远程项目", "GIT_URL", GIT_URL);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(gitService.estimateRepoSize(GIT_URL)).thenReturn(1024L);
        stubInsertAssignsId();
        when(gitService.cloneRepository(eq(GIT_URL), eq("main"), any(Path.class), any()))
                .thenReturn(new GitResult(null, "main", null, false, "认证失败"));
        doThrow(new RuntimeException("alert down")).when(alertService).sendAlert(anyString(), anyString());

        ScanVO vo = service.triggerScan(PROJECT_ID, USER_ID);

        // 字节码证实：异常从 catch 段传播时走独立 finally 副本（javap 偏移 1370），
        // setStatus(FAILED)/setErrorMessage 被打断，状态保持 RUNNING
        assertEquals("RUNNING", vo.getStatus(),
                "sendAlert 在 catch 段内抛出，setStatus(FAILED) 与 setErrorMessage 均被跳过");
        verify(alertService).sendAlert("扫描失败 — 远程项目",
                "项目 **远程项目** (id=7) 扫描失败。\n错误: Git clone failed: 认证失败");
        ArgumentCaptor<Path> workDirCaptor = ArgumentCaptor.forClass(Path.class);
        verify(gitService).deleteLocalRepo(workDirCaptor.capture());
        assertEquals("7-100", workDirCaptor.getValue().getFileName().toString(),
                "异常传播副本的 finally 仍须清理克隆目标目录");
        verify(scanMapper, never()).updateStats(any(ScanRecord.class));
        verify(classSummaryMapper, never()).deleteByScanId(any());
    }

    @Test
    @DisplayName("最近扫描查询：先做项目鉴权再读库（check 调用不得被移除）")
    void getLatestScan_shouldCheckAccessBeforeQuery() {
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);

        assertNull(service.getLatestScan(PROJECT_ID, USER_ID));

        verify(projectAccessChecker).check(PROJECT_ID, USER_ID);
    }

    @Test
    @DisplayName("toJson 序列化抛 JsonProcessingException：兜底为空数组字符串（不得返回空串）")
    void toJson_serializationFailure_shouldFallBackToEmptyArray() {
        List<String> bomb = new AbstractList<String>() {
            @Override
            public int size() {
                return 1;
            }

            @Override
            public String get(int index) {
                // 匿名子类可访问 protected 构造器；经 sneakyThrow 绕过受检异常声明
                throw sneakyThrow(new JsonProcessingException("boom") {
                });
            }
        };

        assertEquals("[]", ReflectionTestUtils.invokeMethod(service, "toJson", bomb));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        throw (T) throwable;
    }

    /**
     * Runtime.maxMemory/totalMemory/freeMemory 均为 native，无法被 Mockito 改写；
     * 用 ByteBuddy 生成子类覆写三个方法为返回受控常量，Objenesis 绕过私有构造器实例化，
     * 再由 mockStatic 让 getRuntime() 返回该替身。
     */
    private Runtime stubRuntime(long max, long total, long free) {
        try {
            Class<? extends Runtime> type = new ByteBuddy()
                    .subclass(Runtime.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .method(ElementMatchers.named("maxMemory")).intercept(FixedValue.value((Object) max))
                    .method(ElementMatchers.named("totalMemory")).intercept(FixedValue.value((Object) total))
                    .method(ElementMatchers.named("freeMemory")).intercept(FixedValue.value((Object) free))
                    .make()
                    .load(ScanServiceImplTest.class.getClassLoader())
                    .getLoaded();
            return new ObjenesisStd().newInstance(type);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("构造 Runtime 受控替身失败", e);
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ScanServiceImpl.class))
                .addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ScanServiceImpl.class))
                .detachAppender(appender);
    }
}
