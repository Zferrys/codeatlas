package com.codeatlas.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 统一工作目录配置。
 * 所有 Git 克隆、ZIP 解压等临时文件统一放到此目录下管理，
 * 不再使用系统临时目录，便于定期清理和磁盘监控。
 */
@Configuration
@ConfigurationProperties(prefix = "codeatlas.workspace")
public class WorkspaceConfig {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceConfig.class);

    private String dir = "./codeatlas-workspace";
    private int retentionHours = 24;
    private long cleanupIntervalMs = 3_600_000;

    private Path scansDir;
    private Path uploadsDir;

    @PostConstruct
    public void init() {
        Path base = Paths.get(dir).toAbsolutePath().normalize();
        scansDir = base.resolve("scans");
        uploadsDir = base.resolve("uploads");
        try {
            Files.createDirectories(scansDir);
            Files.createDirectories(uploadsDir);
            log.info("Workspace initialized: base={}, scans={}, uploads={}", base, scansDir, uploadsDir);
        } catch (IOException e) {
            log.error("Failed to create workspace directories: {}", e.getMessage());
            throw new RuntimeException("Cannot create workspace directories at " + base, e);
        }
    }

    public Path getScansDir() { return scansDir; }
    public Path getUploadsDir() { return uploadsDir; }
    public Path getBaseDir() { return Paths.get(dir).toAbsolutePath().normalize(); }

    public String getDir() { return dir; }
    public void setDir(String dir) { this.dir = dir; }
    public int getRetentionHours() { return retentionHours; }
    public void setRetentionHours(int retentionHours) { this.retentionHours = retentionHours; }
    public long getCleanupIntervalMs() { return cleanupIntervalMs; }
    public void setCleanupIntervalMs(long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
}
