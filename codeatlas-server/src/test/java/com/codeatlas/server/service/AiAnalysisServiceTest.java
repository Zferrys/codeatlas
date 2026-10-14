package com.codeatlas.server.service;

import com.codeatlas.engine.ai.AiClient;
import com.codeatlas.engine.ai.AiRequest;
import com.codeatlas.engine.ai.AiResponse;
import com.codeatlas.engine.ai.PromptTemplate;
import com.codeatlas.server.config.CodeAtlasMetrics;
import com.codeatlas.server.config.TokenBudgetManager;
import com.codeatlas.server.entity.AiAuditLogEntity;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.entity.InsightEntity;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.event.ScanProgressEvent;
import com.codeatlas.server.mapper.AiAuditLogMapper;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiAnalysisService 单元测试（分片路径 + 审计记账 + 降级语义）")
class AiAnalysisServiceTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long SCAN_ID = 100L;
    private static final String TASK_ID = "aitask-story-1";

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private ClassSummaryMapper classSummaryMapper;
    @Mock
    private InsightService insightService;
    @Mock
    private AiAuditLogMapper aiAuditLogMapper;
    @Mock
    private HallucinationChecker hallucinationChecker;
    @Mock
    private AlertService alertService;
    @Mock
    private TokenBudgetManager tokenBudgetManager;
    @Mock
    private CodeAtlasMetrics metrics;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private AiClient aiClient;

    private AiTaskProgressRegistry registry;
    private AiAnalysisService service;

    @BeforeEach
    void setUp() {
        registry = new AiTaskProgressRegistry(new ObjectMapper());
        service = buildService(aiClient);
    }

    private AiAnalysisService buildService(AiClient client) {
        AiAnalysisService svc = new AiAnalysisService(projectMapper, scanMapper, classSummaryMapper,
                insightService, aiAuditLogMapper, hallucinationChecker, alertService, tokenBudgetManager,
                metrics, eventPublisher, client, registry);
        ReflectionTestUtils.setField(svc, "self", svc);
        return svc;
    }

    private void stubProjectData(List<ClassSummaryEntity> classes) {
        Project project = new Project();
        project.setName("Demo");
        project.setLanguage(null);
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        scan.setTotalLines(500);
        when(scanMapper.findById(SCAN_ID)).thenReturn(scan);
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(classes);
    }

    private static ClassSummaryEntity classSummary(String fqn, String pkg) {
        ClassSummaryEntity cls = new ClassSummaryEntity();
        cls.setFqn(fqn);
        cls.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        cls.setPackageName(pkg);
        cls.setLayer("service");
        cls.setClassType("class");
        cls.setTotalMethods(3);
        cls.setLineCount(40);
        cls.setDependencies("[]");
        return cls;
    }

    private static List<ClassSummaryEntity> smallClasses() {
        return new ArrayList<>(Arrays.asList(
                classSummary("com.demo.AService", "com.demo"),
                classSummary("com.demo.BService", "com.demo"),
                classSummary("com.demo.CService", "com.demo")));
    }

    private static List<ClassSummaryEntity> chunkedClasses(int groups, int perGroup) {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int g = 0; g < groups; g++) {
            for (int i = 0; i < perGroup; i++) {
                classes.add(classSummary("pkg" + g + ".service.C" + g + i, "pkg" + g + ".service"));
            }
        }
        return classes;
    }

    private static AiResponse aiResponse(String content, int tokens) {
        AiResponse response = new AiResponse();
        response.setContent(content);
        response.setTokensUsed(tokens);
        response.setPromptTokens(tokens / 2);
        response.setCompletionTokens(tokens - tokens / 2);
        response.setLatencyMs(120);
        return response;
    }

    private AiAuditLogEntity captureAudit() {
        ArgumentCaptor<AiAuditLogEntity> captor = ArgumentCaptor.forClass(AiAuditLogEntity.class);
        verify(aiAuditLogMapper).insert(captor.capture());
        return captor.getValue();
    }

    private void assertAudit(AiAuditLogEntity audit, long promptTokens, long completionTokens,
                             long totalTokens, long latencyMs, String errorMessage, boolean success) {
        assertEquals("mock-model", audit.getModel());
        assertEquals("ARCHITECTURE_STORY", audit.getStage());
        assertEquals(PROJECT_ID, audit.getProjectId());
        assertNull(audit.getUserId());
        assertEquals(promptTokens, audit.getPromptTokens().intValue());
        assertEquals(completionTokens, audit.getCompletionTokens().intValue());
        assertEquals(totalTokens, audit.getTotalTokens().intValue());
        assertEquals(latencyMs, audit.getLatencyMs().intValue());
        assertEquals(errorMessage, audit.getErrorMessage());
        assertEquals(success, audit.getSuccess());
    }

    private static final class HallucinationResultStub {

        static HallucinationChecker.HallucinationResult none() {
            return new HallucinationChecker.HallucinationResult(false, Collections.emptyList());
        }

        static HallucinationChecker.HallucinationResult fake(List<String> fakes) {
            return new HallucinationChecker.HallucinationResult(true, fakes);
        }
    }

    // ========== 前置校验 ==========

    @Test
    @DisplayName("无 AI 客户端时直接返回 null，不触碰项目数据")
    void analyzeArchitecture_noClient_shouldReturnNull() {
        AiAnalysisService noAi = buildService(null);

        assertNull(noAi.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        verifyNoInteractions(projectMapper, scanMapper, classSummaryMapper, insightService,
                aiAuditLogMapper, tokenBudgetManager);
    }

    @Test
    @DisplayName("项目/扫描/类摘要任一缺失时跳过分析且不落审计")
    void analyzeArchitecture_missingData_shouldReturnNull() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(null);
        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        Project project = new Project();
        project.setName("Demo");
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project);
        when(scanMapper.findById(SCAN_ID)).thenReturn(null);
        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        ScanRecord scan = new ScanRecord();
        scan.setId(SCAN_ID);
        when(scanMapper.findById(SCAN_ID)).thenReturn(scan);
        when(classSummaryMapper.findByScanId(SCAN_ID)).thenReturn(Collections.emptyList());
        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        verify(aiClient, never()).chat(any(AiRequest.class));
        verifyNoInteractions(aiAuditLogMapper);
    }

    @Test
    @DisplayName("模板渲染为空（模板缺失防御路径）时跳过分析，不调用 AI 不落审计")
    void analyzeArchitecture_emptyPrompt_shouldReturnNull() throws Exception {
        stubProjectData(smallClasses());
        java.lang.reflect.Field cacheField = PromptTemplate.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, String> cache = (Map<String, String>) cacheField.get(null);
        String original = cache.put("prompts/architecture-story.md", "");
        try {
            assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));
        } finally {
            if (original != null) {
                cache.put("prompts/architecture-story.md", original);
            } else {
                cache.remove("prompts/architecture-story.md");
            }
        }
        verify(aiClient, never()).chat(any(AiRequest.class));
        verifyNoInteractions(aiAuditLogMapper, tokenBudgetManager);
    }

    // ========== 小项目主路径 ==========

    @Test
    @DisplayName("小项目成功路径：单次调用、提交点成功审计（含 cost）并保存 ARCH_STORY 洞察")
    void analyzeArchitecture_smallProject_shouldSaveInsight() throws Exception {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(aiResponse("架构叙事内容 AService", 800));
        when(hallucinationChecker.check(anyString(), anyList())).thenReturn(HallucinationResultStub.none());

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        AiAuditLogEntity audit = captureAudit();
        assertAudit(audit, 400, 400, 800, 120, null, true);
        assertEquals(new BigDecimal("0.0064"), audit.getCost());

        ArgumentCaptor<InsightEntity> insightCaptor = ArgumentCaptor.forClass(InsightEntity.class);
        verify(insightService).saveInsight(insightCaptor.capture());
        InsightEntity saved = insightCaptor.getValue();
        assertEquals(insight, saved);
        assertEquals("ARCH_STORY", saved.getType());
        assertEquals("架构叙事 — Demo", saved.getTitle());
        assertEquals("架构叙事内容 AService", saved.getContent());
        assertEquals(SCAN_ID, saved.getScanId());
        assertEquals(PROJECT_ID, saved.getProjectId());
        assertEquals("[]", saved.getSources());
        assertEquals(0, BigDecimal.valueOf(0.68).compareTo(saved.getConfidence()));
    }

    @Test
    @DisplayName("小项目空正文（纯空白）响应：落失败审计并记账已消耗 Token，返回 null")
    void analyzeArchitecture_blankContent_shouldAuditAndReturnNull() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse response = aiResponse("   ", 100);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(response);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        assertAudit(captureAudit(), 50, 50, 100, 120, "All AI models exhausted or no content", false);
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("调用前被终止：零消耗直接返回 null，不落审计不扣预算")
    void analyzeArchitecture_cancelledBeforeAiCall_shouldNotAudit() {
        stubProjectData(smallClasses());
        registry.subscribe(TASK_ID, PROJECT_ID);
        registry.cancel(TASK_ID);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        verify(aiClient, never()).chat(any(AiRequest.class));
        verifyNoInteractions(aiAuditLogMapper, tokenBudgetManager, insightService);
    }

    @Test
    @DisplayName("预算耗尽：落零 Token 失败审计，不调用 AI")
    void analyzeArchitecture_budgetExceeded_shouldAuditAndReturnNull() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(false);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        assertAudit(captureAudit(), 0, 0, 0, 0, "Monthly token budget exceeded", false);
        verify(aiClient, never()).chat(any(AiRequest.class));
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("AI 返回后被终止：结果丢弃、落失败审计（Cancelled by user），不保存洞察")
    void analyzeArchitecture_cancelledAfterAiCall_shouldAuditFailure() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            registry.cancel(TASK_ID);
            return aiResponse("架构叙事内容", 800);
        });

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 400, 400, 800, 120, "Cancelled by user", false);
        verifyNoInteractions(insightService);
    }

    // ========== 幻觉重试（Token 累进提交点审计） ==========

    @Test
    @DisplayName("幻觉重试成功：采用低温重试结果，主+重试 Token 一并进入提交点审计")
    void analyzeArchitecture_hallucinationRetryAdopted_shouldAccountBothCalls() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(aiResponse("引用了 com.fake.GhostA", 500), aiResponse("修正后的架构叙事", 300));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(HallucinationResultStub.fake(Collections.singletonList("com.fake.GhostA")),
                        HallucinationResultStub.none());
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("修正后的架构叙事", insight.getContent());
        assertAudit(captureAudit(), 400, 400, 800, 240, null, true);

        ArgumentCaptor<AiRequest> reqCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(2)).chat(reqCaptor.capture());
        AiRequest retryRequest = reqCaptor.getAllValues().get(1);
        assertEquals(0.1, retryRequest.getTemperature());
        assertEquals("引用了 com.fake.GhostA", retryRequest.getPrompt());
    }

    @Test
    @DisplayName("幻觉重试仍不合格：降级采用首轮结果，重试 Token 仍进审计行")
    void analyzeArchitecture_hallucinationRetryNotAdopted_shouldStillAccountRetryTokens() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(aiResponse("引用了 com.fake.GhostA", 500), aiResponse("仍引用 com.fake.GhostB", 300));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(HallucinationResultStub.fake(Collections.singletonList("com.fake.GhostA")),
                        HallucinationResultStub.fake(Collections.singletonList("com.fake.GhostB")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("引用了 com.fake.GhostA", insight.getContent());
        assertAudit(captureAudit(), 400, 400, 800, 240, null, true);
    }

    @Test
    @DisplayName("重试调用期间被终止：主+重试 Token 落失败审计，结果丢弃")
    void analyzeArchitecture_cancelledDuringRetry_shouldAuditBothCalls() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return aiResponse("引用了 com.fake.GhostA", 500);
            }
            registry.cancel(TASK_ID);
            return aiResponse("修正后的架构叙事", 300);
        });
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(HallucinationResultStub.fake(Collections.singletonList("com.fake.GhostA")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 400, 400, 800, 240, "Cancelled by user", false);
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("幻觉校验在检查点被终止（检查回调内取消）：不触发重试，主调用 Token 落失败审计")
    void analyzeArchitecture_cancelledDuringHallucinationCheck_shouldSkipRetry() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(aiResponse("引用了 com.fake.GhostA", 500));
        when(hallucinationChecker.check(anyString(), anyList())).thenAnswer(invocation -> {
            registry.cancel(TASK_ID);
            return HallucinationResultStub.fake(Collections.singletonList("com.fake.GhostA"));
        });
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 250, 250, 500, 120, "Cancelled by user", false);
        verify(aiClient, times(1)).chat(any(AiRequest.class));
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("幻觉校验自身抛异常：不阻塞交付，正常提交成功审计")
    void analyzeArchitecture_hallucinationCheckThrows_shouldStillDeliver() {
        stubProjectData(smallClasses());
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(aiResponse("架构叙事内容", 500));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenThrow(new RuntimeException("checker down"));

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("架构叙事内容", insight.getContent());
        assertAudit(captureAudit(), 250, 250, 500, 120, null, true);
    }

    // ========== 分片路径（>200 类） ==========

    @Test
    @DisplayName("分片成功：逐批分析后汇总，审计含全部分片与汇总 Token，标题带分片标记")
    void analyzeArchitecture_chunked_success() throws Exception {
        stubProjectData(chunkedClasses(3, 100));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(
                aiResponse("分析一", 100), aiResponse("分析二", 200), aiResponse("分析三", 300),
                aiResponse("总报告", 400));
        when(hallucinationChecker.check(anyString(), anyList())).thenReturn(HallucinationResultStub.none());

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        AiAuditLogEntity audit = captureAudit();
        assertAudit(audit, 500, 500, 1000, 480, null, true);
        assertEquals(new BigDecimal("0.008"), audit.getCost());

        ArgumentCaptor<InsightEntity> insightCaptor = ArgumentCaptor.forClass(InsightEntity.class);
        verify(insightService).saveInsight(insightCaptor.capture());
        InsightEntity saved = insightCaptor.getValue();
        assertEquals(insight, saved);
        assertEquals("架构叙事 — Demo [分片分析]", saved.getTitle());
        assertEquals("总报告", saved.getContent());
        JsonNode meta = new ObjectMapper().readTree(saved.getMetadata());
        assertEquals(1000, meta.get("tokensUsed").asLong());
        assertEquals(480, meta.get("latencyMs").asLong());
        assertEquals(300, meta.get("classCount").asLong());
        assertTrue(meta.get("chunked").asBoolean());

        ArgumentCaptor<AiRequest> reqCaptor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiClient, times(4)).chat(reqCaptor.capture());
        List<AiRequest> requests = reqCaptor.getAllValues();
        assertTrue(requests.get(0).getPrompt().contains("模块: pkg0"));
        assertTrue(requests.get(0).getPrompt().contains("pkg0.service.C00"));
        assertTrue(requests.get(2).getPrompt().contains("模块: pkg2"));
        assertTrue(requests.get(3).getPrompt().contains("个模块分析结果"));
    }

    @Test
    @DisplayName("全部分片空正文：整体失败并落审计，已消耗 Token 全部记账")
    void analyzeArchitecture_chunked_allChunksEmpty_shouldFailAndAudit() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(aiResponse(" ", 100), aiResponse("", 200));

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        assertAudit(captureAudit(), 150, 150, 300, 240, "Chunked analysis produced no content", false);
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("首个分片调用前已终止（零消耗）：不落审计行")
    void analyzeArchitecture_chunked_zeroCostCancel_shouldNotAudit() {
        stubProjectData(chunkedClasses(2, 101));
        registry.subscribe(TASK_ID, PROJECT_ID);
        registry.cancel(TASK_ID);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        verify(aiClient, never()).chat(any(AiRequest.class));
        verifyNoInteractions(aiAuditLogMapper, tokenBudgetManager, insightService);
    }

    @Test
    @DisplayName("分片中途被终止：已消耗 Token 落失败审计，不再调用后续分片")
    void analyzeArchitecture_chunked_cancelledMidway_shouldAuditConsumedTokens() {
        stubProjectData(chunkedClasses(3, 100));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return aiResponse("分析一", 100);
            }
            registry.cancel(TASK_ID);
            return aiResponse("分析二", 200);
        });

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 150, 150, 300, 240, "Cancelled by user", false);
        verify(aiClient, times(2)).chat(any(AiRequest.class));
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("最后一个分片返回后、汇总前被终止：分片 Token 落失败审计，不调用汇总")
    void analyzeArchitecture_chunked_cancelledBeforeSummary_shouldAuditConsumedTokens() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return aiResponse("分析一", 100);
            }
            registry.cancel(TASK_ID);
            return aiResponse("分析二", 200);
        });

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 150, 150, 300, 240, "Cancelled by user", false);
        verify(aiClient, times(2)).chat(any(AiRequest.class));
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("汇总调用期间被终止：全部 Token 落失败审计，结果丢弃")
    void analyzeArchitecture_chunked_cancelledDuringSummary_shouldAuditAllTokens() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        registry.subscribe(TASK_ID, PROJECT_ID);
        AtomicInteger calls = new AtomicInteger();
        when(aiClient.chat(any(AiRequest.class))).thenAnswer(invocation -> {
            int n = calls.incrementAndGet();
            if (n == 1) {
                return aiResponse("分析一", 100);
            }
            if (n == 2) {
                return aiResponse("分析二", 200);
            }
            registry.cancel(TASK_ID);
            return aiResponse("总报告", 400);
        });

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID, TASK_ID));

        assertAudit(captureAudit(), 350, 350, 700, 360, "Cancelled by user", false);
        verify(aiClient, times(3)).chat(any(AiRequest.class));
        verifyNoInteractions(insightService);
    }

    @Test
    @DisplayName("汇总调用失败（null）：降级为原始分片拼接交付，Token 不含失败的汇总")
    void analyzeArchitecture_chunked_summaryNull_shouldDegradeToRawChunks() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(aiResponse("分析一", 100), aiResponse("分析二", 200), null);

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("## pkg0\n\n分析一\n\n## pkg1\n\n分析二\n\n", insight.getContent());
        assertAudit(captureAudit(), 150, 150, 300, 240, null, true);
    }

    @Test
    @DisplayName("汇总返回空正文：与调用失败同判，降级原始拼接且汇总 Token 仍记账")
    void analyzeArchitecture_chunked_summaryBlank_shouldDegradeToRawChunks() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(aiResponse("分析一", 100), aiResponse("分析二", 200), aiResponse("   ", 400));

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("## pkg0\n\n分析一\n\n## pkg1\n\n分析二\n\n", insight.getContent());
        assertAudit(captureAudit(), 350, 350, 700, 360, null, true);
    }

    @Test
    @DisplayName("首个分片前预算耗尽：落零 Token 失败审计且不调用 AI")
    void analyzeArchitecture_chunked_budgetExceededBeforeFirstChunk_shouldAudit() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(false);

        assertNull(service.analyzeArchitecture(PROJECT_ID, SCAN_ID));

        assertAudit(captureAudit(), 0, 0, 0, 0, "Monthly token budget exceeded", false);
        verify(aiClient, never()).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("分片中途预算耗尽：已有分片直接拼接交付")
    void analyzeArchitecture_chunked_budgetExceededMidway_shouldUseRawChunks() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true, false, false);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(aiResponse("分析一", 100));

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("## pkg0\n\n分析一\n\n", insight.getContent());
        assertAudit(captureAudit(), 50, 50, 100, 120, null, true);
        verify(aiClient, times(1)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("分组数超过上限：只处理前 9 组，其余截断后照常汇总")
    void analyzeArchitecture_chunked_tooManyGroups_shouldCapAtNineChunks() {
        stubProjectData(chunkedClasses(10, 30));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        AiResponse chunk = aiResponse("分片分析内容", 100);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(chunk, chunk, chunk, chunk, chunk, chunk, chunk, chunk, chunk, aiResponse("总报告", 400));
        when(hallucinationChecker.check(anyString(), anyList())).thenReturn(HallucinationResultStub.none());

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("总报告", insight.getContent());
        assertAudit(captureAudit(), 650, 650, 1300, 1200, null, true);
        verify(aiClient, times(10)).chat(any(AiRequest.class));
    }

    @Test
    @DisplayName("分片项目检出幻觉：类数超限不触发重试，照常提交（重试条件为小项目专属）")
    void analyzeArchitecture_chunked_hallucinationNoRetry() {
        stubProjectData(chunkedClasses(2, 101));
        when(aiClient.getModelName()).thenReturn("mock-model");
        when(tokenBudgetManager.tryConsume(anyLong())).thenReturn(true);
        when(aiClient.chat(any(AiRequest.class)))
                .thenReturn(aiResponse("分析一", 100), aiResponse("分析二", 200),
                        aiResponse("总报告引用 com.fake.Ghost", 400));
        when(hallucinationChecker.check(anyString(), anyList()))
                .thenReturn(HallucinationResultStub.fake(Collections.singletonList("com.fake.Ghost")));
        when(hallucinationChecker.suggestAction(any(HallucinationChecker.HallucinationResult.class)))
                .thenReturn(HallucinationChecker.Action.RETRY);

        InsightEntity insight = service.analyzeArchitecture(PROJECT_ID, SCAN_ID);

        assertEquals("总报告引用 com.fake.Ghost", insight.getContent());
        assertAudit(captureAudit(), 350, 350, 700, 360, null, true);
        verify(aiClient, times(3)).chat(any(AiRequest.class));
    }

    // ========== callAiWithResilience 边界 ==========

    @Test
    @DisplayName("AI 客户端返回 null：按无响应处理（不 NPE），不记录成功指标")
    void callAiWithResilience_nullResponse_shouldReturnNull() {
        when(aiClient.chat(any(AiRequest.class))).thenReturn(null);

        assertNull(service.callAiWithResilience(new AiRequest()));

        verify(metrics, never()).recordAiCall();
        verify(metrics, never()).recordAiTokensConsumed(anyInt());
    }

    @Test
    @DisplayName("响应缺 latency 时回填；tokensUsed 缺失时按 prompt+completion 记账")
    void callAiWithResilience_shouldFillLatencyAndRecordTokens() {
        AiResponse response = new AiResponse();
        response.setContent("ok");
        response.setLatencyMs(0);
        response.setPromptTokens(100);
        response.setCompletionTokens(50);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(response);

        AiResponse result = service.callAiWithResilience(new AiRequest());

        assertTrue(result.getLatencyMs() >= 0);
        verify(metrics).recordAiCall();
        verify(metrics).recordAiTokensConsumed(150);
    }

    @Test
    @DisplayName("tokensUsed 与 prompt/completion 全为 0 时不记录 Token 指标")
    void callAiWithResilience_zeroTokens_shouldNotRecordTokens() {
        AiResponse response = new AiResponse();
        response.setContent("ok");
        response.setLatencyMs(10);
        when(aiClient.chat(any(AiRequest.class))).thenReturn(response);

        service.callAiWithResilience(new AiRequest());

        verify(metrics).recordAiCall();
        verify(metrics, never()).recordAiTokensConsumed(anyInt());
    }

    @Test
    @DisplayName("熔断降级：返回 null、记录失败指标并告警")
    void aiFallback_shouldRecordFailureAndAlert() {
        assertNull(service.aiFallback(new AiRequest(), new RuntimeException("circuit open")));

        verify(metrics).recordAiCallFailed();
        verify(alertService).sendAlert(eq("AI 模型全部不可用"), contains("circuit open"));
    }

    // ========== 异步触发与事件 ==========

    @Test
    @DisplayName("triggerAsync：无 AI 客户端时仍发布开始/完成事件并回收运行态")
    void triggerAsync_noClient_shouldPublishStartAndComplete() {
        AiAnalysisService noAi = buildService(null);

        noAi.triggerAsync(PROJECT_ID, SCAN_ID);

        ArgumentCaptor<ScanProgressEvent> captor = ArgumentCaptor.forClass(ScanProgressEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        assertEquals("AI_STARTED", captor.getAllValues().get(0).getStage());
        assertEquals("AI_COMPLETED", captor.getAllValues().get(1).getStage());
        assertFalse(noAi.isAiAnalysisRunning(PROJECT_ID));
    }

    @Test
    @DisplayName("triggerAsync：分析抛异常时发布 AI_FAILED 事件并回收运行态")
    void triggerAsync_analyzeThrows_shouldPublishFailed() {
        when(projectMapper.findById(PROJECT_ID)).thenThrow(new RuntimeException("db down"));

        service.triggerAsync(PROJECT_ID, SCAN_ID);

        ArgumentCaptor<ScanProgressEvent> captor = ArgumentCaptor.forClass(ScanProgressEvent.class);
        verify(eventPublisher, times(2)).publishEvent(captor.capture());
        ScanProgressEvent failed = captor.getAllValues().get(1);
        assertEquals("AI_FAILED", failed.getStage());
        assertTrue(failed.getMessage().contains("db down"));
        assertFalse(service.isAiAnalysisRunning(PROJECT_ID));
    }

    @Test
    @DisplayName("事件发布自身异常：静默降级，不影响任务收尾")
    void triggerAsync_eventPublishFails_shouldSwallow() {
        AiAnalysisService noAi = buildService(null);
        doThrow(new RuntimeException("sse down")).when(eventPublisher).publishEvent(any());

        noAi.triggerAsync(PROJECT_ID, SCAN_ID);

        verify(projectMapper, never()).findById(any());
        assertFalse(noAi.isAiAnalysisRunning(PROJECT_ID));
    }

    // ========== 提示词变量组装 ==========

    @Test
    @DisplayName("buildPromptVariables：忽略未知依赖、仅保留项目内依赖高亮")
    void buildPromptVariables_shouldBuildLayerAndDependencyHighlights() {
        ClassSummaryEntity a = classSummary("com.demo.AService", "com.demo");
        a.setLayer("controller");
        a.setDependencies("[\"com.demo.BService\",\"ext.Outside\"]");
        ClassSummaryEntity b = classSummary("com.demo.BService", "com.demo");
        ClassSummaryEntity c = classSummary("com.demo.CService", "com.demo");
        c.setLayer(null);

        Project project = new Project();
        project.setName("Demo");
        ScanRecord scan = new ScanRecord();
        scan.setTotalLines(1234);

        Map<String, String> vars = service.buildPromptVariables(project, scan,
                new ArrayList<>(Arrays.asList(a, b, c)));

        assertEquals("Demo", vars.get("projectName"));
        assertEquals("Java", vars.get("language"));
        assertEquals("3", vars.get("classCount"));
        assertEquals("1234", vars.get("totalLines"));
        assertTrue(vars.get("layerDistribution").contains("- controller: 1 classes"));
        assertTrue(vars.get("layerDistribution").contains("- service: 1 classes"));
        assertEquals("AService → BService\n", vars.get("dependencyHighlights"));
        assertTrue(vars.get("keyClasses").contains("com.demo.AService"));
        assertTrue(vars.get("keyClasses").contains("2 deps"));
    }

    @Test
    @DisplayName("buildPromptVariables：依赖高亮最多 10 条")
    void buildPromptVariables_shouldCapDependencyHighlightsAtTen() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        ClassSummaryEntity root = classSummary("com.demo.Root", "com.demo");
        classes.add(root);
        for (int i = 1; i <= 12; i++) {
            ClassSummaryEntity cls = classSummary("com.demo.Dep" + i, "com.demo");
            cls.setDependencies("[\"com.demo.Root\"]");
            classes.add(cls);
        }
        Project project = new Project();
        project.setName("Demo");
        ScanRecord scan = new ScanRecord();
        scan.setTotalLines(1);

        Map<String, String> vars = service.buildPromptVariables(project, scan, classes);

        String highlights = vars.get("dependencyHighlights");
        assertEquals(10, highlights.split("\n").length);
    }

    @Test
    @DisplayName("parseDeps：合法 JSON 解析为列表，非法/空值降级为空列表")
    void parseDeps_shouldHandleInvalidInput() {
        assertEquals(Arrays.asList("com.a.B", "com.c.D"), service.parseDeps("[\"com.a.B\",\"com.c.D\"]"));
        assertEquals(0, service.parseDeps(null).size());
        assertEquals(0, service.parseDeps("").size());
        assertEquals(0, service.parseDeps("[]").size());
        assertEquals(0, service.parseDeps("not-json").size());
    }

    @Test
    @DisplayName("shorten：取 FQN 最后一段；无包名前缀时原样返回")
    void shorten_shouldStripPackage() {
        assertEquals("AService", service.shorten("com.demo.AService"));
        assertEquals("NoDot", service.shorten("NoDot"));
    }

    // ========== 置信度 ==========

    @Test
    @DisplayName("置信度：null/空内容一律 0.50")
    void calculateConfidence_blankContent_shouldReturnBaseline() {
        assertEquals(0, BigDecimal.valueOf(0.50).compareTo(service.calculateConfidenceSimple(null, smallClasses())));
        assertEquals(0, BigDecimal.valueOf(0.50).compareTo(service.calculateConfidenceSimple("", smallClasses())));
    }

    @Test
    @DisplayName("置信度：类名命中分档 8/4/1/0，长度加分 1500/600 分档")
    void calculateConfidence_matchAndLengthTiers() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            classes.add(classSummary("com.demo.Cls" + (char) ('A' + i), "com.demo"));
        }
        String eight = "ClsA ClsB ClsC ClsD ClsE ClsF ClsG ClsH";
        assertEquals(0, BigDecimal.valueOf(0.75).compareTo(service.calculateConfidenceSimple(eight, classes)));

        String seven = "ClsA ClsB ClsC ClsD ClsE ClsF ClsG";
        assertEquals(0, BigDecimal.valueOf(0.69).compareTo(service.calculateConfidenceSimple(seven, classes)));

        String four = "ClsA ClsB ClsC ClsD";
        assertEquals(0, BigDecimal.valueOf(0.69).compareTo(service.calculateConfidenceSimple(four, classes)));

        String three = "ClsA ClsB ClsC";
        assertEquals(0, BigDecimal.valueOf(0.63).compareTo(service.calculateConfidenceSimple(three, classes)));

        String one = "ClsA";
        assertEquals(0, BigDecimal.valueOf(0.63).compareTo(service.calculateConfidenceSimple(one, classes)));

        String none = "没有命中任何项目类";
        assertEquals(0, BigDecimal.valueOf(0.55).compareTo(service.calculateConfidenceSimple(none, classes)));

        String oneLong = "ClsA " + repeat('x', 1000);
        assertEquals(0, BigDecimal.valueOf(0.69).compareTo(service.calculateConfidenceSimple(oneLong, classes)));

        String oneVeryLong = "ClsA " + repeat('x', 1600);
        assertEquals(0, BigDecimal.valueOf(0.75).compareTo(service.calculateConfidenceSimple(oneVeryLong, classes)));
    }

    @Test
    @DisplayName("置信度：不确定措辞各变体均扣分（下限 0.50）")
    void calculateConfidence_uncertaintyWords_shouldReduceScore() {
        for (String word : Arrays.asList("不确定", "无法确定", "unsure", "cannot determine", "可能")) {
            assertEquals(0, BigDecimal.valueOf(0.50).compareTo(
                    service.calculateConfidenceSimple("没有命中 " + word, smallClasses())),
                    "措辞: " + word);
        }
    }

    @Test
    @DisplayName("置信度：架构术语各变体均加分")
    void calculateConfidence_architectureWords_shouldBoostScore() {
        for (String word : Arrays.asList("分层", "依赖", "耦合", "设计模式", "架构")) {
            assertEquals(0, BigDecimal.valueOf(0.60).compareTo(
                    service.calculateConfidenceSimple("没有命中 " + word, smallClasses())),
                    "术语: " + word);
        }
    }

    @Test
    @DisplayName("置信度：全部加分项叠加时封顶于可达上限 0.92")
    void calculateConfidence_maxBoost_shouldReachReachableCap() {
        List<ClassSummaryEntity> classes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            classes.add(classSummary("com.demo.Cls" + (char) ('A' + i), "com.demo"));
        }
        String content = "ClsA ClsB ClsC ClsD ClsE ClsF ClsG ClsH 架构 " + repeat('x', 1600);
        assertEquals(0, BigDecimal.valueOf(0.92).compareTo(service.calculateConfidenceSimple(content, classes)));
    }

    private static String repeat(char c, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
