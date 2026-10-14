package com.codeatlas.engine.git;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GitService — 仓库大小预估、本地克隆、目录清理与 CombinedTrustManager 信任链。
 * 克隆与 API 状态分支全部走本地 file:// 协议和本地 mock HTTPS 代理，不依赖外网。
 */
@DisplayName("GitService — 克隆/清理/信任链")
class GitServiceTest {

    private final GitService service = new GitService();

    /** 匹配心跳消息中的「已耗时 N 秒」 */
    private static final Pattern HEARTBEAT_ELAPSED = Pattern.compile("已耗时 (\\d+) 秒");

    /** 建一个含单个提交的本地真实仓库，返回 (仓库路径, 当前分支名) */
    private static Object[] createLocalRepo(Path repoDir) throws Exception {
        Files.createDirectories(repoDir);
        try (Git git = Git.init().setDirectory(repoDir.toFile()).call()) {
            Files.write(repoDir.resolve("README.md"), "hello codeatlas".getBytes(StandardCharsets.UTF_8));
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("init")
                    .setAuthor("tester", "tester@example.com")
                    .setCommitter("tester", "tester@example.com")
                    .call();
            return new Object[]{git.getRepository().getBranch()};
        }
    }

    // ==================== estimateRepoSize ====================

    @Test
    @DisplayName("非 GitHub URL：不发起任何请求直接返回 -1")
    void estimateRepoSize_nonGithubUrl_shouldReturnMinusOne() {
        assertEquals(-1L, service.estimateRepoSize("https://gitlab.com/foo/bar"));
        assertEquals(-1L, service.estimateRepoSize("D:/repos/local-project"));
        assertEquals(-1L, service.estimateRepoSize("not a url at all"));
    }

    @Test
    @DisplayName("GitHub URL 但仓库不存在：API 404 或网络不可达均返回 -1（不抛异常）")
    void estimateRepoSize_githubUrlMissingRepo_shouldReturnMinusOne() {
        assertEquals(-1L, service.estimateRepoSize(
                "https://github.com/codeatlas-nonexistent-owner-zzz/codeatlas-nonexistent-repo-zzz"));
        assertEquals(-1L, service.estimateRepoSize(
                "https://github.com/codeatlas-nonexistent-owner-zzz/codeatlas-nonexistent-repo-zzz.git"));
        assertEquals(-1L, service.estimateRepoSize(
                "https://github.com/codeatlas-nonexistent-owner-zzz/codeatlas-nonexistent-repo-zzz/tree/main"));
    }

