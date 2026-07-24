package com.codeatlas.engine.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private static final long CLONE_TIMEOUT_MINUTES = 10;
    private static final long MAX_REPO_SIZE_BYTES = 500L * 1024 * 1024; // 克隆后本地大小上限 500MB
    private static final long PROGRESS_HEARTBEAT_SECONDS = 10;
    // GitHub API 返回的 size 是裸仓库大小(KB)，工作树约 3~5 倍
    private static final double SIZE_ESTIMATE_FACTOR = 4.0;

    private static final Pattern GITHUB_URL_PATTERN =
            Pattern.compile("https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?(?:/.*)?$");

    private static volatile boolean sslConfigured = false;

    /**
     * 预估仓库大小，不实际克隆。目前支持 GitHub（通过 API），其他返回 -1 表示未知。
     * @return 预估字节数，-1 表示无法获取
     */
    public long estimateRepoSize(String repoUrl) {
        configureSslForWindows();

        // GitHub API
        Matcher m = GITHUB_URL_PATTERN.matcher(repoUrl);
        if (m.matches()) {
            String owner = m.group(1);
            String repo = m.group(2);
            String apiUrl = "https://api.github.com/repos/" + owner + "/" + repo;
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(apiUrl).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
                conn.setRequestProperty("User-Agent", "CodeAtlas/1.0");
                int status = conn.getResponseCode();
                if (status == 200) {
                    StringBuilder body = new StringBuilder();
                    try (InputStreamReader reader = new InputStreamReader(
                            conn.getInputStream(), StandardCharsets.UTF_8)) {
                        char[] buf = new char[1024];
                        int n;
                        while ((n = reader.read(buf)) > 0) {
                            body.append(buf, 0, n);
                            if (body.length() > 8192) break;
                        }
                    }
                    // 简单解析 "size": N 字段
                    Matcher sizeMatcher = Pattern.compile("\"size\"\\s*:\\s*(\\d+)").matcher(body);
                    if (sizeMatcher.find()) {
                        long sizeKb = Long.parseLong(sizeMatcher.group(1));
                        long estimatedBytes = (long) (sizeKb * 1024 * SIZE_ESTIMATE_FACTOR);
                        log.info("GitHub API: {}/{} size={}KB, estimated working tree={}MB",
                                owner, repo, sizeKb, estimatedBytes / (1024 * 1024));
                        return estimatedBytes;
                    }
                } else if (status == 404) {
                    return -1; // 仓库不存在，让 clone 阶段报错
                } else if (status == 403) {
                    log.debug("GitHub API rate limited for {}/{}", owner, repo);
                }
            } catch (IOException e) {
                log.debug("Failed to query GitHub API for {}/{}: {}", owner, repo, e.getMessage());
            }
        }

        return -1; // 非 GitHub URL 或 API 不可用
    }

    /**
     * 配置 JGit 同时信任 JDK 默认 + Windows 系统证书库。
     * 不信任所有证书，只扩展受信 CA 范围，保留完整的证书链校验。
     */
    private static synchronized void configureSslForWindows() {
        if (sslConfigured) return;
        try {
            TrustManagerFactory defaultTmf = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            defaultTmf.init((KeyStore) null);
            List<X509TrustManager> trustManagers = new ArrayList<>();
            for (TrustManager tm : defaultTmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager) {
                    trustManagers.add((X509TrustManager) tm);
                }
            }

            // 叠加 Windows 系统证书库
            try {
                KeyStore windowsStore = KeyStore.getInstance("Windows-ROOT");
                windowsStore.load(null, null);
                TrustManagerFactory windowsTmf = TrustManagerFactory.getInstance(
                        TrustManagerFactory.getDefaultAlgorithm());
                windowsTmf.init(windowsStore);
                for (TrustManager tm : windowsTmf.getTrustManagers()) {
                    if (tm instanceof X509TrustManager) {
                        trustManagers.add((X509TrustManager) tm);
                    }
                }
                log.info("Loaded Windows-ROOT certificate store for JGit SSL");
            } catch (Exception e) {
                log.debug("Windows-ROOT keystore not available, using JDK default only: {}", e.getMessage());
            }

            X509TrustManager combined = new CombinedTrustManager(trustManagers);
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{combined}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            sslConfigured = true;
            log.info("JGit SSL configured — JDK default + Windows system CAs");
        } catch (Exception e) {
            log.warn("Failed to configure JGit SSL, falling back to default: {}", e.getMessage());
        }
    }

    public GitResult cloneRepository(String repoUrl, String branch, Path targetDir,
                                      Consumer<String> progressCallback) {
        configureSslForWindows();
        try {
            Files.createDirectories(targetDir);
            log.info("Cloning {} branch={} to {}", repoUrl, branch, targetDir);

            AtomicLong startedAt = new AtomicLong(System.currentTimeMillis());
            // 心跳线程：定期回调进度，让前端知道还在跑
            ScheduledExecutorService progressScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "clone-heartbeat");
                t.setDaemon(true);
                return t;
            });
            progressScheduler.scheduleAtFixedRate(() -> {
                long elapsed = (System.currentTimeMillis() - startedAt.get()) / 1000;
                progressCallback.accept("正在克隆仓库... (已耗时 " + elapsed + " 秒, 大仓库可能较慢)");
            }, PROGRESS_HEARTBEAT_SECONDS, PROGRESS_HEARTBEAT_SECONDS, TimeUnit.SECONDS);

            try {
                CompletableFuture<GitResult> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        Git git = Git.cloneRepository()
                                .setURI(repoUrl)
                                .setBranch(branch)
                                .setDirectory(targetDir.toFile())
                                .setCloneSubmodules(false)
                                .setCloneAllBranches(false)
                                .setDepth(50)
                                .setProgressMonitor(new org.eclipse.jgit.lib.TextProgressMonitor(
                                        new java.io.PrintWriter(System.out)))
                                .call();

                        try (Repository repo = git.getRepository()) {
                            Ref head = repo.getRefDatabase().findRef("HEAD");
                            String commitHash = head != null && head.getObjectId() != null
                                    ? head.getObjectId().getName()
                                    : "unknown";
                            String actualBranch = repo.getBranch();
                            String localPath = targetDir.toAbsolutePath().toString();

                            long sizeBytes = getDirectorySize(targetDir);
                            if (sizeBytes > MAX_REPO_SIZE_BYTES) {
                                log.warn("Repo size {} MB exceeds limit {} MB, rejecting",
                                        sizeBytes / (1024 * 1024), MAX_REPO_SIZE_BYTES / (1024 * 1024));
                                return new GitResult(null, branch, null, false,
                                        "仓库过大: " + (sizeBytes / (1024 * 1024)) + " MB (上限 " +
                                        (MAX_REPO_SIZE_BYTES / (1024 * 1024)) + " MB)，建议拆分项目或使用子模块");
                            }

                            log.info("Clone success: {} @ {} ({}) size={}MB",
                                    localPath, commitHash, actualBranch, sizeBytes / (1024 * 1024));
                            return new GitResult(localPath, actualBranch, commitHash, true, "clone ok");
                        }
                    } catch (GitAPIException | IOException e) {
                        log.error("Clone failed for {}: {}", repoUrl, e.getMessage());
                        return new GitResult(null, branch, null, false, e.getMessage());
                    }
                });

                return future.get(CLONE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } finally {
                progressScheduler.shutdownNow();
            }
        } catch (TimeoutException e) {
            log.error("Clone timeout for {} after {} minutes", repoUrl, CLONE_TIMEOUT_MINUTES);
            return new GitResult(null, branch, null, false,
                    "仓库克隆超时 (超过 " + CLONE_TIMEOUT_MINUTES + " 分钟)。可能原因: 仓库过大或网络较慢，" +
                    "建议检查仓库大小 (GitHub 页面可见) 或改用本地路径扫描");
        } catch (Exception e) {
            log.error("Clone failed for {}: {}", repoUrl, e.getMessage());
            return new GitResult(null, branch, null, false, e.getMessage());
        }
    }

    private long getDirectorySize(Path dir) {
        try {
            return Files.walk(dir)
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    public void deleteLocalRepo(Path localPath) {
        if (localPath == null || !Files.exists(localPath)) {
            return;
        }
        try {
            Files.walk(localPath)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            log.debug("Deleted local repo: {}", localPath);
        } catch (IOException e) {
            log.warn("Failed to delete local repo {}: {}", localPath, e.getMessage());
        }
    }

    /**
     * 组合多个 TrustManager，任一个信任即通过证书链校验。
     */
    private static class CombinedTrustManager implements X509TrustManager {

        private final List<X509TrustManager> delegates;

        CombinedTrustManager(List<X509TrustManager> delegates) {
            this.delegates = new ArrayList<>(delegates);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 服务端不需要校验客户端证书，跳过
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws java.security.cert.CertificateException {
            if (delegates.isEmpty()) return;
            for (X509TrustManager tm : delegates) {
                try {
                    tm.checkServerTrusted(chain, authType);
                    return;
                } catch (java.security.cert.CertificateException ignored) {
                }
            }
            throw new java.security.cert.CertificateException(
                    "No trusted CA found for certificate chain");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> certs = new ArrayList<>();
            for (X509TrustManager tm : delegates) {
                certs.addAll(Arrays.asList(tm.getAcceptedIssuers()));
            }
            return certs.toArray(new X509Certificate[0]);
        }
    }
}
