package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.service.AiModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "管理后台-AI 模型")
public class AdminAiModelController {

    private final AiModelService aiModelService;

    public AdminAiModelController(AiModelService aiModelService) {
        this.aiModelService = aiModelService;
    }

    @GetMapping("/models")
    @Operation(summary = "可用 AI 模型列表 + 当前选中")
    public ApiResponse<Map<String, Object>> listModels() {
        return ApiResponse.success(aiModelService.listModels());
    }

    @PutMapping("/model")
    @Operation(summary = "切换当前 AI 模型")
    @AuditLog(action = "SWITCH_AI_MODEL", targetType = "SYSTEM", detail = "切换 AI 模型")
    public ApiResponse<Map<String, Object>> switchModel(@RequestBody Map<String, String> body) {
        try {
            aiModelService.switchModel(body.get("model"));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", aiModelService.getCurrentModelId());
        return ApiResponse.success(result);
    }

    @PostMapping("/models/test")
    @Operation(summary = "测试模型连通性")
    public ApiResponse<Map<String, Object>> testModel(@RequestBody Map<String, String> body) {
        return ApiResponse.success(aiModelService.testModel(body.get("model")));
    }
}
