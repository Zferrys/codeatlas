package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("WorkspaceConfig 单元测试（默认值 + 目录初始化 + 失败语义）")
class WorkspaceConfigTest {

    @Test
    @DisplayName("默认值：目录 ./codeatlas-workspace、保留 24 小时、1 小时清理间隔，初始化前路径为 null")
    void defaults_areExact() {
        WorkspaceConfig config = new WorkspaceConfig();

        assertEquals("./codeatlas-workspace", config.getDir());
        assertEquals(24, config.getRetentionHours());
        assertEquals(3_600_000L, config.getCleanupIntervalMs());
        assertNull(config.getScansDir());
        assertNull(config.getUploadsDir());
    }

    @Test
    @DisplayName("setter 覆盖后可读回（配置绑定路径）")
    void setters_updateValues() {
        WorkspaceConfig config = new WorkspaceConfig();

        config.setDir("/tmp/custom-ws");
        config.setRetentionHours(48);
        config.setCleanupIntervalMs(120_000L);

        assertEquals("/tmp/custom-ws", config.getDir());
        assertEquals(48, config.getRetentionHours());
        assertEquals(120_000L, config.getCleanupIntervalMs());
    }

    @Test
    @DisplayName("init：创建 scans/uploads 子目录，基目录为绝对规范化路径，重复 init 幂等")
    void init_createsScansAndUploadsDirectories(@TempDir Path tempDir) throws IOException {
        WorkspaceConfig config = new WorkspaceConfig();
        config.setDir(tempDir.resolve("ws-home").toString());

        config.init();

        Path base = Paths.get(config.getDir()).toAbsolutePath().normalize();
        assertEquals(base, config.getBaseDir());
        assertEquals(base.resolve("scans"), config.getScansDir());
        assertEquals(base.resolve("uploads"), config.getUploadsDir());
        assertTrue(Files.isDirectory(config.getScansDir()));
        assertTrue(Files.isDirectory(config.getUploadsDir()));

        config.init();

        assertTrue(Files.isDirectory(config.getScansDir()));
        assertTrue(Files.isDirectory(config.getUploadsDir()));
    }

    @Test
    @DisplayName("init：基目录已存在时也可正常初始化")
    void init_existingBaseDirectory_succeeds(@TempDir Path tempDir) throws IOException {
        Path base = tempDir.resolve("already-there");
        Files.createDirectories(base);
        WorkspaceConfig config = new WorkspaceConfig();
        config.setDir(base.toString());

        config.init();

        assertTrue(Files.isDirectory(config.getScansDir()));
        assertTrue(Files.isDirectory(config.getUploadsDir()));
    }

    @Test
    @DisplayName("init：目录无法创建时抛出 RuntimeException（精确消息 + IOException 原因）")
    void init_impossiblePath_throwsRuntimeException(@TempDir Path tempDir) throws IOException {
        Path blocker = tempDir.resolve("blocker.txt");
        Files.write(blocker, "x".getBytes());
        WorkspaceConfig config = new WorkspaceConfig();
        config.setDir(blocker.resolve("child").toString());
        Path base = Paths.get(config.getDir()).toAbsolutePath().normalize();

        RuntimeException ex = assertThrows(RuntimeException.class, config::init);

        assertEquals("Cannot create workspace directories at " + base, ex.getMessage());
        assertInstanceOf(IOException.class, ex.getCause());
        // 路径字段在任何分支都已赋值（供调用方诊断）
        assertEquals(base.resolve("scans"), config.getScansDir());
        assertEquals(base.resolve("uploads"), config.getUploadsDir());
    }

    @Test
    @DisplayName("getBaseDir：基于 dir 计算绝对规范化路径（不依赖 init）")
    void getBaseDir_computesAbsoluteNormalizedPath() {
        WorkspaceConfig config = new WorkspaceConfig();
        config.setDir("./some/../workspace-dir");

        assertEquals(Paths.get("./some/../workspace-dir").toAbsolutePath().normalize(), config.getBaseDir());
    }
}
