package com.codeatlas.server.service;

import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.scaffold.subclass.ConstructorStrategy;
import net.bytebuddy.implementation.FixedValue;
import net.bytebuddy.matcher.ElementMatchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.objenesis.ObjenesisStd;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CleanupService 单元测试（磁盘/内存检查 + 孤儿目录 + 上传清理 + 不活跃项目）")
class CleanupServiceTest {

    private static final long MB = 1024L * 1024L;
    private static final long GB = 1024L * 1024L * 1024L;

    @TempDir
    Path tempDir;

    @Mock
    private WorkspaceConfig workspaceConfig;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private AlertService alertService;

    private CleanupService service;

    @BeforeEach
    void setUp() {
        service = new CleanupService(workspaceConfig, projectMapper, scanMapper, alertService);
    }

    // ========== 共享工具 ==========

    /**
     * 构造磁盘数字受控的 baseDir：toFile() 返回 mock File（磁盘容量可控）。
     * 目录体积计算走 mock Path：exists 判定为真后 Files.walk 在 mock provider 上失败，
     * 由 calculateDirSize 内部捕获并计 0，不影响磁盘百分比等断言。
     */
    private void stubDisk(long totalSpace, long freeSpace, long usableSpace) {
        File diskFile = mock(File.class);
        lenient().when(diskFile.getTotalSpace()).thenReturn(totalSpace);
        lenient().when(diskFile.getFreeSpace()).thenReturn(freeSpace);
        lenient().when(diskFile.getUsableSpace()).thenReturn(usableSpace);

        FileSystemProvider provider = mock(FileSystemProvider.class);
        java.nio.file.FileSystem fileSystem = mock(java.nio.file.FileSystem.class);
        lenient().when(fileSystem.provider()).thenReturn(provider);

        Path baseDir = mock(Path.class);
        lenient().when(baseDir.getFileSystem()).thenReturn(fileSystem);
        lenient().when(baseDir.toFile()).thenReturn(diskFile);
        lenient().when(workspaceConfig.getBaseDir()).thenReturn(baseDir);
    }

    private static Project project(Long id, String name, LocalDateTime lastAccessed) {
        Project project = new Project();
        project.setId(id);
        project.setName(name);
        project.setLastAccessedAt(lastAccessed);
        return project;
    }

    private static Project sourceProject(String sourceType, String sourceUrl) {
        Project project = new Project();
        project.setSourceType(sourceType);
        project.setSourceUrl(sourceUrl);
        return project;
    }

    private static ScanRecord scanAt(LocalDateTime createdAt) {
        ScanRecord scan = new ScanRecord();
        scan.setCreatedAt(createdAt);
        return scan;
    }

    private Object field(String name) {
        return ReflectionTestUtils.getField(service, name);
    }

    private static void writeBytes(Path path, int size) throws IOException {
        Files.write(path, new byte[size]);
    }

    // ========== scheduledCleanup ==========

    @Test
    @DisplayName("定时清理：磁盘充足且无工作目录时全链路空跑，不发告警")
    void scheduledCleanup_healthyEnvironment_shouldRunAllStepsWithoutAlerts() {
        stubDisk(100 * GB, 50 * GB, 50 * GB);
        when(workspaceConfig.getScansDir()).thenReturn(null);
        when(workspaceConfig.getUploadsDir()).thenReturn(null);
        when(projectMapper.findAll()).thenReturn(Collections.emptyList());

        service.scheduledCleanup();

        verify(projectMapper).findAll();
        verifyNoInteractions(alertService);
        assertNull(field("lastCleanupTime"), "工作目录为空时两个清理步骤应早退，不更新统计");
    }

    // ========== checkDiskSpace ==========

    @Test
    @DisplayName("磁盘检查：可用 30% 不触发任何清理与告警")
    void checkDiskSpace_plentyFree_shouldNotAlert() {
        stubDisk(100 * GB, 30 * GB, 30 * GB);

        service.checkDiskSpace();

        verifyNoInteractions(alertService);
        verify(workspaceConfig, never()).getScansDir();
        verify(workspaceConfig, never()).getUploadsDir();
    }

