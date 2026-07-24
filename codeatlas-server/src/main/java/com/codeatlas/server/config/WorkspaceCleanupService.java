package com.codeatlas.server.config;

import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ScanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;

/**
 * 工作目录清理服务。
 * <ul>
 *   <li>应用启动时清理所有孤儿目录（无对应 RUNNING 扫描的残留目录）</li>
 *   <li>定时清理超过保留期限的扫描/上传临时目录</li>
 *   <li>自动跳过当前正在扫描的目录</li>
 * </ul>
 */
@Component
public class WorkspaceCleanupService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupService.class);

    private final WorkspaceConfig workspaceConfig;
    private final ScanMapper scanMapper;

    public WorkspaceCleanupService(WorkspaceConfig workspaceConfig, ScanMapper scanMapper) {
        this.workspaceConfig = workspaceConfig;
        this.scanMapper = scanMapper;
    }

    /**
     * 应用启动时立即清理一次所有孤儿目录。
     */
    @Override
    public void run(ApplicationArguments args) {
        log.info("Running startup workspace cleanup...");
        cleanupDirectories(0); // retentionHours=0: 删除所有非活跃目录
    }

    /**
     * 定时清理：默认每小时执行一次。
     */
    @Scheduled(fixedRateString = "${codeatlas.workspace.cleanup-interval-ms:3600000}")
    public void scheduledCleanup() {
        log.debug("Running scheduled workspace cleanup...");
        cleanupDirectories(workspaceConfig.getRetentionHours());
    }

    /**
     * 清理超过保留期限的工作目录。
     *
     * @param retentionHours 保留小时数，0 表示立即清理所有非活跃目录
     */
    private void cleanupDirectories(int retentionHours) {
        cleanup(workspaceConfig.getScansDir(), retentionHours, "scans");
        cleanup(workspaceConfig.getUploadsDir(), retentionHours, "uploads");
    }

    private void cleanup(Path dir, int retentionHours, String label) {
        if (!Files.exists(dir)) return;

        File[] files = dir.toFile().listFiles();
        if (files == null || files.length == 0) return;

        Instant cutoff = Instant.now().minus(retentionHours, ChronoUnit.HOURS);
        int deletedCount = 0;
        long freedBytes = 0;

        for (File file : files) {
            if (!file.isDirectory()) continue;

            Path subDir = file.toPath();
            String dirName = subDir.getFileName().toString();

            if (isActiveDirectory(subDir)) {
                log.debug("Skipping active directory: {}", subDir);
                continue;
            }

            // 检查是否超过保留期限
            if (retentionHours > 0) {
                try {
                    BasicFileAttributes attrs = Files.readAttributes(subDir, BasicFileAttributes.class);
                    Instant lastModified = attrs.lastModifiedTime().toInstant();
                    if (lastModified.isAfter(cutoff)) {
                        log.debug("Skipping recent directory (within {}h): {}", retentionHours, subDir);
                        continue;
                    }
                } catch (IOException e) {
                    log.warn("Cannot read attributes for {}: {}", subDir, e.getMessage());
                }
            }

            // 删除目录
            long dirSize = getDirectorySize(subDir);
            try {
                Files.walk(subDir)
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                deletedCount++;
                freedBytes += dirSize;
                log.info("Cleaned up {}: {} MB freed", subDir, dirSize / (1024 * 1024));
            } catch (IOException e) {
                log.warn("Failed to delete {}: {}", subDir, e.getMessage());
            }
        }

        if (deletedCount > 0) {
            log.info("{} cleanup: deleted {} dirs, freed ~{} MB", label, deletedCount, freedBytes / (1024 * 1024));
        }
    }

    /**
     * 检查目录是否关联一个正在运行的扫描。
     * 目录命名约定: {projectId}-{scanId}
     */
    private boolean isActiveDirectory(Path dir) {
        String name = dir.getFileName().toString();
        int lastDash = name.lastIndexOf('-');
        if (lastDash <= 0) return false;

        try {
            long scanId = Long.parseLong(name.substring(lastDash + 1));
            ScanRecord scan = scanMapper.findById(scanId);
            return scan != null && "RUNNING".equals(scan.getStatus());
        } catch (NumberFormatException e) {
            return false; // 无法解析 scanId，保守处理：不跳过
        }
    }

    private long getDirectorySize(Path dir) {
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try { return Files.size(p); }
                        catch (IOException e) { return 0L; }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
