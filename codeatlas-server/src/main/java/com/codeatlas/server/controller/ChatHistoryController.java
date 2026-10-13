package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.ChatHistoryEntity;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ChatHistoryService;
import com.codeatlas.server.service.ProjectAccessChecker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat-history")
@Tag(name = "对话历史")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;
    private final ProjectAccessChecker projectAccessChecker;

    public ChatHistoryController(ChatHistoryService chatHistoryService,
                                 ProjectAccessChecker projectAccessChecker) {
        this.chatHistoryService = chatHistoryService;
        this.projectAccessChecker = projectAccessChecker;
    }

    @GetMapping
    @Operation(summary = "查询当前用户的对话历史（每人每项目每类型最多 10 条）")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> list(@PathVariable Long projectId,
                                                       @RequestParam String scope,
                                                       @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        List<ChatHistoryEntity> records = chatHistoryService.list(projectId, principal.getUserId(), scope);
        List<Map<String, Object>> items = new ArrayList<>();
        for (ChatHistoryEntity record : records) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", record.getId());
            item.put("scope", record.getScope());
            item.put("contextFqn", record.getContextFqn());
            item.put("question", record.getQuestion());
            item.put("answer", record.getAnswer());
            item.put("verification", record.getVerification());
            item.put("createdAt", record.getCreatedAt() != null ? record.getCreatedAt().toString() : null);
            items.add(item);
        }
        return ApiResponse.success(items);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除一条对话历史")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> delete(@PathVariable Long projectId,
                                    @PathVariable Long id,
                                    @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        projectAccessChecker.check(projectId, principal.getUserId());
        chatHistoryService.delete(id, principal.getUserId());
        return ApiResponse.success(null);
    }
}