    @Test
    @DisplayName("磁盘检查边界：可用恰为 20% 不触发清理与告警")
    void checkDiskSpace_exactlyTwentyPercent_shouldNotAlert() {
        stubDisk(100 * GB, 20 * GB, 20 * GB);

        service.checkDiskSpace();

        verifyNoInteractions(alertService);
        verify(workspaceConfig, never()).getScansDir();
    }

    @Test
    @DisplayName("磁盘检查：可用 19% 发磁盘告警并清理孤儿目录，但不清理上传")
    void checkDiskSpace_belowTwenty_shouldAlertAndCleanOrphansOnly() {
        stubDisk(100 * GB, 19 * GB, 19 * GB);
        when(workspaceConfig.getScansDir()).thenReturn(null);

        service.checkDiskSpace();

        verify(alertService).sendAlert(eq("磁盘空间告警"),
                eq("磁盘空间不足: 可用 19% (19GB/100GB), 工作空间占用 0MB"));
        verify(workspaceConfig).getScansDir();
        verify(workspaceConfig, never()).getUploadsDir();
    }

    @Test
    @DisplayName("磁盘检查：可用低于 10% 额外触发上传清理")
    void checkDiskSpace_belowTenPercent_shouldAlsoCleanUploads() {
        stubDisk(100 * GB, 9 * GB, 9 * GB);
        when(workspaceConfig.getScansDir()).thenReturn(null);
        when(workspaceConfig.getUploadsDir()).thenReturn(null);

        service.checkDiskSpace();

        verify(alertService).sendAlert(eq("磁盘空间告警"),
                eq("磁盘空间不足: 可用 9% (9GB/100GB), 工作空间占用 0MB"));
        verify(workspaceConfig).getScansDir();
        verify(workspaceConfig).getUploadsDir();
    }

    @Test
    @DisplayName("磁盘检查：告警发送失败被吞掉，清理流程继续")
    void checkDiskSpace_alertFails_shouldSwallowAndContinue() {
        stubDisk(100 * GB, 19 * GB, 19 * GB);
        when(workspaceConfig.getScansDir()).thenReturn(null);
        doThrow(new RuntimeException("webhook down")).when(alertService)
                .sendAlert(anyString(), anyString());

        service.checkDiskSpace();

        verify(alertService).sendAlert(anyString(), anyString());
        verify(workspaceConfig).getScansDir();
    }

    @Test
    @DisplayName("磁盘检查：baseDir 获取异常被捕获，不影响调用方")
    void checkDiskSpace_baseDirBroken_shouldSwallowException() {
        when(workspaceConfig.getBaseDir()).thenReturn(null);

        service.checkDiskSpace();

        verifyNoInteractions(alertService);
    }

    // ========== checkMemory ==========

    /**
     * Runtime.maxMemory/totalMemory/freeMemory 声明为 native（javap 可验证），
     * Mockito 无法改写其方法体，常规 mock(Runtime.class) 打桩会抛
     * MissingMethodInvocationException。改用 ByteBuddy 生成 Runtime 子类：native
     * 属性不影响子类覆写，三个方法覆写为返回受控常量；不定义构造器（Runtime 构造器
     * 私有）+ Objenesis 绕过构造器直接实例化，再由 mockStatic 让 getRuntime() 返回替身。
     */
    private Runtime stubRuntime(long max, long total, long free) {
        try {
            Class<? extends Runtime> type = new ByteBuddy()
                    .subclass(Runtime.class, ConstructorStrategy.Default.NO_CONSTRUCTORS)
                    .method(ElementMatchers.named("maxMemory")).intercept(FixedValue.value((Object) max))
                    .method(ElementMatchers.named("totalMemory")).intercept(FixedValue.value((Object) total))
                    .method(ElementMatchers.named("freeMemory")).intercept(FixedValue.value((Object) free))
                    .make()
                    .load(CleanupServiceTest.class.getClassLoader())
                    .getLoaded();
            return new ObjenesisStd().newInstance(type);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("构造 Runtime 受控替身失败", e);
        }
    }

