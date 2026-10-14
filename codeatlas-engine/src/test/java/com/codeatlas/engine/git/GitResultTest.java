package com.codeatlas.engine.git;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("GitResult — 克隆结果载体")
class GitResultTest {

    @Test
    @DisplayName("成功结果：五字段原样暴露、isSuccess=true")
    void successResult_shouldExposeAllFields() {
        GitResult result = new GitResult("/tmp/repo", "refs/heads/master",
                "abc123def456", true, "clone ok");

        assertEquals("/tmp/repo", result.getLocalPath());
        assertEquals("refs/heads/master", result.getBranch());
        assertEquals("abc123def456", result.getCommitHash());
        assertTrue(result.isSuccess());
        assertEquals("clone ok", result.getMessage());
    }

    @Test
    @DisplayName("失败结果：localPath/commitHash 可为 null、isSuccess=false")
    void failureResult_shouldAllowNullFields() {
        GitResult result = new GitResult(null, "main", null, false, "仓库克隆超时");

        assertNull(result.getLocalPath());
        assertEquals("main", result.getBranch());
        assertNull(result.getCommitHash());
        assertFalse(result.isSuccess());
        assertEquals("仓库克隆超时", result.getMessage());
    }
}
