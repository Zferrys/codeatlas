package com.codeatlas.server.service;

import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service
public class CleanupService {

    private static final Logger log = LoggerFactory.getLogger(CleanupService.class);

    private final WorkspaceConfig workspaceConfig;
    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final AlertService alertService;

    private LocalDateTime lastCleanupTime;
    private long lastCleanupFreedBytes;
    private int lastCleanupFileCount;

    public CleanupService(WorkspaceConfig workspaceConfig, ProjectMapper projectMapper,
                          ScanMapper scanMapper, AlertService alertService) {
        this.workspaceConfig = workspaceConfig;
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.alertService = alertService;
    }

    @Scheduled(fixedRate = 1_800_000)
    public void scheduledCleanup() {
        log.info("Scheduled cleanup triggered");
        checkDiskSpace();
        checkMemory();
        cleanOrphanDirectories();
        cleanOldUploads();
        checkInactiveProjects();
    }

    public void checkDiskSpace() {
        try {
            Path baseDir = workspaceConfig.getBaseDir();
            File disk = baseDir.toFile();
            long totalSpace = disk.getTotalSpace();
            long freeSpace = disk.getFreeSpace();
            long usableSpace = disk.getUsableSpace();

            double freePercent = (double) usableSpace / totalSpace * 100;
            long freeGb = usableSpace / (1024 * 1024 * 1024);
            long totalGb = totalSpace / (1024 * 1024 * 1024);

            long workspaceSize = calculateDirSize(baseDir);
            long workspaceMb = workspaceSize / (1024 * 1024);

            log.info("Disk status: free={}% ({}GB/{}GB), workspace={}MB",
                    Math.round(freePercent), freeGb, totalGb, workspaceMb);

            if (freePercent < 20) {
                String alert = String.format("磁盘空间不足: 可用 %.0f%% (%dGB/%dGB), 工作空间占用 %dMB",
                        freePercent, freeGb, totalGb, workspaceMb);
                log.warn(alert);
                try {
                    alertService.sendAlert("磁盘空间告警", alert);
                } catch (Exception e) {
                    log.warn("Failed to send disk alert: {}", e.getMessage());
                }
                cleanOrphanDirectories();
                if (freePercent < 10) {
                    cleanOldUploads();
                }
            }
        } catch (Exception e) {
            log.warn("Disk check failed: {}", e.getMessage());
        }
    }

    public void checkMemory() {
        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = total - free;
        double heapRatio = (double) used / max;
        long usedMb = used / 1024 / 1024;
        long maxMb = max / 1024 / 1024;

        if (heapRatio > 0.85) {
            log.warn("JVM heap high: {}% ({}MB/{}MB)", (int)(heapRatio * 100), usedMb, maxMb);
            try {
                alertService.sendAlert("JVM 内存告警",
                        String.format("堆内存使用率 %.0f%% (%dMB/%dMB)", heapRatio * 100, usedMb, maxMb));
            } catch (Exception e) {
                log.warn("Failed to send memory alert: {}", e.getMessage());
            }
        }

        long systemFree = rt.freeMemory() + (rt.maxMemory() - rt.totalMemory());
        long systemFreeMb = systemFree / 1024 / 1024;
        if (systemFreeMb < 200) {
            log.warn("System memory low: {}MB free", systemFreeMb);
            try {
                alertService.sendAlert("系统内存不足",
                        String.format("可用物理内存仅 %dMB，建议清理不活跃项目", systemFreeMb));
            } catch (Exception e) {
                log.warn("Failed to send system memory alert: {}", e.getMessage());
            }
        }
    }

    public void cleanOrphanDirectories() {
        Path scansDir = workspaceConfig.getScansDir();
        if (scansDir == null || !Files.exists(scansDir)) {
            return;
        }

        int count = 0;
        long freedBytes = 0;
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);

        File[] dirs = scansDir.toFile().listFiles();
        if (dirs == null) return;

        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            try {
                BasicFileAttributes attrs = Files.readAttributes(dir.toPath(), BasicFileAttributes.class);
                Instant created = attrs.creationTime().toInstant();
                if (created.isBefore(cutoff)) {
                    String name = dir.getName();
                    if (name.matches("^\\d+-\\d+$")) {
                        long size = calculateDirSize(dir.toPath());
                        deleteRecursive(dir.toPath());
                        freedBytes += size;
                        count++;
                        log.info("Cleaned orphan scan dir: {} ({} bytes)", dir.getAbsolutePath(), size);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to clean orphan dir {}: {}", dir.getAbsolutePath(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Orphan cleanup: removed {} dirs, freed {}MB", count, freedBytes / (1024 * 1024));
        }
        lastCleanupTime = LocalDateTime.now();
        lastCleanupFreedBytes += freedBytes;
        lastCleanupFileCount += count;
    }

    public void cleanOldUploads() {
        Path uploadsDir = workspaceConfig.getUploadsDir();
        if (uploadsDir == null || !Files.exists(uploadsDir)) {
            return;
        }

        int count = 0;
        long freedBytes = 0;
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);

        File[] files = uploadsDir.toFile().listFiles();
        if (files == null) return;

