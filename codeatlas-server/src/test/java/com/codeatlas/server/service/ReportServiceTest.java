package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ProjectMember;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.ViolationEntity;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportService 单元测试（PDF/HTML 报告生成 + 项目访问控制）")
class ReportServiceTest {

    private static final Long PROJECT_ID = 10L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long USER_ID = 7L;
    private static final Long SCAN_ID = 55L;
    private static final String INSIGHT_TYPE = "PATTERN";

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private ViolationMapper violationMapper;
    @Mock
    private InsightService insightService;
    @Mock
    private ClassSummaryMapper classSummaryMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;

    private ReportService service;

    @BeforeEach
    void setUp() {
        service = new ReportService(projectMapper, scanMapper, violationMapper,
                insightService, classSummaryMapper, projectMemberMapper);
    }

    // ========== 构造与共享工具 ==========

    private static Project ownedProject(String name, String health) {
        Project project = new Project();
        project.setId(PROJECT_ID);
        project.setName(name);
        project.setCreatedBy(USER_ID);
        project.setHealthScore(health != null ? new BigDecimal(health) : null);
        return project;
    }

    private static ScanRecord scan() {
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        scan.setProjectId(PROJECT_ID);
        scan.setStatus("COMPLETED");
        scan.setTotalClasses(9642);
        scan.setTotalLines(8731);
        scan.setTotalViolations(1);
        scan.setDurationMs(2500L);
        return scan;
    }

    private static ViolationEntity violation(String severity, String fqn, String message) {
        ViolationEntity entity = new ViolationEntity();
        entity.setSeverity(severity);
        entity.setClassFqn(fqn);
        entity.setMessage(message);
        return entity;
    }

    private static InsightEntity insight(String title, String content, String confidence) {
        InsightEntity entity = new InsightEntity();
        entity.setTitle(title);
        entity.setContent(content);
        entity.setConfidence(confidence != null ? new BigDecimal(confidence) : null);
        entity.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        return entity;
    }

