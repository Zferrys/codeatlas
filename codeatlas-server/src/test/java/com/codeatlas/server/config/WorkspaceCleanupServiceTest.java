package com.codeatlas.server.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ScanMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WorkspaceCleanupService 工作目录清理测试（真实文件系统）")
class WorkspaceCleanupServiceTest {

    @TempDir
    Path tempDir;

    @Mock
    private ScanMapper scanMapper;

    private WorkspaceConfig workspaceConfig;
    private WorkspaceCleanupService service;
    private final ApplicationArguments args = mock(ApplicationArguments.class);

    @BeforeEach
    void setUp() {
        workspaceConfig = new WorkspaceConfig();
        workspaceConfig.setDir(tempDir.resolve("workspace").toString());
        workspaceConfig.init();
        service = new WorkspaceCleanupService(workspaceConfig, scanMapper);
    }

    private Path scansDir() {
        return workspaceConfig.getScansDir();
    }

    private Path uploadsDir() {
        return workspaceConfig.getUploadsDir();
    }

    private static Path createDirWithContent(Path parent, String name, String fileName) throws IOException {
        Path dir = parent.resolve(name);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(fileName), "content");
        return dir;
    }

    private static void setLastModifiedHoursAgo(Path path, long hoursAgo) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(hoursAgo, ChronoUnit.HOURS)));
    }

    private static void deleteRecursively(Path path) {
        try {
            if (Files.exists(path)) {
                Files.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.delete(p);
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("启动清理（retention=0）：删除所有非活跃目录及其内容")
    void run_retentionZero_shouldDeleteAllInactiveDirectories() throws IOException {
        Path scansOrphan = createDirWithContent(scansDir(), "1-100", "a.txt");
        Path uploadsOrphan = createDirWithContent(uploadsDir(), "2-200", "b.txt");

        service.run(args);

        assertFalse(Files.exists(scansOrphan), "scans 孤儿目录应被删除");
        assertFalse(Files.exists(uploadsOrphan), "uploads 孤儿目录应被删除");
        verify(scanMapper).findById(100L);
        verify(scanMapper).findById(200L);
    }

    @Test
    @DisplayName("启动清理：正在 RUNNING 的扫描目录被跳过，其余照删")
    void run_shouldSkipActiveRunningScanDirectory() throws IOException {
        Path runningDir = createDirWithContent(scansDir(), "5-777", "running.txt");
        Path doneDir = createDirWithContent(scansDir(), "5-778", "done.txt");
        ScanRecord running = new ScanRecord();
        running.setId(777L);
        running.setStatus("RUNNING");
        ScanRecord completed = new ScanRecord();
        completed.setId(778L);
        completed.setStatus("COMPLETED");
        when(scanMapper.findById(777L)).thenReturn(running);
        when(scanMapper.findById(778L)).thenReturn(completed);

        service.run(args);

        assertTrue(Files.exists(runningDir.resolve("running.txt")), "RUNNING 扫描目录必须保留");
        assertFalse(Files.exists(doneDir), "已结束扫描目录应被删除");
    }

    @Test
    @DisplayName("目录名用最后一个 '-' 之前的数字解析 scanId（proj-42-99 取 99）")
    void cleanup_shouldParseScanIdAfterLastDash() throws IOException {
        Path dir = createDirWithContent(scansDir(), "proj-42-99", "f.txt");

        service.run(args);

        verify(scanMapper).findById(99L);
        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("目录名不含 '-'：无法解析 scanId，直接删除")
    void cleanup_dirWithoutDash_shouldBeDeletedWithoutLookup() throws IOException {
        Path dir = createDirWithContent(scansDir(), "nodash", "f.txt");

        service.run(args);

        assertFalse(Files.exists(dir));
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("目录名后缀非数字：NumberFormatException 保守放行（当作非活跃删除）")
    void cleanup_nonNumericSuffix_shouldBeTreatedAsInactive() throws IOException {
        Path dir = createDirWithContent(scansDir(), "5-abc", "f.txt");

        service.run(args);

        assertFalse(Files.exists(dir));
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("目录名以 '-' 结尾（空后缀）：解析失败，直接删除")
    void cleanup_trailingDash_shouldBeTreatedAsInactive() throws IOException {
        Path dir = createDirWithContent(scansDir(), "5-", "f.txt");

        service.run(args);

        assertFalse(Files.exists(dir));
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("目录名以 '-' 开头（lastDash==0）：不查询直接删除")
    void cleanup_leadingDash_shouldBeTreatedAsInactive() throws IOException {
        Path dir = createDirWithContent(scansDir(), "-5", "f.txt");

        service.run(args);

        assertFalse(Files.exists(dir));
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("普通文件（非目录）不参与清理")
    void cleanup_regularFile_shouldBeKept() throws IOException {
        Path file = scansDir().resolve("loose-file.txt");
        Files.writeString(file, "keep");

        service.run(args);

        assertTrue(Files.exists(file), "非目录条目不应被删除");
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("目录为空：提前返回，不查询扫描记录")
    void cleanup_emptyDirs_shouldReturnEarly() {
        service.run(args);

        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("目录不存在：提前返回不报错")
    void cleanup_missingDirs_shouldReturnEarly() throws IOException {
        Files.delete(scansDir());
        Files.delete(uploadsDir());

        service.run(args);

        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("scans 路径实为普通文件：listFiles 返回 null，跳过该目录不报错")
    void cleanup_dirPathIsFile_shouldSkipSafely() throws IOException {
        Files.delete(scansDir());
        Files.createFile(scansDir()); // 同名普通文件：Files.exists 为 true，但 listFiles() 返回 null

        service.run(args);

        assertTrue(Files.exists(scansDir()));
        verifyNoInteractions(scanMapper);
    }

    @Test
    @DisplayName("定时清理：保留期内的新目录被跳过，超期目录被删除")
    void scheduledCleanup_shouldSkipRecentAndDeleteExpired() throws IOException {
        workspaceConfig.setRetentionHours(24);
        Path recent = createDirWithContent(scansDir(), "1-1", "recent.txt");
        Path old = createDirWithContent(scansDir(), "1-2", "old.txt");
        setLastModifiedHoursAgo(old, 48);

        service.scheduledCleanup();

        assertTrue(Files.exists(recent), "24h 保留期内目录应保留");
        assertFalse(Files.exists(old), "超过 24h 的目录应删除");
    }

    @Test
    @DisplayName("定时清理：保留期取自配置（100h 时 48h 目录仍保留）")
    void scheduledCleanup_shouldHonorConfiguredRetentionHours() throws IOException {
        workspaceConfig.setRetentionHours(100);
        Path dir48hAgo = createDirWithContent(scansDir(), "1-3", "f.txt");
        setLastModifiedHoursAgo(dir48hAgo, 48);

        service.scheduledCleanup();

        assertTrue(Files.exists(dir48hAgo), "100h 保留期下 48h 目录不应被删");
    }

    @Test
    @DisplayName("定时清理：读属性失败（目录在扫描活跃性检查后被删除）走 IO 兜底，随后删除阶段同样兜底不中断")
    void scheduledCleanup_shouldTolerateDirectoryVanishingMidScan() throws IOException {
        workspaceConfig.setRetentionHours(24);
        final Path vanishing = createDirWithContent(scansDir(), "9-999", "f.txt");
        // findById 回调中把目录删掉：模拟检查与读属性之间的并发删除
        when(scanMapper.findById(999L)).thenAnswer(invocation -> {
            deleteRecursively(vanishing);
            return null;
        });

        service.scheduledCleanup();

        assertFalse(Files.exists(vanishing));
    }

    @Test
    @DisplayName("启动清理：目录在活跃性检查后消失时，删除/统计路径有 IO 兜底不抛异常")
    void run_shouldTolerateVanishingDirectory() throws IOException {
        final Path vanishing = createDirWithContent(scansDir(), "9-888", "f.txt");
        when(scanMapper.findById(888L)).thenAnswer(invocation -> {
            deleteRecursively(vanishing);
            return null;
        });

        service.run(args);

        assertFalse(Files.exists(vanishing));
    }

    @Test
    @DisplayName("嵌套子目录被完整删除（含深层文件）")
    void cleanup_shouldDeleteNestedContentRecursively() throws IOException {
        Path dir = scansDir().resolve("7-701");
        Files.createDirectories(dir.resolve("a/b"));
        Files.writeString(dir.resolve("a/b/deep.txt"), "x");

        service.run(args);

        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("uploads 与 scans 双目录均按同名规则处理（label 分离）")
    void cleanup_shouldProcessBothScansAndUploads() throws IOException {
        Path uploadsOrphan = createDirWithContent(uploadsDir(), "3-300", "u.txt");
        ScanRecord running = new ScanRecord();
        running.setId(300L);
        running.setStatus("RUNNING");
        when(scanMapper.findById(300L)).thenReturn(running);

        service.run(args);

        assertTrue(Files.exists(uploadsOrphan), "uploads 中 RUNNING 扫描目录也应保留");
        verify(scanMapper).findById(300L);
    }

    @Test
    @DisplayName("目录大小统计结果不影响删除行为（含空目录）")
    void cleanup_emptySubDirectoryShouldBeDeleted() throws IOException {
        Path emptyDir = scansDir().resolve("8-800");
        Files.createDirectories(emptyDir);

        service.run(args);

        assertFalse(Files.exists(emptyDir));
        verify(scanMapper).findById(800L);
    }

    @Test
    @DisplayName("保留期为 0 时不读取文件属性（retentionHours > 0 分支不生效）")
    void run_zeroRetentionShouldNotStatDirectories() throws IOException {
        Path old = createDirWithContent(scansDir(), "0-0", "f.txt");
        setLastModifiedHoursAgo(old, 1); // 一分钟前的目录在 retention=0 下也应删除

        service.run(args);

        assertFalse(Files.exists(old));
    }

    @Test
    @DisplayName("scan 记录存在但状态非 RUNNING（null）：视为非活跃删除")
    void cleanup_scanWithNullStatus_shouldBeInactive() throws IOException {
        Path dir = createDirWithContent(scansDir(), "6-600", "f.txt");
        ScanRecord scan = new ScanRecord();
        scan.setId(600L);
        scan.setStatus(null);
        when(scanMapper.findById(600L)).thenReturn(scan);

        service.run(args);

        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("非目录条目忽略：混杂在目录中的文件不查询 scanId")
    void cleanup_shouldIgnoreNonDirectoryEntries() throws IOException {
        Files.writeString(scansDir().resolve("stray.txt"), "x");
        Path dir = createDirWithContent(scansDir(), "4-400", "f.txt");

        service.run(args);

        assertTrue(Files.exists(scansDir().resolve("stray.txt")));
        assertFalse(Files.exists(dir));
        verify(scanMapper).findById(400L);
    }

    @Test
    @DisplayName("文件大小统计抛 IOException：getDirectorySize 单文件兜底为 0，删除流程照常完成")
    void cleanup_fileSizeFails_shouldFallBackToZeroAndStillDelete() throws IOException {
        Path dir = createDirWithContent(scansDir(), "9-901", "f.txt");

        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mockedFiles.when(() -> Files.size(any(Path.class)))
                    .thenThrow(new IOException("size unavailable"));

            service.run(args);
        }

        assertFalse(Files.exists(dir), "文件大小读取失败不得阻断目录删除");
        verify(scanMapper).findById(901L);
    }

    // ==================== 以下为变异体杀灭追加（PIT 幸存者定点清除） ====================

    @Test
    @DisplayName("清理摘要日志：删除 1 个目录且实际释放 2MB（计数递增 + 字节数累加）")
    void cleanup_aggregateLogReportsRealFreedBytes() throws IOException {
        Path dir = scansDir().resolve("1-500");
        Files.createDirectories(dir);
        Files.write(dir.resolve("big.bin"), new byte[2 * 1024 * 1024]);
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            service.run(args);

            assertEquals(1, appender.list.stream().filter(e ->
                            "scans cleanup: deleted 1 dirs, freed ~2 MB".equals(e.getFormattedMessage()))
                    .count(), "摘要日志必须反映真实删除数与释放字节数");
        } finally {
            detachLogAppender(appender);
        }
        assertFalse(Files.exists(dir));
    }

    @Test
    @DisplayName("全部目录均为活跃：删除数为 0 时不输出清理摘要日志")
    void cleanup_allDirectoriesActive_shouldNotLogAggregate() throws IOException {
        Path runningDir = createDirWithContent(scansDir(), "5-777", "running.txt");
        ScanRecord running = new ScanRecord();
        running.setId(777L);
        running.setStatus("RUNNING");
        when(scanMapper.findById(777L)).thenReturn(running);
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            service.run(args);

            assertFalse(appender.list.stream().anyMatch(e ->
                            e.getFormattedMessage().startsWith("scans cleanup: deleted")),
                    "删除数为 0 时不得输出摘要日志");
        } finally {
            detachLogAppender(appender);
        }
        assertTrue(Files.exists(runningDir));
    }

    @Test
    @DisplayName("启动清理 retention=0：目录消失后不进入保留期读取分支（无读属性告警）")
    void run_zeroRetention_vanishingDirectory_shouldNotReadAttributes() throws IOException {
        final Path vanishing = createDirWithContent(scansDir(), "9-999", "f.txt");
        when(scanMapper.findById(999L)).thenAnswer(invocation -> {
            deleteRecursively(vanishing);
            return null;
        });
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        try {
            service.run(args);

            assertFalse(appender.list.stream().anyMatch(e ->
                            e.getFormattedMessage().startsWith("Cannot read attributes for")),
                    "retentionHours=0 不得进入保留期分支读取文件属性");
        } finally {
            detachLogAppender(appender);
        }
        assertFalse(Files.exists(vanishing));
    }

    @Test
    @DisplayName("目录大小：仅累加普通文件字节数，目录项一律不计入")
    void getDirectorySize_shouldSumOnlyRegularFilesSizes() throws IOException {
        Path dir = scansDir().resolve("1-777");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("small.txt"), "tiny");

        try (MockedStatic<Files> mockedFiles = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            mockedFiles.when(() -> Files.size(any(Path.class))).thenAnswer(invocation -> {
                Path path = invocation.getArgument(0);
                return Files.isDirectory(path) ? 2_000_000L : 5L;
            });

            Long size = ReflectionTestUtils.invokeMethod(service, "getDirectorySize", dir);

            assertEquals(5L, size.longValue(), "目录项（根目录自身）不得计入大小统计");
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkspaceCleanupService.class))
                .addAppender(appender);
        return appender;
    }

    private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkspaceCleanupService.class))
                .detachAppender(appender);
    }
}
