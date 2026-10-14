package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportController 单元测试")
class ReportControllerTest {

    private static final Long PROJECT_ID = 3L;
    private static final Long USER_ID = 9L;

    @Mock
    private ReportService reportService;

    private ReportController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ReportController(reportService);
        principal = new CodeAtlasUserDetails(USER_ID, "bob", "ARCHITECT");
    }

    // ==================== GET /report ====================

    @Test
    @DisplayName("export — format=pdf 返回 PDF 字节流与附件头")
    void exportPdfReturnsAttachment() {
        byte[] pdf = {1, 2, 3};
        when(reportService.generatePdf(PROJECT_ID, USER_ID)).thenReturn(pdf);

        ResponseEntity<?> response = controller.export(PROJECT_ID, "pdf", principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("attachment; filename=codeatlas-report.pdf",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(pdf, (byte[]) response.getBody());
    }

    @Test
    @DisplayName("export — format=PDF（大小写不敏感）同样走 PDF 分支")
    void exportPdfIsCaseInsensitive() {
        when(reportService.generatePdf(PROJECT_ID, USER_ID)).thenReturn(new byte[0]);

        ResponseEntity<?> response = controller.export(PROJECT_ID, "PDF", principal);

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        verify(reportService, never()).generateHtml(anyLong(), anyLong());
    }

    @Test
    @DisplayName("export — 默认 html 返回 TEXT_HTML 且无附件头")
    void exportHtmlReturnsPlainHtml() {
        when(reportService.generateHtml(PROJECT_ID, USER_ID)).thenReturn("<html>报告</html>");

        ResponseEntity<?> response = controller.export(PROJECT_ID, "html", principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
        assertEquals(null, response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("<html>报告</html>", response.getBody());
    }

    @Test
    @DisplayName("export — 未知 format 回退 html 分支")
    void exportUnknownFormatFallsBackToHtml() {
        when(reportService.generateHtml(PROJECT_ID, USER_ID)).thenReturn("body");

        ResponseEntity<?> response = controller.export(PROJECT_ID, "docx", principal);

        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
        assertEquals("body", response.getBody());
    }

    // ==================== POST /reports ====================

    @Test
    @DisplayName("generateReport — 返回固定格式清单与下载地址")
    void generateReportReturnsFormatsAndUrl() {
        ApiResponse<Map<String, Object>> response = controller.generateReport(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        Map<String, Object> data = response.getData();
        assertEquals(PROJECT_ID, data.get("projectId"));
        assertEquals(List.of("pdf", "html"), data.get("formats"));
        assertEquals("/api/v1/projects/3/reports/download", data.get("downloadUrl"));
        verifyNoInteractions(reportService);
    }

    // ==================== GET /reports ====================

    @Test
    @DisplayName("listReports — 透传服务返回的历史列表")
    void listReportsPassesThroughHistory() {
        List<Map<String, Object>> history = List.of(Map.of("id", 1L, "format", "pdf"));
        when(reportService.getReportHistory(PROJECT_ID, USER_ID)).thenReturn(history);

        ApiResponse<List<Map<String, Object>>> response = controller.listReports(PROJECT_ID, principal);

        assertEquals(200, response.getCode());
        assertEquals(history, response.getData());
        verify(reportService).getReportHistory(PROJECT_ID, USER_ID);
    }

    // ==================== GET /reports/download ====================

    @Test
    @DisplayName("downloadReport — pdf 分支返回附件头")
    void downloadReportPdf() {
        byte[] pdf = {9, 8};
        when(reportService.generatePdf(PROJECT_ID, USER_ID)).thenReturn(pdf);

        ResponseEntity<?> response = controller.downloadReport(PROJECT_ID, "pdf", principal);

        assertEquals("attachment; filename=codeatlas-report.pdf",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertArrayEquals(pdf, (byte[]) response.getBody());
    }

    @Test
    @DisplayName("downloadReport — 非 pdf 分支返回 html 正文")
    void downloadReportHtml() {
        when(reportService.generateHtml(PROJECT_ID, USER_ID)).thenReturn("<p>x</p>");

        ResponseEntity<?> response = controller.downloadReport(PROJECT_ID, "html", principal);

        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
        assertEquals("<p>x</p>", response.getBody());
        assertEquals(null, response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    }

    // ==================== GET /insights/{type}/export ====================

    @Test
    @DisplayName("exportInsight — 非法类型抛 400 且不调用服务")
    void exportInsightRejectsUnknownType() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.exportInsight(PROJECT_ID, "UNKNOWN", "pdf", principal));

        assertEquals(400, ex.getCode());
        assertEquals("不支持的报告类型", ex.getMessage());
        verifyNoInteractions(reportService);
    }

    @Test
    @DisplayName("exportInsight — 大小写敏感：小写 arch_story 不在白名单")
    void exportInsightTypeCheckIsCaseSensitive() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> controller.exportInsight(PROJECT_ID, "arch_story", "html", principal));

        assertEquals(400, ex.getCode());
        assertEquals("不支持的报告类型", ex.getMessage());
    }

    @Test
    @DisplayName("exportInsight — html 分支文件名小写并带 .html 后缀")
    void exportInsightHtmlBranch() {
        when(reportService.generateInsightHtml(PROJECT_ID, "ARCH_STORY", USER_ID)).thenReturn("<h1>故事</h1>");

        ResponseEntity<?> response = controller.exportInsight(PROJECT_ID, "ARCH_STORY", "html", principal);

        assertEquals(MediaType.TEXT_HTML, response.getHeaders().getContentType());
        assertEquals("attachment; filename=codeatlas-arch_story.html",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("<h1>故事</h1>", response.getBody());
    }

    @Test
    @DisplayName("exportInsight — 默认 pdf 分支返回 PDF 与 .pdf 后缀文件名")
    void exportInsightPdfBranch() {
        byte[] pdf = "PDF".getBytes(StandardCharsets.UTF_8);
        when(reportService.generateInsightPdf(PROJECT_ID, "CONSTITUTION_CHECK", USER_ID)).thenReturn(pdf);

        ResponseEntity<?> response = controller.exportInsight(PROJECT_ID, "CONSTITUTION_CHECK", "pdf", principal);

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertEquals("attachment; filename=codeatlas-constitution_check.pdf",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        assertArrayEquals(pdf, (byte[]) response.getBody());
    }

    @Test
    @DisplayName("exportInsight — format=HTML 大小写不敏感走 html 分支")
    void exportInsightHtmlBranchCaseInsensitive() {
        when(reportService.generateInsightHtml(PROJECT_ID, "PATTERN", USER_ID)).thenReturn("h");

        ResponseEntity<?> response = controller.exportInsight(PROJECT_ID, "PATTERN", "HTML", principal);

        assertInstanceOf(String.class, response.getBody());
        assertEquals("attachment; filename=codeatlas-pattern.html",
                response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
        verify(reportService, never()).generateInsightPdf(anyLong(), anyString(), anyLong());
    }

    @Test
    @DisplayName("exportInsight — 四种白名单类型全部放行")
    void exportInsightAcceptsAllWhitelistedTypes() {
        for (String type : List.of("ARCH_STORY", "PATTERN", "ANTI_PATTERN", "CONSTITUTION_CHECK")) {
            when(reportService.generateInsightPdf(PROJECT_ID, type, USER_ID)).thenReturn(new byte[0]);
            ResponseEntity<?> response = controller.exportInsight(PROJECT_ID, type, "pdf", principal);
            assertEquals(200, response.getStatusCode().value());
        }
    }
}