    private static String extractPdfText(byte[] pdfBytes) {
        try (PdfDocument document = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdfBytes)))) {
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                text.append(PdfTextExtractor.getTextFromPage(document.getPage(page))).append('\n');
            }
            return text.toString();
        } catch (Exception e) {
            throw new IllegalStateException("提取 PDF 文本失败", e);
        }
    }

    private static void assertIsPdf(byte[] bytes) {
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
        assertEquals("%PDF", new String(bytes, 0, 4, StandardCharsets.ISO_8859_1));
    }

    // ========== getReportHistory ==========

    @Test
    @DisplayName("历史报告：逐条映射扫描字段，completedAt 为空时输出 null")
    void getReportHistory_shouldMapScansInOrder() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        ScanRecord first = scan();
        first.setCompletedAt(LocalDateTime.of(2026, 1, 2, 3, 4, 5));
        ScanRecord second = scan();
        second.setId(66L);
        second.setStatus("FAILED");
        second.setCompletedAt(null);
        when(scanMapper.findByProjectId(PROJECT_ID)).thenReturn(Arrays.asList(first, second));

        List<Map<String, Object>> history = service.getReportHistory(PROJECT_ID, USER_ID);

        assertEquals(2, history.size());
        Map<String, Object> entry0 = history.get(0);
        assertEquals(SCAN_ID, entry0.get("scanId"));
        assertEquals(9642, entry0.get("totalClasses"));
        assertEquals(1, entry0.get("totalViolations"));
        assertEquals(2500L, entry0.get("durationMs"));
        assertEquals("COMPLETED", entry0.get("status"));
        assertEquals("2026-01-02T03:04:05", entry0.get("completedAt"));
        assertEquals(66L, history.get(1).get("scanId"));
        assertEquals("FAILED", history.get(1).get("status"));
        assertNull(history.get(1).get("completedAt"));
    }

    @Test
    @DisplayName("历史报告：无扫描记录返回空列表")
    void getReportHistory_noScans_shouldReturnEmptyList() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findByProjectId(PROJECT_ID)).thenReturn(Collections.emptyList());

        List<Map<String, Object>> history = service.getReportHistory(PROJECT_ID, USER_ID);

        assertTrue(history.isEmpty());
    }

    // ========== 访问控制 ==========

    @Test
    @DisplayName("项目不存在：抛 404 业务异常，不查询其他数据")
    void access_projectNotFound_shouldThrowNotFound() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateHtml(PROJECT_ID, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        verifyNoInteractions(scanMapper, violationMapper, insightService, projectMemberMapper);
    }

    @Test
    @DisplayName("非创建者且非成员：抛 404 业务异常")
    void access_notMember_shouldThrowNotFound() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        // ownedProject 的 createdBy 是 USER_ID，这里用另一个用户访问
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateHtml(PROJECT_ID, OTHER_USER_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("非创建者但为项目成员：允许导出报告")
    void access_projectMember_shouldAllowGenerateHtml() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID))
                .thenReturn(new ProjectMember());
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        String html = service.generateHtml(PROJECT_ID, OTHER_USER_ID);

        assertTrue(html.contains("<h1>CodeAtlas — 架构分析报告</h1>"));
        verify(projectMemberMapper).findByProjectAndUser(PROJECT_ID, OTHER_USER_ID);
    }

    // ========== generateHtml ==========

    @Test
    @DisplayName("HTML 报告：完整数据逐字匹配（含 XSS 转义、类名缩短、Markdown 渲染）")
    void generateHtml_fullData_shouldMatchExactly() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan());
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Arrays.asList(
                violation("ERROR", "com.demo.big.pkg.OrderService", "A<B>&\"C'D"),
                violation(null, null, null)));
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(
                insight("架构叙事", "## 架构概述\n\n- 服务层：3 个类\n- 数据层：1 个类\n\n**重点** `OrderService`", "0.68"));

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertEquals(STYLE_MAIN
                + "<h1>CodeAtlas — 架构分析报告</h1>"
                + "<h2>项目: DemoProj</h2>"
                + "<p><strong>健康度评分: 85.5/100</strong></p>"
                + "<h3>扫描统计</h3>"
                + "<table><tr><th>指标</th><th>值</th></tr>"
                + "<tr><td>总类数</td><td>9642</td></tr>"
                + "<tr><td>总行数</td><td>8731</td></tr>"
                + "<tr><td>违规数</td><td>1</td></tr>"
                + "<tr><td>扫描耗时</td><td>2.5秒</td></tr>"
                + "</table>"
                + "<h3>违规列表 (2 项)</h3>"
                + "<table><tr><th>严重性</th><th>类</th><th>描述</th></tr>"
                + "<tr class='severity-ERROR'><td>ERROR</td><td>OrderService</td><td>A&lt;B&gt;&amp;&quot;C&#39;D</td></tr>"
                + "<tr class=''><td></td><td>-</td><td></td></tr>"
                + "</table>"
                + "<h3>架构叙事</h3>"
                + "<div class='narrative'><h3>架构概述</h3><ul><li>服务层：3 个类</li><li>数据层：1 个类</li></ul>"
                + "<p><strong>重点</strong> <code>OrderService</code></p></div>"
                + "</body></html>", html);
    }

    @Test
    @DisplayName("HTML 报告：无扫描且无叙事时只输出标题与健康度")
    void generateHtml_noScanNoStory_shouldOmitSections() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "90"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertEquals(STYLE_MAIN
                + "<h1>CodeAtlas — 架构分析报告</h1>"
                + "<h2>项目: DemoProj</h2>"
                + "<p><strong>健康度评分: 90/100</strong></p>"
                + "<h3>违规列表 (0 项)</h3>"
                + "</body></html>", html);
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("HTML 报告：扫描统计列为 null 时按 0 兜底，耗时按 0.0 秒")
    void generateHtml_nullScanStats_shouldFallbackToZero() {
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan);
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertTrue(html.contains("<tr><td>总类数</td><td>0</td></tr>"));
        assertTrue(html.contains("<tr><td>总行数</td><td>0</td></tr>"));
        assertTrue(html.contains("<tr><td>违规数</td><td>0</td></tr>"));
        assertTrue(html.contains("<tr><td>扫描耗时</td><td>0.0秒</td></tr>"));
        assertTrue(html.contains("<h3>违规列表 (0 项)</h3>"));
    }

    @Test
    @DisplayName("HTML 报告：健康度评分为 null 时输出字面量 null（当前行为）")
    void generateHtml_nullHealthScore_shouldRenderNullLiteral() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", null));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertTrue(html.contains("<p><strong>健康度评分: null/100</strong></p>"));
    }

    @Test
    @DisplayName("HTML 报告：严重性含 XSS 字符时转义后进入样式类与单元格")
    void generateHtml_severityWithXssChars_shouldEscape() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan());
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(
                violation("<img>", "com.demo.Foo", "message")));
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertTrue(html.contains("<tr class='severity-&lt;img&gt;'><td>&lt;img&gt;</td><td>Foo</td><td>message</td></tr>"));
    }

    @Test
    @DisplayName("HTML 报告：叙事内容为 null 时不输出架构叙事区块")
    void generateHtml_nullStoryContent_shouldOmitNarrative() {
        InsightEntity story = insight("t", null, "0.5");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(story);

        String html = service.generateHtml(PROJECT_ID, USER_ID);

        assertFalse(html.contains("架构叙事"));
        assertFalse(html.contains("<div class='narrative'>"));
    }

    // ========== generatePdf ==========

    @Test
    @DisplayName("PDF 报告：完整数据生成有效 PDF，关键内容可提取")
    void generatePdf_fullData_shouldProduceExtractablePdf() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan());
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(
                violation("BLOCKER", "com.demo.account.PaymentService", "VIOMARKERA")));
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(
                insight("架构叙事", "STORYENDMARK 内容", "0.68"));

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        String text = extractPdfText(pdf);
        assertTrue(text.contains("CodeAtlas"), "标题应包含 CodeAtlas");
        assertTrue(text.contains("DemoProj"), "应包含项目名");
        assertTrue(text.contains("85.5/100"), "应包含健康度");
        assertTrue(text.contains("9642"), "应包含总类数");
        assertTrue(text.contains("8731"), "应包含总行数");
        assertTrue(text.contains("2.5"), "应包含扫描耗时");
        assertTrue(text.contains("PaymentService"), "违规类名应缩短后展示");
        assertTrue(text.contains("VIOMARKERA"), "应包含违规描述");
        assertTrue(text.contains("STORYENDMARK"), "应包含架构叙事内容");
    }

    @Test
    @DisplayName("PDF 报告：无扫描记录时仍生成 PDF 且跳过扫描统计")
    void generatePdf_noScan_shouldSkipStatsAndSucceed() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        String text = extractPdfText(pdf);
        assertTrue(text.contains("CodeAtlas"));
        assertFalse(text.contains("9642"), "无扫描时不应出现统计数据");
        verifyNoInteractions(violationMapper);
    }

    @Test
    @DisplayName("PDF 报告：扫描统计列为 null 时按 0 兜底")
    void generatePdf_nullScanStats_shouldFallbackToZero() {
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan);
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        assertTrue(extractPdfText(pdf).contains("0.0"));
    }

    @Test
    @DisplayName("PDF 报告：违规字段为 null 时以 - 兜底展示")
    void generatePdf_nullViolationFields_shouldRenderPlaceholders() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(scan());
        when(violationMapper.findByScanId(SCAN_ID)).thenReturn(Collections.singletonList(
                violation(null, null, null)));
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        assertTrue(extractPdfText(pdf).contains("CodeAtlas"));
    }

    @Test
    @DisplayName("PDF 报告：叙事超过 6000 字符时截断，尾部内容不出现")
    void generatePdf_storyOverSixThousand_shouldTruncate() {
        String content = "A".repeat(3000) + "B".repeat(3000) + "TAILMARK";
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(insight("t", content, "0.5"));

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        assertFalse(extractPdfText(pdf).contains("TAILMARK"), "超长叙事应被截断");
    }

    @Test
    @DisplayName("PDF 报告：叙事的标题/列表/空行混合内容正常渲染")
    void generatePdf_storyWithMarkdownLines_shouldRenderAllLineKinds() {
        String content = "# 一级\n## 二级\n- 列表项一\n* 列表项二\n普通段落 LINEENDMARK\n\n\n";
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(insight("t", content, "0.5"));

        byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

        assertIsPdf(pdf);
        String text = extractPdfText(pdf);
        assertTrue(text.contains("LINEENDMARK"), "正文段落应渲染");
        assertTrue(text.contains("列表项一"));
        assertTrue(text.contains("列表项二"));
    }

    @Test
    @DisplayName("PDF 生成内部异常：包装为 PDF 生成失败运行时异常")
    void generatePdf_internalFailure_shouldWrapRuntimeException() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);

        try (MockedStatic<PdfFontFactory> mocked = mockStatic(PdfFontFactory.class, invocation -> {
            throw new RuntimeException("font boom");
        })) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.generatePdf(PROJECT_ID, USER_ID));

            assertEquals("PDF 生成失败：无法创建 PDF 字体", ex.getMessage());
            assertNull(ex.getCause(), "包装异常不保留 cause（当前行为）");
        }
    }

    @Test
    @DisplayName("PDF 报告：全部字体文件路径失败时退回内置字体，PDF 仍成功产出")
    void generatePdf_allFontPathsFail_shouldFallBackToBuiltInFont() throws Exception {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("FallbackProj", "85.5"));
        when(scanMapper.findLatestByProjectId(PROJECT_ID)).thenReturn(null);
        when(insightService.getLatestArchStory(PROJECT_ID)).thenReturn(null);
        PdfFont builtInFont = PdfFontFactory.createFont();

        try (MockedStatic<PdfFontFactory> mocked = mockStatic(PdfFontFactory.class)) {
            mocked.when(() -> PdfFontFactory.createFont(anyString(), any(),
                            any(PdfFontFactory.EmbeddingStrategy.class)))
                    .thenThrow(new RuntimeException("字体文件不可用"));
            mocked.when(PdfFontFactory::createFont).thenReturn(builtInFont);

            byte[] pdf = service.generatePdf(PROJECT_ID, USER_ID);

            assertIsPdf(pdf);
            mocked.verify(() -> PdfFontFactory.createFont(), atLeastOnce());
        }
    }

    // ========== generateInsightPdf ==========

    @Test
    @DisplayName("洞察 PDF：标题/项目/时间/置信度与正文均可提取")
    void generateInsightPdf_fullData_shouldContainMetadataAndBody() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("InsightTitleALPHA", "正文 BODYMARKERZ", "0.875"));

        byte[] pdf = service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertIsPdf(pdf);
        String text = extractPdfText(pdf);
        assertTrue(text.contains("InsightTitleALPHA"));
        assertTrue(text.contains("DemoProj"));
        assertTrue(text.contains("2026-01-02 03:04:05"));
        assertTrue(text.contains("87%"));
        assertTrue(text.contains("BODYMARKERZ"));
    }

    @Test
    @DisplayName("洞察 PDF：无可导出报告时抛 404 业务异常")
    void generateInsightPdf_noInsight_shouldThrowNotFound() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals("暂无可导出的报告", ex.getMessage());
    }

    @Test
    @DisplayName("洞察 PDF：标题为 null 使用默认标题，且不输出空的时间/置信度")
    void generateInsightPdf_nullTitleAndMeta_shouldUseDefaultTitle() {
        InsightEntity entity = insight(null, "正文", null);
        entity.setCreatedAt(null);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE)).thenReturn(entity);

        byte[] pdf = service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertIsPdf(pdf);
        String text = extractPdfText(pdf);
        assertTrue(text.contains("分析报告"), "标题为空时使用默认标题");
        assertFalse(text.contains("生成时间"));
        assertFalse(text.contains("置信度"));
    }

    @Test
    @DisplayName("洞察 PDF：正文超过 15000 字符时截断，尾部内容不出现")
    void generateInsightPdf_contentOverLimit_shouldTruncate() {
        String content = "X".repeat(15000) + "TAILMARK";
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("t", content, "0.5"));

        byte[] pdf = service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertIsPdf(pdf);
        assertFalse(extractPdfText(pdf).contains("TAILMARK"), "超长正文应被截断");
    }

    @Test
    @DisplayName("洞察 PDF：正文为 null 时按空正文生成")
    void generateInsightPdf_nullContent_shouldStillGenerate() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("OnlyTitleMARK", null, "0.5"));

        byte[] pdf = service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertIsPdf(pdf);
        assertTrue(extractPdfText(pdf).contains("OnlyTitleMARK"));
    }

    @Test
    @DisplayName("洞察 PDF 生成内部异常：包装为 PDF 生成失败运行时异常")
    void generateInsightPdf_internalFailure_shouldWrapRuntimeException() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("t", "c", "0.5"));

        try (MockedStatic<PdfFontFactory> mocked = mockStatic(PdfFontFactory.class, invocation -> {
            throw new RuntimeException("font boom");
        })) {
            RuntimeException ex = assertThrows(RuntimeException.class,
                    () -> service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID));

            assertEquals("PDF 生成失败：无法创建 PDF 字体", ex.getMessage());
        }
    }

    @Test
    @DisplayName("洞察 PDF：项目不存在时抛 404（不查询洞察）")
    void generateInsightPdf_projectNotFound_shouldThrow() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateInsightPdf(PROJECT_ID, INSIGHT_TYPE, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verifyNoInteractions(insightService);
    }

    // ========== generateInsightHtml ==========

    @Test
    @DisplayName("洞察 HTML：完整数据逐字匹配（标题转义 + Markdown 渲染 + 生成时间）")
    void generateInsightHtml_fullData_shouldMatchExactly() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("My <Report>", "# Head\n\nText", "0.7"));

        String html = service.generateInsightHtml(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertEquals(INSIGHT_HEAD_PREFIX + "My &lt;Report&gt;" + INSIGHT_STYLE_BLOCK
                + "<h1>My &lt;Report&gt;</h1>"
                + "<p style='color:#999;'>项目：DemoProj · 生成时间：2026-01-02 03:04:05</p>"
                + "<div class='narrative'><h2>Head</h2><p>Text</p></div>"
                + "</body></html>", html);
    }

    @Test
    @DisplayName("洞察 HTML：标题为 null 使用默认标题，创建时间为 null 不输出时间")
    void generateInsightHtml_nullTitleAndCreatedAt_shouldUseDefaults() {
        InsightEntity entity = insight(null, "content", "0.5");
        entity.setCreatedAt(null);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE)).thenReturn(entity);

        String html = service.generateInsightHtml(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertTrue(html.startsWith(
                INSIGHT_HEAD_PREFIX + "分析报告" + INSIGHT_STYLE_BLOCK + "<h1>分析报告</h1>"));
        assertTrue(html.contains("<p style='color:#999;'>项目：DemoProj</p>"));
        assertFalse(html.contains("生成时间"));
    }

    @Test
    @DisplayName("洞察 HTML：正文为 null 时叙事区块为空")
    void generateInsightHtml_nullContent_shouldRenderEmptyNarrative() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE))
                .thenReturn(insight("Title", null, "0.5"));

        String html = service.generateInsightHtml(PROJECT_ID, INSIGHT_TYPE, USER_ID);

        assertTrue(html.contains("<div class='narrative'></div>"));
    }

    @Test
    @DisplayName("洞察 HTML：无可导出报告时抛 404 业务异常")
    void generateInsightHtml_noInsight_shouldThrowNotFound() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(insightService.getLatestByType(PROJECT_ID, INSIGHT_TYPE)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateInsightHtml(PROJECT_ID, INSIGHT_TYPE, USER_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        assertEquals("暂无可导出的报告", ex.getMessage());
    }

    @Test
    @DisplayName("洞察 HTML：非成员访问抛 404")
    void generateInsightHtml_notMember_shouldThrow() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(ownedProject("DemoProj", "85.5"));
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.generateInsightHtml(PROJECT_ID, INSIGHT_TYPE, OTHER_USER_ID));
    }

    // ========== 私有辅助方法 ==========

    @Test
    @DisplayName("toChineseSeverity：各种严重性映射，未知值原样返回，null 返回 -")
    void toChineseSeverity_shouldMapAllCases() {
        assertEquals("阻断", invoke("toChineseSeverity", "BLOCKER"));
        assertEquals("错误", invoke("toChineseSeverity", "ERROR"));
        assertEquals("警告", invoke("toChineseSeverity", "WARN"));
        assertEquals("建议", invoke("toChineseSeverity", "INFO"));
        assertEquals("警告", invoke("toChineseSeverity", "warn"), "大小写不敏感");
        assertEquals("custom", invoke("toChineseSeverity", "custom"), "未知严重性原样返回");
        assertEquals("-", invoke("toChineseSeverity", (String) null));
    }

    @Test
    @DisplayName("shorten：截取最后一段；无点或点开头时原样返回")
    void shorten_shouldStripPackage() {
        assertEquals("AService", invoke("shorten", "com.demo.AService"));
        assertEquals("NoDot", invoke("shorten", "NoDot"));
        assertEquals(".LeadingDot", invoke("shorten", ".LeadingDot"));
    }

    @Test
    @DisplayName("escapeHtml：五种字符全部转义，null 返回空串")
    void escapeHtml_shouldEscapeAllSpecialChars() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", invoke("escapeHtml", "&<>\"'"));
        assertEquals("", invoke("escapeHtml", (String) null));
    }

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Object argument) {
        return (T) ReflectionTestUtils.invokeMethod(service, methodName, argument);
    }

    // ========== 预期 HTML 前缀（复制自实现，用于逐字比对） ==========

    private static final String STYLE_MAIN =
            "<html><head><meta charset='UTF-8'><title>CodeAtlas 报告</title>"
            + "<style>body{font-family:-apple-system,'Segoe UI','Microsoft YaHei','PingFang SC',sans-serif;margin:40px;line-height:1.6;color:#2d3748;}h1{color:#1a365d;}"
            + "table{border-collapse:collapse;width:100%;}td,th{border:1px solid #ddd;padding:8px;}"
            + ".severity-BLOCKER{color:red;}.severity-ERROR{color:orange;}"
            + ".severity-WARN{color:#d48806;}.severity-INFO{color:#8c8c8c;}"
            + ".narrative h2,.narrative h3,.narrative h4{color:#1a365d;}"
            + "code{background:#f5f5f5;padding:1px 4px;border-radius:3px;}"
            + "</style></head><body>";

    // generateInsightHtml 的结构：<title>{escaped title}</title><style>...</style></head><body>
    private static final String INSIGHT_HEAD_PREFIX =
            "<html><head><meta charset='UTF-8'><title>";

    private static final String INSIGHT_STYLE_BLOCK =
            "</title>"
            + "<style>body{font-family:-apple-system,'Segoe UI','Microsoft YaHei','PingFang SC',sans-serif;margin:40px;line-height:1.8;color:#2d3748;max-width:900px;}"
            + "h1{color:#1a365d;}.narrative h2,.narrative h3,.narrative h4{color:#1a365d;}"
            + "code{background:#f5f5f5;padding:1px 4px;border-radius:3px;}"
            + "pre{background:#f5f5f5;padding:16px;border-radius:6px;overflow-x:auto;}"
            + "table{border-collapse:collapse;margin:16px 0;width:100%;}td,th{border:1px solid #ddd;padding:8px;}"
            + "blockquote{border-left:3px solid #1890ff;padding:4px 16px;margin:16px 0;color:#666;background:#fafafa;}"
            + "</style></head><body>";
}
