package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.service.FileService;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.service.ScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class FileServiceImpl implements FileService {

    private static final Logger log = LoggerFactory.getLogger(FileServiceImpl.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(".zip", ".tar.gz");
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024; // 100MB

    private final ProjectService projectService;
    private final ScanService scanService;
    private final WorkspaceConfig workspaceConfig;

    public FileServiceImpl(ProjectService projectService, ScanService scanService,
                           WorkspaceConfig workspaceConfig) {
        this.projectService = projectService;
        this.scanService = scanService;
        this.workspaceConfig = workspaceConfig;
    }

    @Override
    public ProjectVO uploadAndScan(MultipartFile file, Long userId) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
        }

        String originalName = file.getOriginalFilename();
        if (originalName == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无法获取文件名");
        }

        // 文件类型白名单检查（扩展名）
        String lowerName = originalName.toLowerCase();
        boolean allowed = false;
        for (String ext : ALLOWED_EXTENSIONS) {
            if (lowerName.endsWith(ext)) {
                allowed = true;
                break;
            }
        }
        if (!allowed) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "不支持的文件类型，仅允许 .zip 和 .tar.gz");
        }

        // 魔数校验：确保文件内容与实际类型匹配
        if (lowerName.endsWith(".zip")) {
            byte[] magic = new byte[4];
            try (InputStream is = file.getInputStream()) {
                int read = is.read(magic);
                if (read < 4 || !isZipMagic(magic)) {
                    throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不是有效的 ZIP 格式");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "无法读取文件内容");
            }
        }

        // 文件大小检查
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "文件大小超过限制（最大 100MB）");
        }

        Path tempDir = null;
        try {
            String dirName = "upload-" + System.currentTimeMillis();
            tempDir = workspaceConfig.getUploadsDir().resolve(dirName);
            Files.createDirectories(tempDir);

            if (lowerName.endsWith(".zip")) {
                extractZip(file, tempDir);
            } else {
                // tar.gz 暂不支持，可后续扩展
                throw new BusinessException(ErrorCode.BAD_REQUEST, "暂不支持 .tar.gz，请使用 .zip 格式");
            }

            // 查找项目根目录（跳过一层包装目录）
            Path projectRoot = findProjectRoot(tempDir);

            // 提取项目名称
            String projectName = originalName;
            int dotIdx = projectName.lastIndexOf('.');
            if (dotIdx > 0) {
                projectName = projectName.substring(0, dotIdx);
            }
            // 去掉 .tar 后缀（如果是 .tar.gz）
            if (projectName.endsWith(".tar")) {
                projectName = projectName.substring(0, projectName.length() - 4);
            }

            // 创建项目
            CreateProjectRequest request = new CreateProjectRequest();
            request.setName(projectName);
            request.setDescription("从上传文件导入: " + originalName);
            request.setSourceType("ZIP_UPLOAD");
            request.setSourceUrl(projectRoot.toAbsolutePath().toString());

            ProjectVO project = projectService.createProject(request, userId);

            // 触发扫描
            scanService.triggerScan(project.getId(), userId);

            log.info("File upload processed: {} → projectId={}", originalName, project.getId());
            return project;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("File upload failed: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件处理失败: " + e.getMessage());
        }
    }

    private void extractZip(MultipartFile file, Path destDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(file.getInputStream()))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = destDir.resolve(entry.getName());
                // 防 ZIP 路径穿越攻击
                if (!entryPath.normalize().startsWith(destDir.normalize())) {
                    log.warn("Zip slip attack blocked: {}", entry.getName());
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    private boolean isZipMagic(byte[] magic) {
        return magic.length >= 4
                && magic[0] == 0x50 && magic[1] == 0x4B; // PK signature
    }

    /**
     * 如果解压后只有一个目录，则认为它是项目根目录。
     */
    private Path findProjectRoot(Path tempDir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir, p -> Files.isDirectory(p))) {
            Path singleDir = null;
            int dirCount = 0;
            for (Path path : stream) {
                dirCount++;
                singleDir = path;
            }
            if (dirCount == 1 && singleDir != null) {
                return singleDir;
            }
        }
        return tempDir;
    }
}
