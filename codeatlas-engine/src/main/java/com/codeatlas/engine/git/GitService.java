package com.codeatlas.engine.git;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);

    private static final long CLONE_TIMEOUT_MINUTES = 5;
    private static final long MAX_REPO_SIZE_BYTES = 500L * 1024 * 1024; // 500MB

    public GitResult cloneRepository(String repoUrl, String branch, Path targetDir) {
        try {
            Files.createDirectories(targetDir);
            log.info("Cloning {} branch={} to {}", repoUrl, branch, targetDir);

            CompletableFuture<GitResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Git git = Git.cloneRepository()
                            .setURI(repoUrl)
                            .setBranch(branch)
                            .setDirectory(targetDir.toFile())
                            .setCloneSubmodules(false)
                            .setCloneAllBranches(false)
                            .call();

                    try (Repository repo = git.getRepository()) {
                        Ref head = repo.getRefDatabase().findRef("HEAD");
                        String commitHash = head != null && head.getObjectId() != null
                                ? head.getObjectId().getName()
                                : "unknown";
                        String actualBranch = repo.getBranch();
                        String localPath = targetDir.toAbsolutePath().toString();

                        // 检查仓库大小
                        long sizeBytes = getDirectorySize(targetDir);
                        if (sizeBytes > MAX_REPO_SIZE_BYTES) {
                            log.warn("Repo size {} MB exceeds limit {} MB, rejecting",
                                    sizeBytes / (1024 * 1024), MAX_REPO_SIZE_BYTES / (1024 * 1024));
                            return new GitResult(null, branch, null, false,
                                    "Repository too large: " + (sizeBytes / (1024 * 1024)) + " MB");
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
        } catch (TimeoutException e) {
            log.error("Clone timeout for {} after {} minutes", repoUrl, CLONE_TIMEOUT_MINUTES);
            return new GitResult(null, branch, null, false,
                    "Clone timeout after " + CLONE_TIMEOUT_MINUTES + " minutes");
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
}
