package com.codeatlas.server.controller;

import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@Tag(name = "报告导出")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping("/report")
    @Operation(summary = "导出项目分析报告（PDF/HTML）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> export(@PathVariable Long projectId,
                                     @RequestParam(defaultValue = "html") String format,
                                     @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdfBytes = reportService.generatePdf(projectId, principal.getUserId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=codeatlas-report.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);
        }

        String html = reportService.generateHtml(projectId, principal.getUserId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }

    @PostMapping("/reports")
    @Operation(summary = "生成分析报告")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> generateReport(@PathVariable Long projectId,
                                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        Map<String, Object> result = new HashMap<>();
        result.put("projectId", projectId);
        result.put("formats", java.util.List.of("pdf", "html"));
        result.put("downloadUrl", "/api/v1/projects/" + projectId + "/reports/download");
        return ApiResponse.success(result);
    }

    @GetMapping("/reports")
    @Operation(summary = "获取报告生成历史")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> listReports(@PathVariable Long projectId,
                                                               @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(reportService.getReportHistory(projectId, principal.getUserId()));
    }

    @GetMapping("/reports/download")
    @Operation(summary = "下载最新报告（PDF/HTML）")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<?> downloadReport(@PathVariable Long projectId,
                                             @RequestParam(defaultValue = "html") String format,
                                             @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        if ("pdf".equalsIgnoreCase(format)) {
            byte[] pdfBytes = reportService.generatePdf(projectId, principal.getUserId());
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=codeatlas-report.pdf")
                    .contentType(MediaType.APPLICATION_PDF)
                    .body(pdfBytes);
        }
        String html = reportService.generateHtml(projectId, principal.getUserId());
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(html);
    }
}