    @Test
    @DisplayName("GitHub API 连接异常：走 IOException 分支返回 -1（通过指向已关闭端口的代理强制复现）")
    void estimateRepoSize_connectionFailure_shouldReturnMinusOne() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", String.valueOf(closedPort));
        try {
            assertEquals(-1L, service.estimateRepoSize("https://github.com/foo/bar"));
        } finally {
            System.clearProperty("https.proxyHost");
            System.clearProperty("https.proxyPort");
        }
    }

    @Test
    @DisplayName("本地 mock HTTPS 代理：200 有 size/200 无 size/404/403 四种响应逐分支断言（不依赖外网）")
    void estimateRepoSize_allStatusBranches_withMockProxy(@TempDir Path tempDir) throws Exception {
        // 先触发 configureSslForWindows 置位静态标记，避免其稍后覆盖下面自定义的信任策略
        assertEquals(-1L, service.estimateRepoSize("D:/repos/local"));

        SSLSocketFactory savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier savedVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try (MockGitHubHttpsProxy proxy = new MockGitHubHttpsProxy(tempDir)) {
            HttpsURLConnection.setDefaultSSLSocketFactory(trustAllContext().getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", String.valueOf(proxy.port()));
            try {
                assertEquals(2048L * 1024 * 4,
                        service.estimateRepoSize("https://github.com/test200/sized"),
                        "200 且含 size 字段：按 size(KB)*1024*4 估算");
                assertEquals(2048L * 1024 * 4,
                        service.estimateRepoSize("https://github.com/test200/sized.git"),
                        ".git 后缀被正则剥离后 owner/repo 解析一致");
                assertEquals(2048L * 1024 * 4,
                        service.estimateRepoSize("https://github.com/test200/sized/tree/main"),
                        "带子路径的 URL 也能提取 owner/repo");
                assertEquals(-1L, service.estimateRepoSize("https://github.com/test200/nosize"),
                        "200 但无 size 字段：落到方法末尾返回 -1");
                assertEquals(-1L, service.estimateRepoSize("https://github.com/test404/gone"),
                        "404：仓库不存在返回 -1，交由 clone 阶段报错");
                assertEquals(-1L, service.estimateRepoSize("https://github.com/test403/limited"),
                        "403：限流分支仅记日志后返回 -1");
            } finally {
                System.clearProperty("https.proxyHost");
                System.clearProperty("https.proxyPort");
                HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory);
                HttpsURLConnection.setDefaultHostnameVerifier(savedVerifier);
            }
        }
    }

    private static String formattedLogs(ListAppender<ILoggingEvent> appender) {
        StringBuilder logs = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            logs.append(event.getFormattedMessage()).append('\n');
        }
        return logs.toString();
    }

    @Test
    @DisplayName("GitHub API 日志：200 换算值精确到 MB；403 记限流日志；404 提前返回不产生限流日志")
    void estimateRepoSize_logging_shouldRecordExactMbAndRateLimit(@TempDir Path tempDir) throws Exception {
        assertEquals(-1L, service.estimateRepoSize("D:/repos/local")); // 先置位 SSL 静态标记

        Logger gitLogger = (Logger) LoggerFactory.getLogger(GitService.class);
        Level previousLevel = gitLogger.getLevel();
        gitLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        gitLogger.addAppender(appender);

        SSLSocketFactory savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        HostnameVerifier savedVerifier = HttpsURLConnection.getDefaultHostnameVerifier();
        try (MockGitHubHttpsProxy proxy = new MockGitHubHttpsProxy(tempDir)) {
            HttpsURLConnection.setDefaultSSLSocketFactory(trustAllContext().getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((host, session) -> true);
            System.setProperty("https.proxyHost", "127.0.0.1");
            System.setProperty("https.proxyPort", String.valueOf(proxy.port()));
            try {
                assertEquals(2048L * 1024 * 4, service.estimateRepoSize("https://github.com/test200/sized"));
                assertTrue(formattedLogs(appender).contains("size=2048KB, estimated working tree=8MB"),
                        "8MB 必须按字节精确除 1024*1024（除法变异或 1024*1024 改写会得到天文数字），实际日志: "
                                + formattedLogs(appender));

                appender.list.clear();
                assertEquals(-1L, service.estimateRepoSize("https://github.com/test404/gone"));
                assertFalse(formattedLogs(appender).contains("rate limited"),
                        "404 必须在其分支立即返回，不得落入 403 限流日志分支");

                appender.list.clear();
                assertEquals(-1L, service.estimateRepoSize("https://github.com/test403/limited"));
                assertTrue(formattedLogs(appender).contains("GitHub API rate limited for test403/limited"),
                        "403 必须且只能记限流日志（404/403 条件取反会提前返回或漏掉该日志），实际日志: "
                                + formattedLogs(appender));
            } finally {
                System.clearProperty("https.proxyHost");
                System.clearProperty("https.proxyPort");
                HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory);
                HttpsURLConnection.setDefaultHostnameVerifier(savedVerifier);
            }
        } finally {
            gitLogger.detachAppender(appender);
            gitLogger.setLevel(previousLevel);
        }
    }

    // ==================== configureSslForWindows（私有静态，经 estimateRepoSize 触发） ====================

    @Test
    @DisplayName("Windows-ROOT 证书库不可用：内层 catch 记日志后继续，整体 SSL 配置仍成功置位")
    void configureSslForWindows_windowsRootFailure_shouldFallbackToJdkDefaultOnly() throws Exception {
        boolean savedFlag = sslConfiguredFlag();
        setSslConfiguredFlag(false); // 重置静态标记，强制重新进入 configureSslForWindows
        try {
            try (MockedStatic<KeyStore> keyStoreMock = mockStatic(KeyStore.class, CALLS_REAL_METHODS)) {
                // 只替换 Windows-ROOT 这一个入口，JDK 默认信任库初始化（内部也会走 KeyStore.getInstance）保持真实行为
                keyStoreMock.when(() -> KeyStore.getInstance("Windows-ROOT"))
                        .thenThrow(new KeyStoreException("Windows-ROOT not available"));
                // 非 GitHub URL 不发起网络请求，仅触发 configureSslForWindows 后返回 -1
                assertEquals(-1L, service.estimateRepoSize("D:/repos/local"));
            }
            assertTrue(sslConfiguredFlag(),
                    "Windows-ROOT 失败只记日志（内层 catch），不应阻断 JDK 默认证书库配置完成");
        } finally {
            setSslConfiguredFlag(savedFlag);
        }
    }

    @Test
    @DisplayName("SSLContext 初始化失败：外层 catch 兜住异常，静态标记保持 false 允许下次重试")
    void configureSslForWindows_sslContextFailure_shouldStayUnconfigured() throws Exception {
        boolean savedFlag = sslConfiguredFlag();
        setSslConfiguredFlag(false);
        try {
            try (MockedStatic<SSLContext> sslContextMock = mockStatic(SSLContext.class, CALLS_REAL_METHODS)) {
                sslContextMock.when(() -> SSLContext.getInstance("TLS"))
                        .thenThrow(new NoSuchAlgorithmException("TLS unavailable"));
                assertEquals(-1L, service.estimateRepoSize("D:/repos/local"));
            }
            assertFalse(sslConfiguredFlag(),
                    "SSLContext 初始化失败被外层 catch 兜住，标记必须保持 false 以便下次调用重试");
        } finally {
            setSslConfiguredFlag(savedFlag);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<X509TrustManager> combinedTrustManagerDelegates(TrustManager combined) throws Exception {
        Field field = combined.getClass().getDeclaredField("delegates");
        field.setAccessible(true);
        return (List<X509TrustManager>) field.get(combined);
    }

    @Test
    @DisplayName("configureSslForWindows 全链路：Windows-ROOT 加载/双 TMF init/过滤器/工厂安装逐调用断言")
    void configureSslForWindows_fullPath_shouldWireWindowsStoreAndInstallFactory() throws Exception {
        boolean savedFlag = sslConfiguredFlag();
        SSLSocketFactory savedFactory = HttpsURLConnection.getDefaultSSLSocketFactory();
        setSslConfiguredFlag(false);
        try {
            X509TrustManager defaultX509 = mock(X509TrustManager.class);
            X509TrustManager windowsX509 = mock(X509TrustManager.class);
            KeyStore windowsStore = mock(KeyStore.class);
            TrustManagerFactory defaultTmf = mock(TrustManagerFactory.class);
            TrustManagerFactory windowsTmf = mock(TrustManagerFactory.class);
            SSLContext sslContext = mock(SSLContext.class);
            SSLSocketFactory socketFactory = mock(SSLSocketFactory.class);
            when(defaultTmf.getTrustManagers()).thenReturn(new TrustManager[]{defaultX509});
            when(windowsTmf.getTrustManagers()).thenReturn(new TrustManager[]{windowsX509});
            when(sslContext.getSocketFactory()).thenReturn(socketFactory);

            try (MockedStatic<KeyStore> keyStoreMock = mockStatic(KeyStore.class, CALLS_REAL_METHODS);
                 MockedStatic<TrustManagerFactory> tmfMock =
                         mockStatic(TrustManagerFactory.class, CALLS_REAL_METHODS);
                 MockedStatic<SSLContext> sslContextMock =
                         mockStatic(SSLContext.class, CALLS_REAL_METHODS)) {
                keyStoreMock.when(() -> KeyStore.getInstance("Windows-ROOT")).thenReturn(windowsStore);
                tmfMock.when(() -> TrustManagerFactory.getInstance(nullable(String.class)))
                        .thenReturn(defaultTmf, windowsTmf);
                sslContextMock.when(() -> SSLContext.getInstance("TLS")).thenReturn(sslContext);

                assertEquals(-1L, service.estimateRepoSize("D:/repos/local"));
            }

            verify(windowsStore).load(null, null);
            verify(windowsTmf).init(windowsStore);
            ArgumentCaptor<TrustManager[]> trustCaptor = ArgumentCaptor.forClass(TrustManager[].class);
            verify(sslContext).init(isNull(), trustCaptor.capture(), isNull());
            assertEquals(1, trustCaptor.getValue().length);
            assertEquals(Arrays.asList(defaultX509, windowsX509),
                    combinedTrustManagerDelegates(trustCaptor.getValue()[0]),
                    "仅 X509TrustManager 进入组合链，JB 默认在前、Windows-ROOT 在后");
            assertSame(socketFactory, HttpsURLConnection.getDefaultSSLSocketFactory(),
                    "SSLContext 产出的工厂必须安装为 JVM 默认工厂");
            assertTrue(sslConfiguredFlag());
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(savedFactory);
            setSslConfiguredFlag(savedFlag);
        }
    }

    // ==================== cloneRepository ====================

    @Test
    @DisplayName("cloneRepository 入口先配置 SSL：重置标记后调用失败克隆，静态标记必须已被置位")
    void cloneRepository_shouldConfigureSslFirst(@TempDir Path tempDir) throws Exception {
        boolean savedFlag = sslConfiguredFlag();
        setSslConfiguredFlag(false);
        try {
            GitResult result = service.cloneRepository(
                    tempDir.resolve("no-such-repo").toUri().toString(), "master",
                    tempDir.resolve("cloned"), message -> { });

            assertFalse(result.isSuccess());
            assertTrue(sslConfiguredFlag(),
                    "cloneRepository 必须在克隆前调用 configureSslForWindows（入口第一步）");
        } finally {
            setSslConfiguredFlag(savedFlag);
        }
    }

    /**
     * 伪造 JGit 克隆瞬时成功。关键：Mockito 静态 mock 只在创建线程内生效，而生产代码的克隆体
     * 跑在 CompletableFuture.supplyAsync 的 commonPool worker 上——因此必须同时把 supplyAsync
     * （该静态调用发生在调用线程）替换为同步执行，让 supplier 回到测试线程运行，两个 mock 才能一起命中。
     */
    private static CloneMocks mockSynchronousClone(Git git) throws Exception {
        MockedStatic<Git> gitMock = mockStatic(Git.class, CALLS_REAL_METHODS);
        CloneCommand command = mock(CloneCommand.class, RETURNS_SELF);
        when(command.call()).thenReturn(git);
        gitMock.when(Git::cloneRepository).thenReturn(command);

        MockedStatic<CompletableFuture> futureMock =
                mockStatic(CompletableFuture.class, CALLS_REAL_METHODS);
        futureMock.when(() -> CompletableFuture.supplyAsync(nullable(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> supplier = invocation.getArgument(0);
                    return CompletableFuture.completedFuture(supplier.get());
                });
        return new CloneMocks(gitMock, futureMock);
    }

    /** 成对持有克隆相关静态 mock，按逆序关闭 */
    private static final class CloneMocks implements AutoCloseable {

        private final MockedStatic<Git> gitMock;
        private final MockedStatic<CompletableFuture> futureMock;

        CloneMocks(MockedStatic<Git> gitMock, MockedStatic<CompletableFuture> futureMock) {
            this.gitMock = gitMock;
            this.futureMock = futureMock;
        }

        @Override
        public void close() {
            futureMock.close();
            gitMock.close();
        }
    }

    private static Git gitWithHead(String branch, String commitHash) throws Exception {
        Ref headRef = mock(Ref.class);
        when(headRef.getObjectId()).thenReturn(ObjectId.fromString(commitHash));
        RefDatabase refDb = mock(RefDatabase.class);
        when(refDb.findRef("HEAD")).thenReturn(headRef);
        Repository repository = mock(Repository.class);
        when(repository.getRefDatabase()).thenReturn(refDb);
        when(repository.getBranch()).thenReturn(branch);
        Git git = mock(Git.class);
        when(git.getRepository()).thenReturn(repository);
        return git;
    }

    @Test
    @DisplayName("克隆后目录大小恰好等于 500MB 上限：> 判定为 false 不拒绝，返回成功（边界精确驱动）")
    void cloneRepository_exactSizeLimit_shouldSucceed(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("exact-target");
        Files.createDirectories(target);
        try (RandomAccessFile sparse = new RandomAccessFile(target.resolve("big.bin").toFile(), "rw")) {
            sparse.setLength(500L * 1024 * 1024); // 恰好 MAX_REPO_SIZE_BYTES，毫秒级稀疏分配
        }
        assertEquals(524288000L, Files.size(target.resolve("big.bin")));

        Git git = gitWithHead("main", "0123456789abcdef0123456789abcdef01234567");
        GitResult result;
        try (CloneMocks ignored = mockSynchronousClone(git)) {
            result = service.cloneRepository("https://example.com/repo.git", "main", target, message -> { });
        }

        assertTrue(result.isSuccess(),
                "sizeBytes == 上限时 > 判定必须为 false（>= 变异会误拒），实际消息: " + result.getMessage());
        assertEquals("clone ok", result.getMessage());
        assertEquals("main", result.getBranch());
        assertEquals("0123456789abcdef0123456789abcdef01234567", result.getCommitHash());
    }

    @Test
    @DisplayName("克隆后目录超限拒绝消息：按字节精确换算 550 MB / 上限 500 MB")
    void cloneRepository_oversizeMessage_shouldContainExactMb(@TempDir Path tempDir) throws Exception {
        Path target = tempDir.resolve("huge-target");
        Files.createDirectories(target);
        try (RandomAccessFile sparse = new RandomAccessFile(target.resolve("big.bin").toFile(), "rw")) {
            sparse.setLength(550L * 1024 * 1024);
        }

        Git git = gitWithHead("main", "0123456789abcdef0123456789abcdef01234567");
        GitResult result;
        try (CloneMocks ignored = mockSynchronousClone(git)) {
            result = service.cloneRepository("https://example.com/repo.git", "main", target, message -> { });
        }

        assertFalse(result.isSuccess());
        assertEquals("仓库过大: 550 MB (上限 500 MB)，建议拆分项目或使用子模块", result.getMessage(),
                "拒绝消息必须按字节精确换算 MB 数（除法变异会放大为天文数字）");
    }

    @Test
    @DisplayName("克隆超时：future.get 抛 TimeoutException 时返回超时失败结果并记录分钟数日志（毫秒级驱动，不等 10 分钟）")
    @SuppressWarnings("unchecked")
    void cloneRepository_timeout_shouldReturnTimeoutResultWithLog(@TempDir Path tempDir) throws Exception {
        Logger gitLogger = (Logger) LoggerFactory.getLogger(GitService.class);
        Level previousLevel = gitLogger.getLevel();
        gitLogger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        gitLogger.addAppender(appender);

        Path target = tempDir.resolve("timeout-target");
        GitResult result;
        try (MockedStatic<CompletableFuture> futureMock =
                     mockStatic(CompletableFuture.class, CALLS_REAL_METHODS)) {
            // supplyAsync 返回一个 get(timeout) 必定超时的 future：直接驱动外层 TimeoutException 分支
            CompletableFuture<GitResult> timedOut = mock(CompletableFuture.class);
            when(timedOut.get(anyLong(), any(TimeUnit.class))).thenThrow(new TimeoutException());
            futureMock.when(() -> CompletableFuture.supplyAsync(nullable(Supplier.class)))
                    .thenReturn((CompletableFuture) timedOut);

            result = service.cloneRepository("https://example.com/slow.git", "main", target, message -> { });
        } finally {
            gitLogger.detachAppender(appender);
            gitLogger.setLevel(previousLevel);
        }

        assertFalse(result.isSuccess(), "超时分支必须返回失败结果");
        assertNull(result.getLocalPath());
        assertNull(result.getCommitHash());
        assertEquals("main", result.getBranch(), "超时失败结果原样保留请求的分支名");
        assertTrue(result.getMessage().contains("仓库克隆超时 (超过 10 分钟)"),
                "超时消息必须携带分钟数与建议，实际: " + result.getMessage());
        assertTrue(formattedLogs(appender).contains("Clone timeout for https://example.com/slow.git after 10 minutes"),
                "超时 catch 必须记录含 URL 与分钟数的错误日志，实际日志: " + formattedLogs(appender));
    }

    // ==================== estimateRepoSize 请求装配（mockConstruction 驱动） ====================

    /** 按固定块大小返回数据的输入流：保证 InputStreamReader 每次 read(char[1024]) 得到确定长度的块 */
    private static final class ChunkedInputStream extends InputStream {

        private final byte[] data;
        private final int chunk;
        private int pos;

        ChunkedInputStream(byte[] data, int chunk) {
            this.data = data;
            this.chunk = chunk;
        }

        @Override
        public int read() {
            return pos < data.length ? data[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(Math.min(chunk, len), data.length - pos);
            System.arraycopy(data, pos, b, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    @DisplayName("GitHub API 请求装配：连接/读取超时 8000ms 与双请求头逐调用断言；读取在 8192 边界精确截停")
    void estimateRepoSize_shouldSetTimeoutsHeaders_andStopExactlyAt8192Boundary() throws Exception {
        assertEquals(-1L, service.estimateRepoSize("D:/repos/local")); // 先完成 SSL 配置

        HttpURLConnection conn = mock(HttpURLConnection.class);
        when(conn.getResponseCode()).thenReturn(200);
        // 8192 字节填充 + 尾部 size 字段：恰好 1024*8 处累计长度命中 > 8192 判定
        byte[] padding = new byte[8192];
        Arrays.fill(padding, (byte) 'a');
        byte[] tail = "\"size\":2048}".getBytes(StandardCharsets.UTF_8);
        byte[] payload = Arrays.copyOf(padding, padding.length + tail.length);
        System.arraycopy(tail, 0, payload, padding.length, tail.length);
        when(conn.getInputStream()).thenReturn(new ChunkedInputStream(payload, 1024));

        long estimated;
        try (MockedConstruction<URL> urlMock = mockConstruction(URL.class,
                (url, context) -> when(url.openConnection()).thenReturn(conn))) {
            estimated = service.estimateRepoSize("https://github.com/foo/bar");
        }

        assertEquals(2048L * 1024 * 4, estimated,
                "8192 字节后仍有数据：必须继续读取到 > 8192 才截停，size 字段在尾部时必须能取到");
        verify(conn).setConnectTimeout(8000);
        verify(conn).setReadTimeout(8000);
        verify(conn).setRequestProperty("Accept", "application/vnd.github.v3+json");
        verify(conn).setRequestProperty("User-Agent", "CodeAtlas/1.0");
    }

    // ==================== cloneRepository（mock 前述） ====================

    @Test
    @DisplayName("本地仓库克隆成功：返回真实 commit hash/分支/路径，目标目录含检出文件")
    void cloneRepository_localRepo_shouldSucceed(@TempDir Path tempDir) throws Exception {
        Path sourceRepo = tempDir.resolve("source");
        String branch = (String) createLocalRepo(sourceRepo)[0];
        Path target = tempDir.resolve("cloned");

        GitResult result = service.cloneRepository(
                sourceRepo.toUri().toString(), branch, target, message -> { });

        assertTrue(result.isSuccess(), "错误信息: " + result.getMessage());
        assertEquals(target.toAbsolutePath().toString(), result.getLocalPath());
        assertEquals(branch, result.getBranch());
        assertEquals(40, result.getCommitHash().length(), "commit hash 应为完整 40 位 SHA-1");
        assertEquals("clone ok", result.getMessage());
        assertTrue(Files.exists(target.resolve("README.md")), "克隆目录必须包含检出的文件");
    }

    @Test
    @DisplayName("源仓库不存在：捕获 GitAPIException 返回失败结果而非抛异常，目标目录已被创建")
    void cloneRepository_missingSource_shouldFailGracefully(@TempDir Path tempDir) throws Exception {
        Path missingSource = tempDir.resolve("no-such-repo");
        Path target = tempDir.resolve("cloned-fail");

        GitResult result = service.cloneRepository(
                missingSource.toUri().toString(), "master", target, message -> { });

        assertFalse(result.isSuccess());
        assertNull(result.getLocalPath());
        assertNull(result.getCommitHash());
        assertEquals("master", result.getBranch(), "失败结果原样保留请求的分支名");
        assertNotNull(result.getMessage());
        assertTrue(Files.isDirectory(target), "cloneRepository 应已创建目标目录");
    }

    @Test
    @DisplayName("克隆目标为非空目录：异常逸出 Future 后被外层 catch 捕获，返回失败结果")
    void cloneRepository_nonEmptyTargetDir_shouldFailGracefully(@TempDir Path tempDir) throws Exception {
        Path sourceRepo = tempDir.resolve("source");
        String branch = (String) createLocalRepo(sourceRepo)[0];
        Path target = tempDir.resolve("non-empty-target");
        Files.createDirectories(target);
        Files.write(target.resolve("keep.txt"), "occupied".getBytes(StandardCharsets.UTF_8));

        GitResult result = service.cloneRepository(
                sourceRepo.toUri().toString(), branch, target, message -> { });

        assertFalse(result.isSuccess(), "非空目录无法作为克隆目标");
        assertNotNull(result.getMessage());
    }

    @Test
    @DisplayName("远端阻塞超过心跳周期：10 秒后进度回调收到心跳消息，连接断开后返回失败结果")
    void cloneRepository_slowRemote_shouldEmitHeartbeat(@TempDir Path tempDir) throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (requestCount.getAndIncrement() == 0) {
                try {
                    Thread.sleep(12_000); // 首请求悬挂 12 秒，保证跨过 10 秒心跳周期
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            exchange.close(); // 不发送响应体直接断开，让 JGit 尽快失败
        });
        server.start();
        try {
            List<String> progress = new CopyOnWriteArrayList<>();
            AtomicBoolean heartbeatOnDaemonThread = new AtomicBoolean(false);
            AtomicReference<Long> heartbeatElapsedSeconds = new AtomicReference<>();
            Path target = tempDir.resolve("cloned-slow");

            // 用显式非守护线程发起调用：心跳线程由线程工厂在调用线程上创建，
            // 若 setDaemon(true) 被移除，守护属性将继承调用线程——固定为非守护才能确定性观测
            GitResult[] resultHolder = new GitResult[1];
            Thread caller = new Thread(() -> resultHolder[0] = service.cloneRepository(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git",
                    "master", target, message -> {
                        progress.add(message);
                        if (message.contains("已耗时")) {
                            heartbeatOnDaemonThread.set(Thread.currentThread().isDaemon());
                            Matcher elapsedMatcher = HEARTBEAT_ELAPSED.matcher(message);
                            if (elapsedMatcher.find()) {
                                heartbeatElapsedSeconds.set(Long.parseLong(elapsedMatcher.group(1)));
                            }
                        }
                    }), "non-daemon-clone-caller");
            caller.setDaemon(false);
            caller.start();
            caller.join();
            GitResult result = resultHolder[0];

            assertFalse(result.isSuccess());
            assertTrue(progress.stream().anyMatch(m -> m.contains("正在克隆仓库")),
                    "克隆阻塞超过 10 秒必须触发心跳进度回调，实际回调: " + progress);
            assertTrue(heartbeatOnDaemonThread.get(),
                    "心跳线程必须是守护线程（setDaemon(true) 被移除后默认继承主线程的非守护属性，会阻止 JVM 退出）");
            Long elapsed = heartbeatElapsedSeconds.get();
            assertNotNull(elapsed, "心跳消息必须携带可解析的「已耗时 N 秒」，实际回调: " + progress);
            assertTrue(elapsed >= 10 && elapsed <= 60,
                    "心跳耗时必须是真实经过的秒数 10~60（减法改加法会得到 epoch 量级绝对值，除法改乘法会放大千倍），实际: " + elapsed);
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("克隆后目录超 500MB 上限：返回「仓库过大」拒绝结果，检出文件仍在（判定发生在克隆之后）")
    void cloneRepository_oversizeRepo_shouldReject(@TempDir Path tempDir) throws Exception {
        Path sourceRepo = tempDir.resolve("huge-source");
        Files.createDirectories(sourceRepo);
        String branch;
        try (Git git = Git.init().setDirectory(sourceRepo.toFile()).call()) {
            Files.write(sourceRepo.resolve("README.md"), "hello".getBytes(StandardCharsets.UTF_8));
            // 550MB 稀疏文件：NTFS 上 setLength 毫秒级完成且几乎不占物理磁盘，
            // 但 Files.size() 返回 550MB 逻辑大小，克隆检出后足以越过 500MB 上限
            try (RandomAccessFile sparse = new RandomAccessFile(
                    sourceRepo.resolve("big.bin").toFile(), "rw")) {
                sparse.setLength(550L * 1024 * 1024);
            }
            git.add().addFilepattern(".").call();
            git.commit()
                    .setMessage("huge")
                    .setAuthor("tester", "tester@example.com")
                    .setCommitter("tester", "tester@example.com")
                    .call();
            branch = git.getRepository().getBranch();
        }
        Path target = tempDir.resolve("cloned-huge");

        GitResult result = service.cloneRepository(
                sourceRepo.toUri().toString(), branch, target, message -> { });

        assertFalse(result.isSuccess(), "超限仓库必须被拒绝");
        assertNull(result.getLocalPath());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage().contains("仓库过大"), "实际消息: " + result.getMessage());
        assertTrue(result.getMessage().contains("上限 500 MB"), "实际消息: " + result.getMessage());
        assertTrue(Files.exists(target.resolve("big.bin")), "克隆检出已完成，拒绝判定发生在检出之后");
    }

    // ==================== deleteLocalRepo ====================

    @Test
    @DisplayName("deleteLocalRepo：路径为 null 或不存在时静默返回")
    void deleteLocalRepo_nullOrMissing_shouldReturnSilently(@TempDir Path tempDir) {
        service.deleteLocalRepo(null);
        service.deleteLocalRepo(tempDir.resolve("no-such-dir"));
        // 不抛异常即通过
        assertFalse(Files.exists(tempDir.resolve("no-such-dir")));
    }

    @Test
    @DisplayName("deleteLocalRepo：已存在的目录树被递归删除（含嵌套子目录与文件）")
    void deleteLocalRepo_existingTree_shouldDeleteRecursively(@TempDir Path tempDir) throws Exception {
        Path repoDir = tempDir.resolve("to-delete");
        Path nested = repoDir.resolve("src/main/java/com/demo");
        Files.createDirectories(nested);
        Files.write(repoDir.resolve("pom.xml"), "<project/>".getBytes(StandardCharsets.UTF_8));
        Files.write(nested.resolve("Main.java"), "class Main {}".getBytes(StandardCharsets.UTF_8));

        service.deleteLocalRepo(repoDir);

        assertFalse(Files.exists(repoDir), "目录树必须被完全删除");
    }

    @Test
    @DisplayName("deleteLocalRepo：Files.walk 抛 IOException 被 catch 兜住，不抛异常且目录保留")
    void deleteLocalRepo_walkFailure_shouldBeSwallowed(@TempDir Path tempDir) throws Exception {
        Path repoDir = tempDir.resolve("walk-fail");
        Files.createDirectories(repoDir);
        Files.write(repoDir.resolve("keep.txt"), "x".getBytes(StandardCharsets.UTF_8));

        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // 仅替换遍历入口：exists 判断等其他静态方法保持真实行为
            filesMock.when(() -> Files.walk(repoDir)).thenThrow(new IOException("walk denied"));
            service.deleteLocalRepo(repoDir);
        }

        assertTrue(Files.exists(repoDir), "walk 失败时目录必须保留（仅记日志告警）");
        assertTrue(Files.exists(repoDir.resolve("keep.txt")));
    }

    // ==================== getDirectorySize（私有，反射驱动） ====================

    private static Method getDirectorySizeMethod() throws Exception {
        Method method = GitService.class.getDeclaredMethod("getDirectorySize", Path.class);
        method.setAccessible(true);
        return method;
    }

    @Test
    @DisplayName("getDirectorySize：目录不存在时 Files.walk 抛 IOException，外层 catch 返回 0")
    void getDirectorySize_missingDir_shouldReturnZero(@TempDir Path tempDir) throws Exception {
        long size = (long) getDirectorySizeMethod().invoke(service, tempDir.resolve("no-such-dir"));

        assertEquals(0L, size);
    }

    @Test
    @DisplayName("getDirectorySize：单个文件取大小抛 IOException 时计 0，其余文件正常累加")
    void getDirectorySize_sizeFailure_shouldCountFileAsZero(@TempDir Path tempDir) throws Exception {
        Path good = tempDir.resolve("good.bin");
        Path broken = tempDir.resolve("broken.bin");
        Files.write(good, new byte[1024]);
        Files.write(broken, new byte[4096]);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            filesMock.when(() -> Files.size(broken)).thenThrow(new IOException("size unavailable"));
            long size = (long) getDirectorySizeMethod().invoke(service, tempDir);
            assertEquals(1024L, size, "broken.bin 抛异常计 0，仅累加 good.bin 的 1024 字节");
        }
    }

    @Test
    @DisplayName("getDirectorySize：目录条目不得计入——目录大小放大到远超文件总和后仍只累加普通文件")
    void getDirectorySize_directoryEntries_shouldNotBeCounted(@TempDir Path tempDir) throws Exception {
        Files.write(tempDir.resolve("a.bin"), new byte[10]);
        Files.write(tempDir.resolve("b.bin"), new byte[10]);

        try (MockedStatic<Files> filesMock = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            // 目录条目放大到远超文件总和：isRegularFile 过滤器若被替换为恒真（变异体），根目录自身会被计入求和
            filesMock.when(() -> Files.size(any(Path.class))).thenAnswer(invocation -> {
                Path path = invocation.getArgument(0);
                return Files.isDirectory(path) ? 5_000_000L : 10L;
            });

            long size = (long) getDirectorySizeMethod().invoke(service, tempDir);

            assertEquals(20L, size, "仅两个普通文件各 10 字节；目录条目（NTFS 下可能报 4096 等非零值）不得计入");
        }
    }

    // ==================== sslConfigured 静态标记反射辅助 ====================

    private static boolean sslConfiguredFlag() throws Exception {
        Field field = GitService.class.getDeclaredField("sslConfigured");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    private static void setSslConfiguredFlag(boolean value) throws Exception {
        Field field = GitService.class.getDeclaredField("sslConfigured");
        field.setAccessible(true);
        field.setBoolean(null, value);
    }

    // ==================== CombinedTrustManager（私有内部类，反射驱动） ====================

    @SuppressWarnings("unchecked")
    private static X509TrustManager combinedTrustManager(List<X509TrustManager> delegates) throws Exception {
        Class<?> cls = Class.forName("com.codeatlas.engine.git.GitService$CombinedTrustManager");
        Constructor<?> ctor = cls.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return (X509TrustManager) ctor.newInstance(delegates);
    }

    private static X509TrustManager trustingManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private static X509TrustManager rejectingManager() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                throw new CertificateException("untrusted");
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    @Test
    @DisplayName("CombinedTrustManager 空委托：服务端校验直接放行，接受颁发者列表为空")
    void combinedTrustManager_emptyDelegates_shouldPassAndReturnEmpty() throws Exception {
        X509TrustManager combined = combinedTrustManager(new ArrayList<X509TrustManager>());
        X509Certificate[] chain = new X509Certificate[0];

        combined.checkClientTrusted(chain, "RSA");
        combined.checkServerTrusted(chain, "RSA");
        assertEquals(0, combined.getAcceptedIssuers().length);
    }

    @Test
    @DisplayName("CombinedTrustManager 委托构造时拷贝列表：外部清空后仍保留原委托")
    void combinedTrustManager_constructor_shouldCopyDelegateList() throws Exception {
        List<X509TrustManager> input = new ArrayList<>();
        input.add(rejectingManager());
        X509TrustManager combined = combinedTrustManager(input);
        input.clear();

        assertThrows(CertificateException.class,
                () -> combined.checkServerTrusted(new X509Certificate[0], "RSA"),
                "若未拷贝列表，空委托会静默放行");
    }

    @Test
    @DisplayName("CombinedTrustManager 全部委托拒绝：抛出带明确信息的 CertificateException")
    void combinedTrustManager_allReject_shouldThrowCertificateException() throws Exception {
        List<X509TrustManager> delegates = new ArrayList<>();
        delegates.add(rejectingManager());
        delegates.add(rejectingManager());
        X509TrustManager combined = combinedTrustManager(delegates);

        CertificateException ex = assertThrows(CertificateException.class,
                () -> combined.checkServerTrusted(new X509Certificate[0], "RSA"));

        assertEquals("No trusted CA found for certificate chain", ex.getMessage());
    }

    @Test
    @DisplayName("CombinedTrustManager 前一个拒绝后一个信任：链校验通过（任一委托信任即放行）")
    void combinedTrustManager_secondDelegateTrusts_shouldPass() throws Exception {
        List<X509TrustManager> delegates = new ArrayList<>();
        delegates.add(rejectingManager());
        delegates.add(trustingManager());
        X509TrustManager combined = combinedTrustManager(delegates);

        combined.checkServerTrusted(new X509Certificate[0], "RSA");
        // 不抛异常即通过：第一个委托抛 CertificateException 后被吞掉，第二个委托接受
    }

    @Test
    @DisplayName("CombinedTrustManager getAcceptedIssuers：合并各委托的颁发者列表")
    void combinedTrustManager_getAcceptedIssuers_shouldMergeDelegates() throws Exception {
        List<X509TrustManager> delegates = new ArrayList<>();
        delegates.add(trustingManager());
        delegates.add(trustingManager());
        X509TrustManager combined = combinedTrustManager(delegates);

        assertEquals(0, combined.getAcceptedIssuers().length);
    }

    @Test
    @DisplayName("configureSslForWindows 幂等：多次调用仓库方法不抛异常且行为一致")
    void estimateRepoSize_repeatedCalls_shouldBeIdempotent() {
        long first = service.estimateRepoSize("D:/repos/local");
        long second = service.estimateRepoSize("D:/repos/local");

        assertEquals(-1L, first);
        assertEquals(first, second);
    }

    // ==================== mock HTTPS 代理基础设施 ====================

    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustingManager()}, null);
        return context;
    }

    /**
     * 最小 HTTPS 代理：明文接收 CONNECT 后应答 200，再将连接升级为 TLS（自签证书），
     * 按请求路径返回 200/404/403 等 JSON，用于不依赖外网地驱动 GitHub API 各状态分支。
     */
    private static final class MockGitHubHttpsProxy implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final SSLContext tlsContext;
        private volatile boolean closed;

        MockGitHubHttpsProxy(Path workDir) throws Exception {
            this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            this.tlsContext = createServerTlsContext(workDir);
            Thread acceptor = new Thread(this::acceptLoop, "mock-github-proxy");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (!closed) {
                try {
                    Socket plain = serverSocket.accept();
                    Thread worker = new Thread(() -> handleConnection(plain), "mock-github-conn");
                    worker.setDaemon(true);
                    worker.start();
                } catch (IOException e) {
                    return; // 关闭 serverSocket 时退出
                }
            }
        }

        private void handleConnection(Socket plain) {
            try (Socket socket = plain) {
                BufferedReader headReader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                String connectLine = headReader.readLine();
                if (connectLine == null || !connectLine.startsWith("CONNECT")) {
                    return;
                }
                String line;
                while ((line = headReader.readLine()) != null && !line.isEmpty()) {
                    // 丢弃 CONNECT 头部
                }
                OutputStream plainOut = socket.getOutputStream();
                plainOut.write("HTTP/1.1 200 Connection established\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                plainOut.flush();

                // 把已连接的明文 socket 升级为服务端 TLS（CONNECT 头部已消费，无预读数据）
                SSLSocket tls = (SSLSocket) tlsContext.getSocketFactory()
                        .createSocket(socket, new ByteArrayInputStream(new byte[0]), true);
                tls.startHandshake();
                try {
                    BufferedReader requestReader = new BufferedReader(
                            new InputStreamReader(tls.getInputStream(), StandardCharsets.ISO_8859_1));
                    String requestLine = requestReader.readLine();
                    if (requestLine == null) {
                        return;
                    }
                    while ((line = requestReader.readLine()) != null && !line.isEmpty()) {
                        // 丢弃请求头部
                    }
                    String path = requestLine.split(" ")[1];
                    String statusLine;
                    byte[] body;
                    if (path.contains("/test403/")) {
                        statusLine = "HTTP/1.1 403 Forbidden";
                        body = "{\"message\":\"API rate limit exceeded\"}".getBytes(StandardCharsets.UTF_8);
                    } else if (path.contains("/test404/")) {
                        statusLine = "HTTP/1.1 404 Not Found";
                        body = "{\"message\":\"Not Found\"}".getBytes(StandardCharsets.UTF_8);
                    } else if (path.contains("/nosize")) {
                        statusLine = "HTTP/1.1 200 OK";
                        body = "{\"full_name\":\"test200/nosize\"}".getBytes(StandardCharsets.UTF_8);
                    } else {
                        statusLine = "HTTP/1.1 200 OK";
                        body = "{\"size\":2048,\"full_name\":\"test200/sized\"}"
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    OutputStream tlsOut = tls.getOutputStream();
                    tlsOut.write((statusLine + "\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    tlsOut.write(body);
                    tlsOut.flush();
                } finally {
                    tls.close();
                }
            } catch (Exception ignored) {
                // 单个连接失败不影响后续请求
            }
        }

        /** 用 keytool 现场生成 api.github.com 的自签证书，构建服务端 TLS 上下文 */
        private static SSLContext createServerTlsContext(Path workDir) throws Exception {
            Path keyStorePath = workDir.resolve("mock-github-api.p12");
            File keytool = new File(System.getProperty("java.home"), "bin/keytool.exe");
            if (!keytool.isFile()) {
                keytool = new File(System.getProperty("java.home"), "bin/keytool");
            }
            Process process = new ProcessBuilder(
                    keytool.getAbsolutePath(),
                    "-genkeypair", "-alias", "mock-github",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-keystore", keyStorePath.toString(),
                    "-storetype", "PKCS12",
                    "-storepass", "changeit", "-keypass", "changeit",
                    "-dname", "CN=api.github.com",
                    "-validity", "1",
                    "-ext", "SAN=dns:api.github.com")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool 生成测试证书失败");
            }

            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keyStorePath)) {
                keyStore.load(in, "changeit".toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, "changeit".toCharArray());
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(kmf.getKeyManagers(), null, null);
            return context;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            serverSocket.close();
        }
    }
}