    private void checkMemoryWith(Runtime runtime) {
        try (MockedStatic<Runtime> mocked = mockStatic(Runtime.class)) {
            mocked.when(Runtime::getRuntime).thenReturn(runtime);
            service.checkMemory();
        }
    }

    @Test
    @DisplayName("内存检查：堆使用率 86% 触发 JVM 内存告警（精确内容）")
    void checkMemory_heapHigh_shouldAlertWithExactContent() {
        checkMemoryWith(stubRuntime(2000 * MB, 2000 * MB, 280 * MB));

        verify(alertService).sendAlert(eq("JVM 内存告警"),
                eq("堆内存使用率 86% (1720MB/2000MB)"));
    }

    @Test
    @DisplayName("内存检查边界：堆使用率恰为 85% 且可用内存充足，不发任何告警")
    void checkMemory_exactlyEightyFivePercent_shouldNotAlert() {
        checkMemoryWith(stubRuntime(2000 * MB, 2000 * MB, 300 * MB));

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("内存检查：堆正常但系统可用内存低于 200MB 触发系统内存告警")
    void checkMemory_systemMemoryLow_shouldAlertWithExactContent() {
        checkMemoryWith(stubRuntime(1000 * MB, 900 * MB, 50 * MB));

        verify(alertService).sendAlert(eq("系统内存不足"),
                eq("可用物理内存仅 150MB，建议清理不活跃项目"));
    }

    @Test
    @DisplayName("内存检查：堆告警发送失败被吞掉")
    void checkMemory_heapAlertFails_shouldSwallow() {
        doThrow(new RuntimeException("webhook down")).when(alertService)
                .sendAlert(anyString(), anyString());

        checkMemoryWith(stubRuntime(2000 * MB, 2000 * MB, 280 * MB));

        verify(alertService).sendAlert(anyString(), anyString());
    }

    @Test
    @DisplayName("内存检查：系统内存告警发送失败被吞掉")
    void checkMemory_systemAlertFails_shouldSwallow() {
        doThrow(new RuntimeException("webhook down")).when(alertService)
                .sendAlert(anyString(), anyString());

        checkMemoryWith(stubRuntime(1000 * MB, 900 * MB, 50 * MB));

        verify(alertService).sendAlert(eq("系统内存不足"), anyString());
    }

    // ========== cleanOrphanDirectories ==========

    @Test
    @DisplayName("孤儿目录清理：扫描目录为 null/不存在/是文件时安全返回且不更新统计")
    void cleanOrphanDirectories_missingScansDir_shouldReturnEarly() throws IOException {
        ReflectionTestUtils.setField(service, "lastCleanupFreedBytes", 123L);
        ReflectionTestUtils.setField(service, "lastCleanupFileCount", 3);

        when(workspaceConfig.getScansDir()).thenReturn(null);
        service.cleanOrphanDirectories();

        when(workspaceConfig.getScansDir()).thenReturn(tempDir.resolve("no-such-dir"));
        service.cleanOrphanDirectories();

        Path asFile = tempDir.resolve("plain.txt");
        writeBytes(asFile, 1);
        when(workspaceConfig.getScansDir()).thenReturn(asFile);
        service.cleanOrphanDirectories();

        assertNull(field("lastCleanupTime"), "早退不应刷新清理时间");
        assertEquals(123L, field("lastCleanupFreedBytes"), "早退不应改动释放字节数");
        assertEquals(3, field("lastCleanupFileCount"), "早退不应改动文件计数");
    }

    @Test
    @DisplayName("孤儿目录清理：删除超 24 小时且命名匹配的目录，保留命名不匹配/过新/读属性失败的项")
    void cleanOrphanDirectories_mixedDirs_shouldDeleteOnlyOldMatchingOnes() throws IOException {
        Path scansDir = Files.createDirectories(tempDir.resolve("scans"));
        Path oldDir = Files.createDirectories(scansDir.resolve("123-456"));
        Files.createDirectories(oldDir.resolve("nested"));
        writeBytes(oldDir.resolve("a.bin"), 2048);
        writeBytes(oldDir.resolve("nested/b.bin"), 1024);

        Path mismatch = Files.createDirectories(scansDir.resolve("abc-xyz"));
        Path brokenAttrs = Files.createDirectories(scansDir.resolve("789-012"));
        Path young = Files.createDirectories(scansDir.resolve("456-789"));
        Path plainFile = scansDir.resolve("note.txt");
        writeBytes(plainFile, 1);

        BasicFileAttributes oldAttrs = mock(BasicFileAttributes.class);
        lenient().when(oldAttrs.creationTime())
                .thenReturn(FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));

        when(workspaceConfig.getScansDir()).thenReturn(scansDir);
        try (MockedStatic<Files> mocked = mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.readAttributes(eq(oldDir), eq(BasicFileAttributes.class)))
                    .thenReturn(oldAttrs);
            mocked.when(() -> Files.readAttributes(eq(mismatch), eq(BasicFileAttributes.class)))
                    .thenReturn(oldAttrs);
            mocked.when(() -> Files.readAttributes(eq(brokenAttrs), eq(BasicFileAttributes.class)))
                    .thenThrow(new IOException("attrs boom"));

            service.cleanOrphanDirectories();
        }

