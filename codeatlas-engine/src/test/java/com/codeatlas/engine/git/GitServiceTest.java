package com.codeatlas.engine.git;

import com.sun.net.httpserver.HttpServer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

/**
 * GitService — 仓库大小预估、本地克隆、目录清理与 CombinedTrustManager 信任链。
 * 克隆与 API 状态分支全部走本地 file:// 协议和本地 mock HTTPS 代理，不依赖外网。
 */
@DisplayName("GitService — 克隆/清理/信任链")
class GitServiceTest {

    private final GitService service = new GitService();

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

    // ==================== cloneRepository ====================

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
            Path target = tempDir.resolve("cloned-slow");

            GitResult result = service.cloneRepository(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/repo.git",
                    "master", target, progress::add);

            assertFalse(result.isSuccess());
            assertTrue(progress.stream().anyMatch(m -> m.contains("正在克隆仓库")),
                    "克隆阻塞超过 10 秒必须触发心跳进度回调，实际回调: " + progress);
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
