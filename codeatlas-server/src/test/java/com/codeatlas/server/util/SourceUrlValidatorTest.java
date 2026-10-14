package com.codeatlas.server.util;

import com.codeatlas.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SourceUrlValidator 单元测试（Git URL 安全校验 + 本地路径边界）")
class SourceUrlValidatorTest {

    private static final String EMPTY_URL_MESSAGE = "Git 仓库 URL 不能为空";
    private static final String URL_FORMAT_MESSAGE = "Git URL 格式不正确，示例：https://github.com/user/repo.git";
    private static final String INTERNAL_HOST_MESSAGE = "不允许访问本机或内网地址";
    private static final String EMPTY_PATH_MESSAGE = "本地源码路径不能为空";
    private static final String OUT_OF_ROOT_MESSAGE = "本地路径超出允许范围（仅支持工作区内的上传目录）";
    private static final String INVALID_PATH_MESSAGE = "本地源码路径无效或不存在";

    @TempDir
    Path tempDir;

    private static BusinessException expectGitUrlRejected(String url) {
        return assertThrows(BusinessException.class, () -> SourceUrlValidator.validateGitUrl(url));
    }

    // ========== validateGitUrl：空值 ==========

    @Test
    @DisplayName("null / 空串 / 纯空白 URL：拒绝并提示不能为空")
    void validateGitUrl_nullOrBlank_shouldReject() {
        for (String url : new String[] {null, "", "   ", "\t\n"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(EMPTY_URL_MESSAGE, ex.getMessage());
        }
    }

    // ========== validateGitUrl：格式 ==========

    @Test
    @DisplayName("缺少协议 / 非 http(s) 协议 / 前后带空白：拒绝并提示格式不正确")
    void validateGitUrl_badFormat_shouldReject() {
        for (String url : new String[] {
                "github.com/user/repo.git",
                "ftp://github.com/user/repo.git",
                "git@github.com:user/repo.git",
                " http://github.com/user/repo.git",
                "http://github.com/use r/repo.git"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(URL_FORMAT_MESSAGE, ex.getMessage());
        }
    }

    // ========== validateGitUrl：本机 / 内网主机 ==========

    @Test
    @DisplayName("localhost 与 *.localhost（含大小写变体）：拒绝为内网地址")
    void validateGitUrl_localhostVariants_shouldReject() {
        for (String url : new String[] {
                "http://localhost/repo.git",
                "https://localhost:8080/repo.git",
                "http://LOCALHOST/repo.git",
                "http://app.localhost/repo.git",
                "http://app.LOCALHOST/repo.git"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(INTERNAL_HOST_MESSAGE, ex.getMessage());
        }
    }

    @Test
    @DisplayName("回环地址（127.0.0.1 / [::1]）：拒绝为内网地址")
    void validateGitUrl_loopbackAddresses_shouldReject() {
        for (String url : new String[] {
                "http://127.0.0.1/repo.git",
                "http://127.0.0.1:9090/repo.git",
                "http://[::1]/repo.git"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(INTERNAL_HOST_MESSAGE, ex.getMessage());
        }
    }

    @Test
    @DisplayName("私网 / 链路本地 / 任意本地地址：全部拒绝")
    void validateGitUrl_privateAndReservedAddresses_shouldReject() {
        for (String url : new String[] {
                "http://192.168.1.1/repo.git",
                "http://10.0.0.1/repo.git",
                "http://169.254.1.1/repo.git",
                "http://0.0.0.0/repo.git"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(INTERNAL_HOST_MESSAGE, ex.getMessage());
        }
    }

    @Test
    @DisplayName("IPv6 唯一本地地址 fd00::/7 与链路本地 fe80::/10：拒绝")
    void validateGitUrl_ipv6LocalAddresses_shouldReject() {
        for (String url : new String[] {
                "http://[fd00::1]/repo.git",
                "http://[fc12:3456::1]/repo.git",
                "http://[fe80::1]/repo.git"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(INTERNAL_HOST_MESSAGE, ex.getMessage());
        }
    }

    @Test
    @DisplayName("无主机（http:///path）与畸形 IPv6（http://[::x）：拒绝为内网地址")
    void validateGitUrl_nullHostOrMalformedUri_shouldReject() {
        for (String url : new String[] {
                "http:///path/repo.git",
                "http://[::x"}) {
            BusinessException ex = expectGitUrlRejected(url);
            assertEquals(400, ex.getCode());
            assertEquals(INTERNAL_HOST_MESSAGE, ex.getMessage());
        }
    }

    // ========== validateGitUrl：合法放行 ==========

    @Test
    @DisplayName("公网主机：合法 URL 放行且不抛异常")
    void validateGitUrl_publicHosts_shouldPass() {
        SourceUrlValidator.validateGitUrl("https://8.8.8.8/repo.git");
        SourceUrlValidator.validateGitUrl("http://8.8.4.4:8080/group/repo.git");
        SourceUrlValidator.validateGitUrl("https://[2001:4860:4860::8888]/repo.git");
        SourceUrlValidator.validateGitUrl("http://172.217.160.78/user/repo.git");
    }

    @Test
    @DisplayName("172.32.x.x（私网 172.16-31 之外的公网段）放行，边界不误杀")
    void validateGitUrl_172PublicRange_shouldPass() {
        SourceUrlValidator.validateGitUrl("http://172.32.0.1/repo.git");
    }

    // ========== resolveLocalPathInside：空值 ==========

    @Test
    @DisplayName("null / 空串 / 纯空白路径：拒绝并提示不能为空")
    void resolveLocalPathInside_nullOrBlank_shouldReject() {
        for (String rawPath : new String[] {null, "", "   "}) {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> SourceUrlValidator.resolveLocalPathInside(tempDir, rawPath));
            assertEquals(400, ex.getCode());
            assertEquals(EMPTY_PATH_MESSAGE, ex.getMessage());
        }
    }

    // ========== resolveLocalPathInside：根目录之内 ==========

    @Test
    @DisplayName("工作区内真实路径：返回规范化真实路径")
    void resolveLocalPathInside_existingPathInsideRoot_shouldReturnRealPath() throws IOException {
        Path root = tempDir.resolve("workspace");
        Path file = root.resolve("upload").resolve("Project.java");
        Files.createDirectories(file.getParent());
        Files.write(file, "class Project {}".getBytes(StandardCharsets.UTF_8));

        Path result = SourceUrlValidator.resolveLocalPathInside(root, file.toString());

        assertEquals(file.toRealPath(), result);
        assertTrue(result.startsWith(root.toRealPath()));
    }

    @Test
    @DisplayName("路径恰为工作区根目录本身：合法放行")
    void resolveLocalPathInside_rootItself_shouldPass() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);

        Path result = SourceUrlValidator.resolveLocalPathInside(root, root.toString());

        assertEquals(root.toRealPath(), result);
    }

    // ========== resolveLocalPathInside：越界与无效 ==========

    @Test
    @DisplayName("工作区外的真实路径：拒绝并提示超出允许范围")
    void resolveLocalPathInside_pathOutsideRoot_shouldReject() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);
        Path outside = tempDir.resolve("secret.txt");
        Files.write(outside, "top secret".getBytes(StandardCharsets.UTF_8));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> SourceUrlValidator.resolveLocalPathInside(root, outside.toString()));
        assertEquals(400, ex.getCode());
        assertEquals(OUT_OF_ROOT_MESSAGE, ex.getMessage());
    }

    @Test
    @DisplayName("不存在的路径：拒绝并提示路径无效")
    void resolveLocalPathInside_nonexistentPath_shouldReject() throws IOException {
        Path root = tempDir.resolve("workspace");
        Files.createDirectories(root);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> SourceUrlValidator.resolveLocalPathInside(root, root.resolve("missing").toString()));
        assertEquals(400, ex.getCode());
        assertEquals(INVALID_PATH_MESSAGE, ex.getMessage());
    }

    @Test
    @DisplayName("工作区根目录本身不存在：拒绝并提示路径无效")
    void resolveLocalPathInside_nonexistentRoot_shouldReject() throws IOException {
        Path file = tempDir.resolve("existing.txt");
        Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> SourceUrlValidator.resolveLocalPathInside(tempDir.resolve("no-such-root"), file.toString()));
        assertEquals(400, ex.getCode());
        assertEquals(INVALID_PATH_MESSAGE, ex.getMessage());
    }
}