        assertFalse(Files.exists(oldDir), "超龄且命名匹配的目录应被删除");
        assertTrue(Files.exists(mismatch), "命名不匹配的目录应保留");
        assertTrue(Files.exists(brokenAttrs), "读属性失败的目录应保留");
        assertTrue(Files.exists(young), "未超龄的目录应保留");
        assertTrue(Files.exists(plainFile), "普通文件应保留");

        assertEquals(3072L, field("lastCleanupFreedBytes"));
        assertEquals(1, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("孤儿目录清理：无命中目录时统计保持为 0，但刷新清理时间")
    void cleanOrphanDirectories_nothingMatched_shouldKeepZeroStats() throws IOException {
        Path scansDir = Files.createDirectories(tempDir.resolve("scans-empty"));
        writeBytes(scansDir.resolve("note.txt"), 1);
        when(workspaceConfig.getScansDir()).thenReturn(scansDir);

        service.cleanOrphanDirectories();

        assertEquals(0L, field("lastCleanupFreedBytes"));
        assertEquals(0, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("孤儿目录清理：删除动作抛异常被吞掉，目录保留但仍计入本次清理")
    void cleanOrphanDirectories_deleteFails_shouldSwallowPerItem() throws IOException {
        Path scansDir = Files.createDirectories(tempDir.resolve("scans-del-fail"));
        Path oldDir = Files.createDirectories(scansDir.resolve("321-654"));

        BasicFileAttributes oldAttrs = mock(BasicFileAttributes.class);
        when(oldAttrs.creationTime())
                .thenReturn(FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));

        when(workspaceConfig.getScansDir()).thenReturn(scansDir);
        try (MockedStatic<Files> mocked = mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.readAttributes(eq(oldDir), eq(BasicFileAttributes.class)))
                    .thenReturn(oldAttrs);
            mocked.when(() -> Files.deleteIfExists(eq(oldDir)))
                    .thenThrow(new IOException("delete boom"));

            service.cleanOrphanDirectories();
        }

        assertTrue(Files.exists(oldDir), "删除失败时目录应保留");
        assertEquals(0L, field("lastCleanupFreedBytes"), "空目录体积计 0");
        assertEquals(1, field("lastCleanupFileCount"), "删除异常被吞后仍照常记账");
        assertNotNull(field("lastCleanupTime"));
    }

    // ========== cleanOldUploads ==========

    @Test
    @DisplayName("上传清理：删除超 7 天的未引用文件/目录，保留被项目引用与新鲜的文件")
    void cleanOldUploads_shouldDeleteOnlyUnreferencedOldEntries() throws IOException {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads"));
        Path keepDir = Files.createDirectories(uploads.resolve("keepdir"));
        writeBytes(keepDir.resolve("src.txt"), 10);
        Path parentDir = Files.createDirectories(uploads.resolve("parent"));
        Path nestedUpload = Files.createDirectories(parentDir.resolve("inner"));

        Path staleDir = Files.createDirectories(uploads.resolve("stale-dir"));
        writeBytes(staleDir.resolve("old.bin"), 512);
        Path staleFile = uploads.resolve("stale.txt");
        writeBytes(staleFile, 700);
        Path freshFile = uploads.resolve("fresh.txt");
        writeBytes(freshFile, 10);

        FileTime old = FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS));
        Files.setLastModifiedTime(staleDir, old);
        Files.setLastModifiedTime(staleFile, old);
        // 被引用的目录同样设为超龄：验证"引用优先于年龄"的判断顺序
        Files.setLastModifiedTime(keepDir, old);
        Files.setLastModifiedTime(parentDir, old);

