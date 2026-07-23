package com.codeatlas.server.controller;

import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/report")
@Tag(name = "报告导出")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping
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
}