        for (File f : files) {
            try {
                BasicFileAttributes attrs = Files.readAttributes(f.toPath(), BasicFileAttributes.class);
                Instant lastModified = attrs.lastModifiedTime().toInstant();
                if (lastModified.isBefore(cutoff)) {
                    long size = f.isDirectory() ? calculateDirSize(f.toPath()) : f.length();
                    deleteRecursive(f.toPath());
                    freedBytes += size;
                    count++;
                    log.info("Cleaned old upload: {} ({} bytes, last accessed: {})",
                            f.getAbsolutePath(), size, lastModified);
                }
            } catch (Exception e) {
                log.warn("Failed to clean old upload {}: {}", f.getAbsolutePath(), e.getMessage());
            }
        }

        if (count > 0) {
            log.info("Old uploads cleanup: removed {} files, freed {}MB", count, freedBytes / (1024 * 1024));
        }
        lastCleanupTime = LocalDateTime.now();
        lastCleanupFreedBytes += freedBytes;
        lastCleanupFileCount += count;
    }

    public void checkInactiveProjects() {
        try {
            List<Project> projects = projectMapper.findAll();
            LocalDateTime cutoff = LocalDateTime.now().minusDays(30);

            List<String> inactiveProjects = new ArrayList<>();
            for (Project project : projects) {
                LocalDateTime accessed = project.getLastAccessedAt();
                if (accessed == null || accessed.isBefore(cutoff)) {
                    ScanRecord latest = scanMapper.findLatestByProjectId(project.getId());
                    LocalDateTime lastScanTime = latest != null ? latest.getCreatedAt() : null;
                    if (lastScanTime == null || lastScanTime.isBefore(cutoff)) {
                        inactiveProjects.add(String.format("Project[id=%d, name=%s, lastAccess=%s]",
                                project.getId(), project.getName(),
                                accessed != null ? accessed.toString() : "never"));
                    }
                }
            }

            if (!inactiveProjects.isEmpty()) {
                log.info("Found {} inactive projects (>30d no access): {}", inactiveProjects.size(), inactiveProjects);
                if (inactiveProjects.size() >= 5) {
                    try {
                        alertService.sendAlert("不活跃项目提醒",
                                String.format("有 %d 个项目超过 30 天未访问，建议清理以释放磁盘空间", inactiveProjects.size()));
                    } catch (Exception e) {
                        log.warn("Failed to send inactive project alert: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Inactive project check failed: {}", e.getMessage());
        }
    }

    public Map<String, Object> getCleanupStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        try {
            Path baseDir = workspaceConfig.getBaseDir();
            File disk = baseDir.toFile();
            long totalSpace = disk.getTotalSpace();
            long freeSpace = disk.getFreeSpace();
            status.put("diskTotalGb", Math.round(totalSpace * 100.0 / (1024 * 1024 * 1024)) / 100.0);
            status.put("diskFreeGb", Math.round(freeSpace * 100.0 / (1024 * 1024 * 1024)) / 100.0);
            status.put("diskFreePercent", (int) Math.round((double) freeSpace / totalSpace * 100));
            status.put("workspaceSizeMb", calculateDirSize(baseDir) / (1024 * 1024));
        } catch (Exception e) {
            status.put("diskError", e.getMessage());
        }

        Runtime rt = Runtime.getRuntime();
        status.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        status.put("heapTotalMb", rt.totalMemory() / (1024 * 1024));
        status.put("heapFreeMb", rt.freeMemory() / (1024 * 1024));
        status.put("heapUsedPercent", (int) Math.round((rt.totalMemory() - rt.freeMemory()) * 100.0 / rt.maxMemory()));

        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        long uptimeMinutes = uptimeMs / 60000;
        status.put("uptimeMinutes", uptimeMinutes);
        if (uptimeMinutes < 60) {
            status.put("uptimeFormatted", uptimeMinutes + "m");
        } else if (uptimeMinutes < 1440) {
            status.put("uptimeFormatted", (uptimeMinutes / 60) + "h " + (uptimeMinutes % 60) + "m");
        } else {
            status.put("uptimeFormatted", (uptimeMinutes / 1440) + "d " + ((uptimeMinutes % 1440) / 60) + "h");
        }
        status.put("startedAt", LocalDateTime.now()
                .minus(uptimeMs / 1000, ChronoUnit.SECONDS)
                .toString().replace("T", " "));

        status.put("lastCleanupTime", lastCleanupTime != null ? lastCleanupTime.toString() : "never");
        status.put("lastCleanupFreedMb", lastCleanupFreedBytes / (1024 * 1024));
        status.put("lastCleanupFileCount", lastCleanupFileCount);

        try {
            List<Project> projects = projectMapper.findAll();
            status.put("totalProjects", projects.size());
        } catch (Exception e) {
            status.put("totalProjects", "unknown");
        }

        return status;
    }

    private long calculateDirSize(Path dir) {
        if (dir == null || !Files.exists(dir)) return 0;
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
        } catch (Exception e) {
            return 0;
        }
    }

    private void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                File[] children = path.toFile().listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child.toPath());
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (Exception e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }
}