        when(projectMapper.findAll()).thenReturn(Arrays.asList(
                sourceProject("ZIP_UPLOAD", keepDir.toAbsolutePath().normalize().toString()),
                sourceProject("ZIP_UPLOAD", nestedUpload.toAbsolutePath().normalize().toString()),
                sourceProject("ZIP_UPLOAD", null),
                sourceProject("GIT", tempDir.resolve("elsewhere").toString())));
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);

        service.cleanOldUploads();

        assertTrue(Files.exists(keepDir), "被项目直接引用的目录应保留");
        assertTrue(Files.exists(parentDir), "被嵌套路径引用的目录应保留");
        assertFalse(Files.exists(staleDir), "未引用且超龄的目录应删除");
        assertFalse(Files.exists(staleFile), "未引用且超龄的文件应删除");
        assertTrue(Files.exists(freshFile), "新鲜文件应保留");

        assertEquals(1212L, field("lastCleanupFreedBytes"));
        assertEquals(2, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("上传清理：项目列表加载失败时跳过清理（不清任何文件）")
    void cleanOldUploads_projectLoadFails_shouldSkipCleaning() throws IOException {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads2"));
        Path staleFile = uploads.resolve("stale.txt");
        writeBytes(staleFile, 700);
        Files.setLastModifiedTime(staleFile, FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);
        when(projectMapper.findAll()).thenThrow(new RuntimeException("db down"));

        service.cleanOldUploads();

        assertTrue(Files.exists(staleFile), "加载项目失败时必须保守跳过清理");
        assertNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("上传清理：上传目录为 null/不存在/是文件时安全返回")
    void cleanOldUploads_missingUploadsDir_shouldReturnEarly() throws IOException {
        when(workspaceConfig.getUploadsDir()).thenReturn(null);
        service.cleanOldUploads();

        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir.resolve("no-uploads"));
        service.cleanOldUploads();

        Path asFile = tempDir.resolve("uploads-as-file");
        writeBytes(asFile, 1);
        when(workspaceConfig.getUploadsDir()).thenReturn(asFile);
        when(projectMapper.findAll()).thenReturn(Collections.emptyList());
        service.cleanOldUploads();

        assertNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("上传清理：空上传目录统计为 0，仍刷新清理时间")
    void cleanOldUploads_emptyDir_shouldRefreshStatsWithZero() throws IOException {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads-empty"));
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);
        when(projectMapper.findAll()).thenReturn(Collections.emptyList());

        service.cleanOldUploads();

        assertEquals(0L, field("lastCleanupFreedBytes"));
        assertEquals(0, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("上传清理：单项读取属性失败被跳过，其余超龄项照常删除")
    void cleanOldUploads_readAttributesFails_shouldSkipOnlyBrokenEntry() throws IOException {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads-attr-fail"));
        Path broken = uploads.resolve("broken.bin");
        writeBytes(broken, 64);
        Path stale = uploads.resolve("stale-skip.txt");
        writeBytes(stale, 128);
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        when(projectMapper.findAll()).thenReturn(Collections.emptyList());
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);

        try (MockedStatic<Files> mocked = mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.readAttributes(eq(broken), eq(BasicFileAttributes.class)))
                    .thenThrow(new IOException("attrs boom"));

            service.cleanOldUploads();
        }

        assertTrue(Files.exists(broken), "读属性失败的项应保留");
        assertFalse(Files.exists(stale), "正常超龄项不受影响，仍应删除");
        assertEquals(128L, field("lastCleanupFreedBytes"));
        assertEquals(1, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    @Test
    @DisplayName("上传清理：目录内文件大小读取失败按 0 字节计，删除动作不受影响")
    void cleanOldUploads_dirSizeReadFails_shouldCountZeroBytes() throws IOException {
        Path uploads = Files.createDirectories(tempDir.resolve("uploads-size-fail"));
        Path staleDir = Files.createDirectories(uploads.resolve("stale-dir"));
        Path inner = staleDir.resolve("inner.bin");
        writeBytes(inner, 4096);
        Files.setLastModifiedTime(staleDir, FileTime.from(Instant.now().minus(8, ChronoUnit.DAYS)));

        when(projectMapper.findAll()).thenReturn(Collections.emptyList());
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);

        try (MockedStatic<Files> mocked = mockStatic(Files.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            mocked.when(() -> Files.size(eq(inner))).thenThrow(new IOException("size boom"));

            service.cleanOldUploads();
        }

        assertFalse(Files.exists(staleDir), "体积读取失败不影响删除动作");
        assertEquals(0L, field("lastCleanupFreedBytes"), "读取失败的文件按 0 字节累计");
        assertEquals(1, field("lastCleanupFileCount"));
        assertNotNull(field("lastCleanupTime"));
    }

    // ========== checkInactiveProjects ==========

    @Test
    @DisplayName("不活跃检查：项目列表加载失败被吞掉")
    void checkInactiveProjects_loadFails_shouldSwallow() {
        when(projectMapper.findAll()).thenThrow(new RuntimeException("db down"));

        service.checkInactiveProjects();

        verifyNoInteractions(alertService);
    }

    @Test
    @DisplayName("不活跃检查：4 个不活跃项目（低于阈值）不发告警，只查需要扫描时间的项目")
    void checkInactiveProjects_belowThreshold_shouldNotAlert() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime old = now.minusDays(40);
        LocalDateTime recent = now.minusDays(5);
        when(projectMapper.findAll()).thenReturn(Arrays.asList(
                project(1L, "NeverAccessed", null),
                project(2L, "OldAccessOldScan", old),
                project(3L, "OldAccessRecentScan", old),
                project(4L, "RecentAccess", recent),
                project(5L, "NeverAccessedOldScan", null),
                project(6L, "NeverAccessedRecentScan", null),
                project(7L, "JustInaccessible", now.minusDays(30).plusMinutes(1)),
                project(8L, "OldAccessBoundaryScan", old),
                project(9L, "OldAccessOldScan2", null)));
        when(scanMapper.findLatestByProjectId(1L)).thenReturn(null);
        when(scanMapper.findLatestByProjectId(2L)).thenReturn(scanAt(now.minusDays(50)));
        when(scanMapper.findLatestByProjectId(3L)).thenReturn(scanAt(now.minusDays(10)));
        when(scanMapper.findLatestByProjectId(5L)).thenReturn(scanAt(now.minusDays(50)));
        when(scanMapper.findLatestByProjectId(6L)).thenReturn(scanAt(now.minusDays(10)));
        when(scanMapper.findLatestByProjectId(8L)).thenReturn(scanAt(now.minusDays(30).plusMinutes(1)));
        when(scanMapper.findLatestByProjectId(9L)).thenReturn(scanAt(now.minusDays(50)));

        service.checkInactiveProjects();

        verifyNoInteractions(alertService);
        verify(scanMapper, times(7)).findLatestByProjectId(anyLong());
        verify(scanMapper, never()).findLatestByProjectId(4L);
        verify(scanMapper, never()).findLatestByProjectId(7L);
    }

    @Test
    @DisplayName("不活跃检查：恰好 5 个不活跃项目触发提醒（精确内容）")
    void checkInactiveProjects_exactlyFiveInactive_shouldAlert() {
        when(projectMapper.findAll()).thenReturn(inactiveProjects(5));
        when(scanMapper.findLatestByProjectId(anyLong())).thenReturn(null);

        service.checkInactiveProjects();

        verify(alertService).sendAlert(eq("不活跃项目提醒"),
                eq("有 5 个项目超过 30 天未访问，建议清理以释放磁盘空间"));
    }

    @Test
    @DisplayName("不活跃检查：超过 5 个不活跃项目按实际数量报出")
    void checkInactiveProjects_moreThanFiveInactive_shouldReportActualCount() {
        when(projectMapper.findAll()).thenReturn(inactiveProjects(7));
        when(scanMapper.findLatestByProjectId(anyLong())).thenReturn(null);

        service.checkInactiveProjects();

        verify(alertService).sendAlert(eq("不活跃项目提醒"),
                eq("有 7 个项目超过 30 天未访问，建议清理以释放磁盘空间"));
    }

    @Test
    @DisplayName("不活跃检查：告警发送失败被吞掉")
    void checkInactiveProjects_alertFails_shouldSwallow() {
        when(projectMapper.findAll()).thenReturn(inactiveProjects(5));
        when(scanMapper.findLatestByProjectId(anyLong())).thenReturn(null);
        doThrow(new RuntimeException("webhook down")).when(alertService)
                .sendAlert(anyString(), anyString());

        service.checkInactiveProjects();

        verify(alertService).sendAlert(eq("不活跃项目提醒"), anyString());
    }

    private List<Project> inactiveProjects(int count) {
        List<Project> projects = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            projects.add(project((long) i, "Stale" + i, null));
        }
        return projects;
    }

    // ========== getCleanupStatus ==========

    @Test
    @DisplayName("清理状态：磁盘/内存/运行时长/项目数各字段齐全，未清理时统计为初始值")
    void getCleanupStatus_fullStatus_shouldContainAllFields() {
        stubDisk(100 * GB, 35 * GB, 35 * GB);
        when(projectMapper.findAll()).thenReturn(Arrays.asList(
                project(1L, "A", null), project(2L, "B", null), project(3L, "C", null)));

        try (MockedStatic<ManagementFactory> mocked = mockStatic(ManagementFactory.class)) {
            RuntimeMXBean bean = mock(RuntimeMXBean.class);
            mocked.when(ManagementFactory::getRuntimeMXBean).thenReturn(bean);
            when(bean.getUptime()).thenReturn(59 * 60_000L);

            LocalDateTime startLowerBound = LocalDateTime.now().minusSeconds(59 * 60);
            Map<String, Object> status = service.getCleanupStatus();
            LocalDateTime startUpperBound = LocalDateTime.now().minusSeconds(59 * 60);

            assertEquals(100.0, status.get("diskTotalGb"));
            assertEquals(35.0, status.get("diskFreeGb"));
            assertEquals(35, status.get("diskFreePercent"));
            assertEquals(0L, status.get("workspaceSizeMb"));
            assertTrue(((Number) status.get("heapMaxMb")).longValue() > 0);
            assertTrue(((Number) status.get("heapTotalMb")).longValue() > 0);
            assertTrue(((Number) status.get("heapFreeMb")).longValue() >= 0);
            long usedPercent = ((Number) status.get("heapUsedPercent")).longValue();
            assertTrue(usedPercent >= 0 && usedPercent <= 100);
            assertEquals(59L, status.get("uptimeMinutes"));
            assertEquals("59m", status.get("uptimeFormatted"));
            LocalDateTime startedAt = LocalDateTime.parse(
                    String.valueOf(status.get("startedAt")).replace(' ', 'T'));
            assertFalse(startedAt.isBefore(startLowerBound),
                    "startedAt 应等于当前时刻回拨 59 分钟");
            assertFalse(startedAt.isAfter(startUpperBound),
                    "startedAt 应等于当前时刻回拨 59 分钟");
            assertEquals("never", status.get("lastCleanupTime"));
            assertEquals(0L, status.get("lastCleanupFreedMb"));
            assertEquals(0, status.get("lastCleanupFileCount"));
            assertEquals(3, status.get("totalProjects"));
            assertFalse(status.containsKey("diskError"));
        }
    }

    @Test
    @DisplayName("清理状态：运行时长格式化分钟/小时/天三档界值")
    void getCleanupStatus_uptimeFormatting_shouldCoverAllTiers() {
        stubDisk(100 * GB, 35 * GB, 35 * GB);
        when(projectMapper.findAll()).thenReturn(Collections.emptyList());

        long[] uptimesMinutes = {0, 59, 60, 90, 1439, 1440, 1500};
        String[] expected = {"0m", "59m", "1h 0m", "1h 30m", "23h 59m", "1d 0h", "1d 1h"};

        try (MockedStatic<ManagementFactory> mocked = mockStatic(ManagementFactory.class)) {
            RuntimeMXBean bean = mock(RuntimeMXBean.class);
            mocked.when(ManagementFactory::getRuntimeMXBean).thenReturn(bean);

            for (int i = 0; i < uptimesMinutes.length; i++) {
                when(bean.getUptime()).thenReturn(uptimesMinutes[i] * 60_000L);
                Map<String, Object> status = service.getCleanupStatus();
                assertEquals(expected[i], status.get("uptimeFormatted"),
                        "uptime=" + uptimesMinutes[i] + " 分钟");
            }
        }
    }

    @Test
    @DisplayName("清理状态：磁盘信息异常记入 diskError，项目数加载失败记为 unknown")
    void getCleanupStatus_brokenEnvironment_shouldReportErrorsGracefully() {
        Path broken = mock(Path.class);
        when(broken.toFile()).thenThrow(new RuntimeException("disk down"));
        when(workspaceConfig.getBaseDir()).thenReturn(broken);
        when(projectMapper.findAll()).thenThrow(new RuntimeException("db down"));

        Map<String, Object> status = service.getCleanupStatus();

        assertEquals("disk down", status.get("diskError"));
        assertFalse(status.containsKey("diskTotalGb"));
        assertEquals("unknown", status.get("totalProjects"));
        assertEquals("never", status.get("lastCleanupTime"));
    }

    @Test
    @DisplayName("清理状态：执行过清理后回报时间/释放容量/文件数")
    void getCleanupStatus_afterCleanup_shouldReportLastRun() {
        stubDisk(100 * GB, 35 * GB, 35 * GB);
        when(projectMapper.findAll()).thenReturn(Collections.emptyList());
        ReflectionTestUtils.setField(service, "lastCleanupTime", LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        ReflectionTestUtils.setField(service, "lastCleanupFreedBytes", 3 * MB + 500);
        ReflectionTestUtils.setField(service, "lastCleanupFileCount", 2);

        Map<String, Object> status = service.getCleanupStatus();

        assertEquals("2026-01-02T03:04:05", status.get("lastCleanupTime"));
        assertEquals(3L, status.get("lastCleanupFreedMb"));
        assertEquals(2, status.get("lastCleanupFileCount"));
    }
}
