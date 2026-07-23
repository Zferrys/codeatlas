package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.response.ScanVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ScanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @Operation(summary = "扫描历史")
    public ApiResponse<List<ScanVO>> getScanHistory(@PathVariable Long projectId,
                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(scanService.getScanHistory(projectId, principal.getUserId()));
    }

    @GetMapping("/latest")
    @Operation(summary = "最新扫描")
    public ApiResponse<ScanVO> getLatestScan(@PathVariable Long projectId,
                                              @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(scanService.getLatestScan(projectId, principal.getUserId()));
    }
}
