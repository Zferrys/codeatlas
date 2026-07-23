package com.codeatlas.server.service;

import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.*;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.List;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final ViolationMapper violationMapper;
    private final InsightService insightService;
    private final ClassSummaryMapper classSummaryMapper;

    public ReportService(ProjectMapper projectMapper, ScanMapper scanMapper,
                         ViolationMapper violationMapper, InsightService insightService,
                         ClassSummaryMapper classSummaryMapper) {
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.violationMapper = violationMapper;
        this.insightService = insightService;
        this.classSummaryMapper = classSummaryMapper;
    }

    public byte[] generatePdf(Long projectId) {
        Project project = projectMapper.findById(projectId);
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        List<ViolationEntity> violations = violationMapper.findByProjectId(projectId);
        InsightEntity archStory = insightService.getLatestArchStory(projectId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);

            // 标题
            document.add(new Paragraph("CodeAtlas — 架构分析报告")
                    .setFontSize(20).setBold().setTextAlignment(TextAlignment.CENTER));
            document.add(new Paragraph("项目: " + (project != null ? project.getName() : "N/A"))
                    .setFontSize(14));
            document.add(new Paragraph(""));

            // 健康度
            BigDecimal health = project != null ? project.getHealthScore() : BigDecimal.ZERO;
            document.add(new Paragraph("健康度评分: " + health + "/100").setFontSize(12).setBold());
            document.add(new Paragraph(""));

            // 扫描统计
            if (scan != null) {
                document.add(new Paragraph("扫描统计").setFontSize(14).setBold());
                Table statsTable = new Table(2);
                statsTable.addCell("总类数");
                statsTable.addCell(String.valueOf(scan.getTotalClasses() != null ? scan.getTotalClasses() : 0));
                statsTable.addCell("总行数");
                statsTable.addCell(String.valueOf(scan.getTotalLines() != null ? scan.getTotalLines() : 0));
                statsTable.addCell("违规数");
                statsTable.addCell(String.valueOf(scan.getTotalViolations() != null ? scan.getTotalViolations() : 0));
                statsTable.addCell("扫描耗时");
                statsTable.addCell((scan.getDurationMs() != null ? scan.getDurationMs() / 1000.0 : 0) + "秒");
                document.add(statsTable);
                document.add(new Paragraph(""));
            }

            // 违规列表
            document.add(new Paragraph("违规列表 (" + violations.size() + " 项)").setFontSize(14).setBold());
            if (!violations.isEmpty()) {
                Table vTable = new Table(3);
                vTable.addCell("严重性");
                vTable.addCell("类");
                vTable.addCell("描述");
                for (ViolationEntity v : violations) {
                    vTable.addCell(v.getSeverity() != null ? v.getSeverity() : "-");
                    vTable.addCell(v.getClassFqn() != null ? shorten(v.getClassFqn()) : "-");
                    vTable.addCell(v.getMessage() != null ? v.getMessage() : "-");
                }
                document.add(vTable);
                document.add(new Paragraph(""));
            }

            // 架构叙事
            if (archStory != null && archStory.getContent() != null) {
                document.add(new Paragraph("架构叙事").setFontSize(14).setBold());
                document.add(new Paragraph(archStory.getContent()).setFontSize(10));
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("PDF generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("PDF 生成失败: " + e.getMessage());
        }
    }

    public String generateHtml(Long projectId) {
        Project project = projectMapper.findById(projectId);
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        List<ViolationEntity> violations = violationMapper.findByProjectId(projectId);
        InsightEntity archStory = insightService.getLatestArchStory(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='UTF-8'><title>CodeAtlas 报告</title>");
        sb.append("<style>body{font-family:Arial;margin:40px;}h1{color:#1a365d;}");
        sb.append("table{border-collapse:collapse;width:100%;}td,th{border:1px solid #ddd;padding:8px;}");
        sb.append(".severity-BLOCKER{color:red;}.severity-ERROR{color:orange;}</style></head><body>");

        sb.append("<h1>CodeAtlas — 架构分析报告</h1>");
        sb.append("<h2>项目: ").append(project != null ? project.getName() : "N/A").append("</h2>");

        BigDecimal health = project != null ? project.getHealthScore() : BigDecimal.ZERO;
        sb.append("<p><strong>健康度评分: ").append(health).append("/100</strong></p>");

        if (scan != null) {
            sb.append("<h3>扫描统计</h3>");
            sb.append("<table><tr><th>指标</th><th>值</th></tr>");
            sb.append("<tr><td>总类数</td><td>").append(scan.getTotalClasses()).append("</td></tr>");
            sb.append("<tr><td>总行数</td><td>").append(scan.getTotalLines()).append("</td></tr>");
            sb.append("<tr><td>违规数</td><td>").append(scan.getTotalViolations()).append("</td></tr>");
            sb.append("<tr><td>扫描耗时</td><td>").append(scan.getDurationMs() / 1000.0).append("秒</td></tr>");
            sb.append("</table>");
        }

        sb.append("<h3>违规列表 (").append(violations.size()).append(" 项)</h3>");
        if (!violations.isEmpty()) {
            sb.append("<table><tr><th>严重性</th><th>类</th><th>描述</th></tr>");
            for (ViolationEntity v : violations) {
                String sevClass = v.getSeverity() != null ? "severity-" + v.getSeverity() : "";
                sb.append("<tr class='").append(sevClass).append("'>");
                sb.append("<td>").append(v.getSeverity()).append("</td>");
                sb.append("<td>").append(v.getClassFqn() != null ? shorten(v.getClassFqn()) : "-").append("</td>");
                sb.append("<td>").append(v.getMessage()).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        if (archStory != null && archStory.getContent() != null) {
            sb.append("<h3>架构叙事</h3>");
            sb.append("<pre style='white-space:pre-wrap;'>").append(archStory.getContent()).append("</pre>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private String shorten(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(idx + 1) : fqn;
    }
}
