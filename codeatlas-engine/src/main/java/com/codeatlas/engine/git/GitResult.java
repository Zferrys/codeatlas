package com.codeatlas.engine.git;

public class GitResult {

    private String localPath;
    private String branch;
    private String commitHash;
    private boolean success;
    private String message;

    public GitResult(String localPath, String branch, String commitHash, boolean success, String message) {
        this.localPath = localPath;
        this.branch = branch;
        this.commitHash = commitHash;
        this.success = success;
        this.message = message;
    }

    public String getLocalPath() { return localPath; }
    public String getBranch() { return branch; }
    public String getCommitHash() { return commitHash; }
    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
}
