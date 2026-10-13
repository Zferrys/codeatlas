package com.codeatlas.server.controller;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.AiTaskProgressRegistry;
import com.codeatlas.server.service.ProjectAccessChecker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.regex.Pattern;

/**
 * AI 任务进度订阅端点：SSE 旁路推送，与任务 POST 请求解耦；
 * 客户端断开连接即取消信号，语义见 {@link AiTaskProgressRegistry}。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/ai")
@Tag(name = "AI 分析")
public class AiProgressController {

    /** taskId 由前端生成：限制字符集与长度，防日志注入与超长键 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final AiTaskProgressRegistry progressRegistry;
    private final ProjectAccessChecker projectAccessChecker;

    public AiProgressController(AiTaskProgressRegistry progressRegistry,
                                ProjectAccessChecker projectAccessChecker) {
        this.progressRegistry = progressRegistry;
        this.projectAccessChecker = projectAccessChecker;
    }

    @GetMapping(value = "/ai-progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "订阅 AI 任务实时进度（SSE）")
    @PreAuthorize("isAuthenticated()")
    public SseEmitter aiProgress(@PathVariable Long projectId,
                                 @RequestParam String taskId,
                                 @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "taskId 无效");
        }
        return progressRegistry.subscribe(taskId, projectId);
    }
}
