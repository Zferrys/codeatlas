package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.service.impl.FileServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileServiceImpl 单元测试")
class FileServiceImplTest {

    private static final Long USER_ID = 7L;
    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;

    @TempDir
    Path tempDir;

    @Mock
    private ProjectService projectService;
    @Mock
    private ScanService scanService;
    @Mock
    private WorkspaceConfig workspaceConfig;
    @Mock
    private MultipartFile file;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileServiceImpl(projectService, scanService, workspaceConfig);
    }

    /** 用真实 ZipOutputStream 构造 zip 字节内容 */
    private byte[] zipBytes(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 每次调用返回全新的输入流（魔数校验与解压各读一次），不含 size 桩 */
    private void stubFileContent(String filename, byte[] bytes) throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(filename);
        when(file.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(bytes));
    }

    /** 走到大小检查之后的完整流程所需：内容 + 大小桩 */
    private void stubZipFile(String filename, byte[] bytes) throws IOException {
        stubFileContent(filename, bytes);
        when(file.getSize()).thenReturn((long) bytes.length);
    }

    private ProjectVO projectVO(Long id) {
        ProjectVO vo = new ProjectVO();
        vo.setId(id);
        return vo;
    }

    @Test
    @DisplayName("空文件：抛 400 上传文件不能为空")
    void uploadAndScan_emptyFile_shouldThrow400() {
        when(file.isEmpty()).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("上传文件不能为空", ex.getMessage());
        verifyNoInteractions(projectService, scanService);
    }

    @Test
    @DisplayName("文件名为 null：抛 400 无法获取文件名")
    void uploadAndScan_nullFilename_shouldThrow400() throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("无法获取文件名", ex.getMessage());
        verify(file, never()).getInputStream();
    }

    @Test
    @DisplayName("非白名单扩展名（.jar）：抛 400，不读取内容")
    void uploadAndScan_unsupportedExtension_shouldThrow400() throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("app.jar");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("不支持的文件类型，仅允许 .zip 和 .tar.gz", ex.getMessage());
        verify(file, never()).getInputStream();
    }

    @Test
    @DisplayName("zip 魔数不足 4 字节：抛 400 文件内容不是有效的 ZIP 格式")
    void uploadAndScan_shortZipContent_shouldReject() throws IOException {
        stubFileContent("a.zip", new byte[]{0x50});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("文件内容不是有效的 ZIP 格式", ex.getMessage());
        verifyNoInteractions(projectService);
    }

    @Test
    @DisplayName("zip 魔数错误（非 PK）：抛 400")
    void uploadAndScan_wrongMagic_shouldReject() throws IOException {
        stubFileContent("a.zip", new byte[]{'A', 'B', 'C', 'D'});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals("文件内容不是有效的 ZIP 格式", ex.getMessage());
    }

    @Test
    @DisplayName("zip 魔数首字节 P 但第二字节非 K：抛 400")
    void uploadAndScan_pkWrongSecondByte_shouldReject() throws IOException {
        stubFileContent("a.zip", new byte[]{0x50, 0x58, 0x03, 0x04});

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals("文件内容不是有效的 ZIP 格式", ex.getMessage());
    }

    @Test
    @DisplayName("文件名只有扩展名部分（.zip）：dotIdx=0 不截名，项目名保持原名")
    void uploadAndScan_nameWithoutBase_shouldKeepName() throws IOException {
        byte[] zip = zipBytes(Map.of("proj/x.java", "class X {}".getBytes()));
        stubZipFile(".zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(projectVO(5L));

        fileService.uploadAndScan(file, USER_ID);

        ArgumentCaptor<CreateProjectRequest> captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).createProject(captor.capture(), eq(USER_ID));
        assertEquals(".zip", captor.getValue().getName());
    }

    @Test
    @DisplayName("zip 解压后总大小超过 500MB：抛 400 解压大小限制（真实超限数据流）")
    void uploadAndScan_uncompressedSizeExceeded_shouldThrow400() throws IOException {
        // 零字节高度可压缩：压缩包体约 0.5MB，解压约 500MB，命中 500MB 累计字节守卫
        long uncompressedSize = 500L * 1024 * 1024 + 8192;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("big.bin"));
            byte[] zeros = new byte[8192];
            long remaining = uncompressedSize;
            while (remaining > 0) {
                int chunk = (int) Math.min(zeros.length, remaining);
                zos.write(zeros, 0, chunk);
                remaining -= chunk;
            }
            zos.closeEntry();
        }
        byte[] zip = bos.toByteArray();
        stubZipFile("big.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("ZIP 解压后总大小超过限制（最大 500 MB）", ex.getMessage());
        verifyNoInteractions(projectService);
    }

    @Test
    @DisplayName("读取流抛 IOException：抛 400 无法读取文件内容")
    void uploadAndScan_streamReadFails_shouldThrow400() throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("a.zip");
        when(file.getInputStream()).thenThrow(new IOException("stream broken"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("无法读取文件内容", ex.getMessage());
    }

    @Test
    @DisplayName("文件大小超过 100MB：抛 400 且不解压")
    void uploadAndScan_oversizeFile_shouldThrow400() throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("big.zip");
        when(file.getInputStream())
                .thenAnswer(invocation -> new ByteArrayInputStream(new byte[]{0x50, 0x4B, 0x03, 0x04}));
        when(file.getSize()).thenReturn(MAX_FILE_SIZE + 1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("文件大小超过限制（最大 100MB）", ex.getMessage());
        verifyNoInteractions(projectService);
    }

    @Test
    @DisplayName("tar.gz 走白名单但在解压阶段拒绝：抛 400 暂不支持")
    void uploadAndScan_tarGz_shouldThrow400AtExtractStage() throws IOException {
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("archive.tar.gz");
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("暂不支持 .tar.gz，请使用 .zip 格式", ex.getMessage());
        verifyNoInteractions(projectService);
        verify(file, never()).getInputStream();
    }

    @Test
    @DisplayName("合法 zip（单一顶层目录）：项目根取该子目录，创建项目并触发扫描")
    void uploadAndScan_singleTopDir_shouldUseSubDirAsProjectRoot() throws IOException {
        byte[] zip = zipBytes(Map.of(
                "proj/pom.xml", "<project/>".getBytes(),
                "proj/src/A.java", "class A {}".getBytes()));
        stubZipFile("myproj.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        ProjectVO created = projectVO(123L);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(created);

        ProjectVO result = fileService.uploadAndScan(file, USER_ID);

        assertSame(created, result);
        ArgumentCaptor<CreateProjectRequest> captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).createProject(captor.capture(), eq(USER_ID));
        CreateProjectRequest request = captor.getValue();
        assertEquals("myproj", request.getName());
        assertEquals("从上传文件导入: myproj.zip", request.getDescription());
        assertEquals("ZIP_UPLOAD", request.getSourceType());
        // 项目根 = <uploadsDir>/upload-<ts>/proj（跳过单层包装目录）
        Path sourcePath = Path.of(request.getSourceUrl());
        assertEquals("proj", sourcePath.getFileName().toString());
        assertTrue(sourcePath.startsWith(tempDir), "实际路径: " + sourcePath);
        assertTrue(sourcePath.getParent().getFileName().toString().startsWith("upload-"));
        verify(scanService).triggerScan(123L, USER_ID);
    }

    @Test
    @DisplayName("合法 zip（根目录平铺文件，无子目录）：项目根取临时目录本身")
    void uploadAndScan_flatFiles_shouldUseTempDirAsProjectRoot() throws IOException {
        byte[] zip = zipBytes(Map.of(
                "readme.md", "hello".getBytes(),
                "Main.java", "class Main {}".getBytes()));
        stubZipFile("flat.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(projectVO(1L));

        fileService.uploadAndScan(file, USER_ID);

        ArgumentCaptor<CreateProjectRequest> captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).createProject(captor.capture(), eq(USER_ID));
        Path sourcePath = Path.of(captor.getValue().getSourceUrl());
        assertTrue(sourcePath.startsWith(tempDir), "实际路径: " + sourcePath);
        assertTrue(sourcePath.getFileName().toString().startsWith("upload-"));
    }

    @Test
    @DisplayName("合法 zip（多个顶层目录）：不视为单一包装目录，项目根取临时目录")
    void uploadAndScan_multipleTopDirs_shouldUseTempDirAsProjectRoot() throws IOException {
        byte[] zip = zipBytes(Map.of(
                "d1/A.java", "class A {}".getBytes(),
                "d2/B.java", "class B {}".getBytes()));
        stubZipFile("multi.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(projectVO(2L));

        fileService.uploadAndScan(file, USER_ID);

        ArgumentCaptor<CreateProjectRequest> captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).createProject(captor.capture(), eq(USER_ID));
        Path sourcePath = Path.of(captor.getValue().getSourceUrl());
        assertTrue(sourcePath.startsWith(tempDir), "实际路径: " + sourcePath);
        assertTrue(sourcePath.getFileName().toString().startsWith("upload-"));
    }

    @Test
    @DisplayName("zip 名以 .tar.zip 结尾：项目名剥离 .zip 与 .tar 双层后缀")
    void uploadAndScan_tarZipSuffix_shouldStripTarSuffix() throws IOException {
        byte[] zip = zipBytes(Map.of("proj/x.java", "class X {}".getBytes()));
        stubZipFile("bundle.tar.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(projectVO(3L));

        fileService.uploadAndScan(file, USER_ID);

        ArgumentCaptor<CreateProjectRequest> captor = ArgumentCaptor.forClass(CreateProjectRequest.class);
        verify(projectService).createProject(captor.capture(), eq(USER_ID));
        assertEquals("bundle", captor.getValue().getName());
    }

    @Test
    @DisplayName("zip 路径穿越条目：被拦截跳过，正常条目照常解压")
    void uploadAndScan_zipSlipEntry_shouldBeBlocked() throws IOException {
        String evilName = "evil-slip-" + System.nanoTime() + ".txt";
        byte[] zip = zipBytes(Map.of(
                "../" + evilName, "evil".getBytes(),
                "proj/Good.java", "class Good {}".getBytes()));
        stubZipFile("slip.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenReturn(projectVO(4L));

        fileService.uploadAndScan(file, USER_ID);

        // ../ 路径被归一化到临时目录外，必须没有被写出
        Path escaped = tempDir.getParent().resolve(evilName);
        assertFalse(Files.exists(escaped), "zip-slip 条目不应被写出: " + escaped);
        // 正常条目仍然解压成功，项目也创建了
        verify(projectService).createProject(any(CreateProjectRequest.class), eq(USER_ID));
    }

    @Test
    @DisplayName("zip 条目数超过 10000：抛 400 条目数限制")
    void uploadAndScan_tooManyEntries_shouldThrow400() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (int i = 0; i <= 10000; i++) {
                zos.putNextEntry(new ZipEntry("d" + i + "/"));
                zos.closeEntry();
            }
        }
        byte[] zip = bos.toByteArray();
        stubZipFile("bomb.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(400, ex.getCode());
        assertEquals("ZIP 条目数超过限制（最大 10000 个）", ex.getMessage());
        verifyNoInteractions(projectService);
    }

    @Test
    @DisplayName("创建项目抛异常：统一包装为 500 文件处理失败: 原因")
    void uploadAndScan_createProjectFails_shouldWrapAs500() throws IOException {
        byte[] zip = zipBytes(Map.of("proj/x.java", "class X {}".getBytes()));
        stubZipFile("ok.zip", zip);
        when(workspaceConfig.getUploadsDir()).thenReturn(tempDir);
        when(projectService.createProject(any(CreateProjectRequest.class), eq(USER_ID)))
                .thenThrow(new RuntimeException("db down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(500, ex.getCode());
        assertEquals("文件处理失败: db down", ex.getMessage());
    }

    @Test
    @DisplayName("临时目录创建失败（uploadsDir 指向普通文件）：包装为 500")
    void uploadAndScan_tempDirCreationFails_shouldWrapAs500() throws IOException {
        byte[] zip = zipBytes(Map.of("proj/x.java", "class X {}".getBytes()));
        stubZipFile("ok.zip", zip);
        Path blockingFile = Files.createFile(tempDir.resolve("blocking-file"));
        when(workspaceConfig.getUploadsDir()).thenReturn(blockingFile);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fileService.uploadAndScan(file, USER_ID));

        assertEquals(500, ex.getCode());
        assertTrue(ex.getMessage().startsWith("文件处理失败: "), "实际消息: " + ex.getMessage());
    }
}
