package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scans")
@Tag(name = "扫描管理")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @PostMapping
    @Operation(summary = "触发扫描")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    @AuditLog(action = "RUN_SCAN", targetType = "SCAN", targetIdExpression = "#result.data.id", detail = "触发代码扫描")
    public ApiResponse<ScanVO> triggerScan(@PathVariable Long projectId,
                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(scanService.triggerScan(projectId, principal.getUserId()));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "扫描历史")
    public ApiResponse<PageResult<ScanVO>> getScanHistory(
            @PathVariable Long projectId,
            @AuthenticationPrincipal CodeAtlasUserDetails principal,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(scanService.getScanHistory(projectId, principal.getUserId(), page, size));
    }

    @GetMapping("/latest")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "最新扫描")
    public ApiResponse<ScanVO> getLatestScan(@PathVariable Long projectId,
                                              @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(scanService.getLatestScan(projectId, principal.getUserId()));
    }

    @GetMapping("/status")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "扫描状态")
    public ApiResponse<Map<String, Object>> getScanStatus(@PathVariable Long projectId) {
        return ApiResponse.success(scanService.getScanStatus(projectId));
    }

    @PostMapping("/increment")
    @Operation(summary = "增量扫描（复用已有仓库，只分析变更文件）")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    @AuditLog(action = "RUN_INCREMENTAL_SCAN", targetType = "SCAN", detail = "触发增量扫描")
    public ApiResponse<ScanVO> triggerIncrementalScan(@PathVariable Long projectId,
                                                       @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(scanService.triggerIncrementalScan(projectId, principal.getUserId()));
    }
}
