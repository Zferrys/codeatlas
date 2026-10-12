package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.mapper.*;
import com.codeatlas.server.util.MarkdownRenderer;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ProjectMapper projectMapper;
    private final ScanMapper scanMapper;
    private final ViolationMapper violationMapper;
    private final InsightService insightService;
    private final ClassSummaryMapper classSummaryMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ReportService(ProjectMapper projectMapper, ScanMapper scanMapper,
                         ViolationMapper violationMapper, InsightService insightService,
                         ClassSummaryMapper classSummaryMapper,
                         ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.scanMapper = scanMapper;
        this.violationMapper = violationMapper;
        this.insightService = insightService;
        this.classSummaryMapper = classSummaryMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public byte[] generatePdf(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        checkProjectAccess(project, projectId, userId);
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        // 与扫描统计同口径：只取最新一次扫描的违规，避免历史扫描累积导致重复与数字矛盾
        List<ViolationEntity> violations = scan != null
                ? violationMapper.findByScanId(scan.getId())
                : Collections.emptyList();
        InsightEntity archStory = insightService.getLatestArchStory(projectId);

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfWriter writer = new PdfWriter(baos);
            PdfDocument pdf = new PdfDocument(writer);
            Document document = new Document(pdf);

            PdfFont cnFont = loadChineseFont();
            float docWidth = pdf.getDefaultPageSize().getWidth() - 72; // A4 减去左右margin

            // 标题
            document.add(new Paragraph("CodeAtlas — 架构分析报告")
                    .setFontSize(20).setBold().setTextAlignment(TextAlignment.CENTER)
                    .setFont(cnFont));
            document.add(new Paragraph("项目：" + (project != null ? project.getName() : "N/A"))
                    .setFontSize(14).setFont(cnFont));
            document.add(new Paragraph("生成时间：" + java.time.LocalDateTime.now().toString().replace("T", " "))
                    .setFontSize(10).setFont(cnFont).setOpacity(0.6f));
            document.add(new Paragraph(""));

            // 健康度
            BigDecimal health = project != null ? project.getHealthScore() : BigDecimal.ZERO;
            document.add(new Paragraph("健康度评分：" + health + "/100")
                    .setFontSize(14).setBold().setFont(cnFont));
            document.add(new Paragraph(""));

            // 扫描统计
            if (scan != null) {
                document.add(new Paragraph("扫描统计").setFontSize(14).setBold().setFont(cnFont));
                Table statsTable = new Table(UnitValue.createPercentArray(new float[]{30, 70}))
                        .setWidth(UnitValue.createPercentValue(100));
                addCell(statsTable, "总类数", cnFont, true);
                addCell(statsTable, String.valueOf(scan.getTotalClasses() != null ? scan.getTotalClasses() : 0), cnFont, false);
                addCell(statsTable, "总行数", cnFont, true);
                addCell(statsTable, String.valueOf(scan.getTotalLines() != null ? scan.getTotalLines() : 0), cnFont, false);
                addCell(statsTable, "违规数", cnFont, true);
                addCell(statsTable, String.valueOf(scan.getTotalViolations() != null ? scan.getTotalViolations() : 0), cnFont, false);
                addCell(statsTable, "扫描耗时", cnFont, true);
                addCell(statsTable, String.format("%.1f 秒", scan.getDurationMs() != null ? scan.getDurationMs() / 1000.0 : 0), cnFont, false);
                document.add(statsTable);
                document.add(new Paragraph(""));
            }

            // 违规列表
            document.add(new Paragraph("违规列表（共 " + violations.size() + " 项）")
                    .setFontSize(14).setBold().setFont(cnFont));
            if (!violations.isEmpty()) {
                Table vTable = new Table(UnitValue.createPercentArray(new float[]{10, 30, 60}))
                        .setWidth(UnitValue.createPercentValue(100));
                addHeaderCell(vTable, "严重性", cnFont);
                addHeaderCell(vTable, "类", cnFont);
                addHeaderCell(vTable, "描述", cnFont);
                for (ViolationEntity v : violations) {
                    addCell(vTable, toChineseSeverity(v.getSeverity()), cnFont, false);
                    addCell(vTable, v.getClassFqn() != null ? shorten(v.getClassFqn()) : "-", cnFont, false);
                    addCell(vTable, v.getMessage() != null ? v.getMessage() : "-", cnFont, false);
                }
                document.add(vTable);
                document.add(new Paragraph(""));
            } else {
                document.add(new Paragraph("恭喜！未发现违规项。").setFont(cnFont));
                document.add(new Paragraph(""));
            }

            // 架构叙事
            if (archStory != null && archStory.getContent() != null) {
                document.add(new Paragraph("架构叙事").setFontSize(14).setBold().setFont(cnFont));
                // 截断过长的叙事内容，避免撑爆 PDF
                String story = archStory.getContent();
                if (story.length() > 6000) {
                    story = story.substring(0, 6000) + "\n\n...（内容过长，已截断，完整叙事请查看 HTML 报告）";
                }
                addNarrativeToPdf(document, story, cnFont);
            }

            document.close();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("PDF generation failed: {}", e.getMessage(), e);
            throw new RuntimeException("PDF 生成失败：" + e.getMessage());
        }
    }

    private PdfFont loadChineseFont() {
        // .ttc 是字体集合，iText 必须用 "路径,索引" 语法加载（裸路径报 Type of font not recognized）；
        // Noto CJK 集合的索引 2 = SC 简体中文（索引 0 是日文字形）
        String[] fontPaths = {
            "C:/Windows/Fonts/msyh.ttc,0",
            "C:/Windows/Fonts/simsun.ttc,0",
            "C:/Windows/Fonts/simhei.ttf",
            "/System/Library/Fonts/PingFang.ttc,0",
            "/System/Library/Fonts/STHeiti Light.ttc,0",
            "/usr/share/fonts/noto/NotoSansCJK-Regular.ttc,2",
            "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc,2",
            "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc,2",
            "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc,2",
            "/usr/share/fonts/wqy-zenhei/wqy-zenhei.ttc,0"
        };
        for (String path : fontPaths) {
            try {
                return PdfFontFactory.createFont(path, PdfEncodings.IDENTITY_H,
                        PdfFontFactory.EmbeddingStrategy.PREFER_EMBEDDED);
            } catch (Exception ignored) {
                // try next
            }
        }
        log.warn("未找到中文字体文件，PDF 中文内容可能无法正常显示");
        // fallback: try Helvetica (doesn't support CJK but at least renders ASCII)
        try {
            return PdfFontFactory.createFont();
        } catch (Exception e) {
            throw new RuntimeException("无法创建 PDF 字体", e);
        }
    }

    /** iText 无 HTML 渲染能力，叙事按行做轻量格式化：标题加粗、列表加符号、去掉 Markdown 标记 */
    private void addNarrativeToPdf(Document document, String story, PdfFont font) {
        for (String rawLine : story.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                document.add(new Paragraph(stripInlineMarkers(line.replaceFirst("^#+\\s*", "")))
                        .setFontSize(12).setBold().setFont(font));
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                document.add(new Paragraph("• " + stripInlineMarkers(line.substring(2)))
                        .setFontSize(10).setFont(font));
            } else {
                document.add(new Paragraph(stripInlineMarkers(line)).setFontSize(10).setFont(font));
            }
        }
    }

    private String stripInlineMarkers(String line) {
        return line.replace("**", "").replace("`", "");
    }

    private void addCell(Table table, String text, PdfFont font, boolean isLabel) {
        Cell cell = new Cell().add(new Paragraph(text).setFont(font).setFontSize(10));
        if (isLabel) {
            cell.setBackgroundColor(new com.itextpdf.kernel.colors.DeviceRgb(245, 245, 255));
        }
        cell.setPadding(6);
        table.addCell(cell);
    }

    private void addHeaderCell(Table table, String text, PdfFont font) {
        Cell cell = new Cell().add(new Paragraph(text).setFont(font).setFontSize(10).setBold());
        cell.setBackgroundColor(new com.itextpdf.kernel.colors.DeviceRgb(102, 126, 234));
        cell.setFontColor(new com.itextpdf.kernel.colors.DeviceRgb(255, 255, 255));
        cell.setPadding(6);
        table.addCell(cell);
    }

    private String toChineseSeverity(String severity) {
        if (severity == null) return "-";
        switch (severity.toUpperCase()) {
            case "BLOCKER": return "阻断";
            case "ERROR": return "错误";
            case "WARN": return "警告";
            case "INFO": return "建议";
            default: return severity;
        }
    }

    public String generateHtml(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        checkProjectAccess(project, projectId, userId);
        ScanRecord scan = scanMapper.findLatestByProjectId(projectId);
        // 与扫描统计同口径：只取最新一次扫描的违规，避免历史扫描累积导致重复与数字矛盾
        List<ViolationEntity> violations = scan != null
                ? violationMapper.findByScanId(scan.getId())
                : Collections.emptyList();
        InsightEntity archStory = insightService.getLatestArchStory(projectId);

        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset='UTF-8'><title>CodeAtlas 报告</title>");
        sb.append("<style>body{font-family:-apple-system,'Segoe UI','Microsoft YaHei','PingFang SC',sans-serif;margin:40px;line-height:1.6;color:#2d3748;}h1{color:#1a365d;}");
        sb.append("table{border-collapse:collapse;width:100%;}td,th{border:1px solid #ddd;padding:8px;}");
        sb.append(".severity-BLOCKER{color:red;}.severity-ERROR{color:orange;}");
        sb.append(".severity-WARN{color:#d48806;}.severity-INFO{color:#8c8c8c;}");
        sb.append(".narrative h2,.narrative h3,.narrative h4{color:#1a365d;}");
        sb.append("code{background:#f5f5f5;padding:1px 4px;border-radius:3px;}");
        sb.append("</style></head><body>");

        sb.append("<h1>CodeAtlas — 架构分析报告</h1>");
        sb.append("<h2>项目: ").append(escapeHtml(project != null ? project.getName() : "N/A")).append("</h2>");

        BigDecimal health = project != null ? project.getHealthScore() : BigDecimal.ZERO;
        sb.append("<p><strong>健康度评分: ").append(health).append("/100</strong></p>");

        if (scan != null) {
            sb.append("<h3>扫描统计</h3>");
            sb.append("<table><tr><th>指标</th><th>值</th></tr>");
            sb.append("<tr><td>总类数</td><td>").append(orZero(scan.getTotalClasses())).append("</td></tr>");
            sb.append("<tr><td>总行数</td><td>").append(orZero(scan.getTotalLines())).append("</td></tr>");
            sb.append("<tr><td>违规数</td><td>").append(orZero(scan.getTotalViolations())).append("</td></tr>");
            sb.append("<tr><td>扫描耗时</td><td>").append(orZero(scan.getDurationMs()) / 1000.0).append("秒</td></tr>");
            sb.append("</table>");
        }

        sb.append("<h3>违规列表 (").append(violations.size()).append(" 项)</h3>");
        if (!violations.isEmpty()) {
            sb.append("<table><tr><th>严重性</th><th>类</th><th>描述</th></tr>");
            for (ViolationEntity v : violations) {
                String sevClass = v.getSeverity() != null ? "severity-" + escapeHtml(v.getSeverity()) : "";
                sb.append("<tr class='").append(sevClass).append("'>");
                sb.append("<td>").append(escapeHtml(v.getSeverity())).append("</td>");
                sb.append("<td>").append(v.getClassFqn() != null ? escapeHtml(shorten(v.getClassFqn())) : "-").append("</td>");
                sb.append("<td>").append(escapeHtml(v.getMessage())).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }

        if (archStory != null && archStory.getContent() != null) {
            sb.append("<h3>架构叙事</h3>");
            sb.append("<div class='narrative'>").append(MarkdownRenderer.toHtml(archStory.getContent())).append("</div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    /** 进行中/中断的扫描这些统计列可为 NULL，统一兜底为 0，避免导出时拆箱 NPE */
    private static long orZero(Number value) {
        return value != null ? value.longValue() : 0L;
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private void checkProjectAccess(Project project, Long projectId, Long userId) {
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        // 项目创建者可直接访问
        if (userId.equals(project.getCreatedBy())) {
            return;
        }
        // 检查是否为项目成员
        if (projectMemberMapper.findByProjectAndUser(projectId, userId) != null) {
            return;
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
    }

    public List<Map<String, Object>> getReportHistory(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        checkProjectAccess(project, projectId, userId);

        List<ScanRecord> scans = scanMapper.findByProjectId(projectId);
        List<Map<String, Object>> history = new ArrayList<>();
        for (ScanRecord scan : scans) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("scanId", scan.getId());
            entry.put("totalClasses", scan.getTotalClasses());
            entry.put("totalViolations", scan.getTotalViolations());
            entry.put("durationMs", scan.getDurationMs());
            entry.put("status", scan.getStatus());
            entry.put("completedAt", scan.getCompletedAt() != null ? scan.getCompletedAt().toString() : null);
            history.add(entry);
        }
        return history;
    }

    private String shorten(String fqn) {
        int idx = fqn.lastIndexOf('.');
        return idx > 0 ? fqn.substring(idx + 1) : fqn;
    }
}
