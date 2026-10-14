package com.codeatlas.server.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.codeatlas.server.entity.ClassSummaryEntity;
import com.codeatlas.server.service.HallucinationChecker.Action;
import com.codeatlas.server.service.HallucinationChecker.HallucinationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("HallucinationChecker 单元测试")
class HallucinationCheckerTest {

    private final HallucinationChecker checker = new HallucinationChecker();

    private ClassSummaryEntity classSummary(Long id, String fqn) {
        ClassSummaryEntity c = new ClassSummaryEntity();
        c.setId(id);
        c.setFqn(fqn);
        c.setSimpleName(fqn.substring(fqn.lastIndexOf('.') + 1));
        return c;
    }

    private List<ClassSummaryEntity> actualClasses() {
        return List.of(
                classSummary(1L, "com.demo.AService"),
                classSummary(2L, "com.demo.BService"));
    }

    private List<String> fakeFqns(int count) {
        List<String> fakes = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            fakes.add("com.fake.Unknown" + index);
        }
        return fakes;
    }

    @Test
    @DisplayName("输出为 null：返回无幻觉空列表")
    void check_nullOutput_shouldReturnNoHallucinationWithEmptyList() {
        HallucinationResult result = checker.check(null, actualClasses());

        assertFalse(result.hasHallucination());
        assertTrue(result.getFakeClasses().isEmpty());
    }

    @Test
    @DisplayName("输出为空串：返回无幻觉空列表")
    void check_emptyOutput_shouldReturnNoHallucinationWithEmptyList() {
        HallucinationResult result = checker.check("", actualClasses());

        assertFalse(result.hasHallucination());
        assertTrue(result.getFakeClasses().isEmpty());
    }

    @Test
    @DisplayName("全部引用真实 FQN：无幻觉")
    void check_allRealFqns_shouldNotFlag() {
        HallucinationResult result = checker.check(
                "com.demo.AService 调用 com.demo.BService", actualClasses());

        assertFalse(result.hasHallucination());
        assertTrue(result.getFakeClasses().isEmpty());
    }

    @Test
    @DisplayName("简名与真实类匹配：即使包名不同也跳过，只保留真正的虚构类")
    void check_simpleNameMatch_shouldSkipAndKeepRealFake() {
        HallucinationResult result = checker.check(
                "com.other.AService 与 com.fake.Unknown 出现了", actualClasses());

        assertFalse(result.hasHallucination());
        assertEquals(List.of("com.fake.Unknown"), result.getFakeClasses());
    }

    @Test
    @DisplayName("常见 JDK/框架类引用：全部跳过不计入虚构类")
    void check_commonLibraries_shouldSkipAll() {
        String output = "依赖 java.util.List、javax.sql.DataSource、jakarta.servlet.http.HttpServlet、"
                + "org.springframework.context.ApplicationContext、org.apache.commons.lang3.StringUtils、"
                + "com.fasterxml.jackson.databind.ObjectMapper、org.slf4j.Logger、lombok.Data";

        HallucinationResult result = checker.check(output, actualClasses());

        assertFalse(result.hasHallucination());
        assertTrue(result.getFakeClasses().isEmpty());
    }

    @Test
    @DisplayName("虚构类达到阈值 3：判定为幻觉")
    void check_fakesAtThreshold_shouldFlag() {
        HallucinationResult result = checker.check(String.join(" ", fakeFqns(3)), actualClasses());

        assertTrue(result.hasHallucination());
        assertEquals(3, result.getFakeClasses().size());
        assertEquals("com.fake.Unknown1", result.getFakeClasses().get(0));
    }

    @Test
    @DisplayName("虚构类低于阈值（2 个）：不判定为幻觉但仍记录引用")
    void check_fakesBelowThreshold_shouldNotFlagButRecord() {
        HallucinationResult result = checker.check(String.join(" ", fakeFqns(2)), actualClasses());

        assertFalse(result.hasHallucination());
        assertEquals(2, result.getFakeClasses().size());
    }

    @Test
    @DisplayName("虚构类超过 10 个：仍判定为幻觉并完整记录")
    void check_manyFakes_shouldFlagAndKeepAll() {
        HallucinationResult result = checker.check(String.join(" ", fakeFqns(12)), actualClasses());

        assertTrue(result.hasHallucination());
        assertEquals(12, result.getFakeClasses().size());
        assertEquals(IntStream.rangeClosed(1, 12).mapToObj(i -> "com.fake.Unknown" + i)
                .collect(Collectors.toList()), result.getFakeClasses());
    }

    @Test
    @DisplayName("建议动作：无幻觉 → PASS")
    void suggestAction_noHallucination_shouldPass() {
        assertEquals(Action.PASS, checker.suggestAction(new HallucinationResult(false, List.of())));
    }

    @Test
    @DisplayName("建议动作：虚构类不超过 5 → RETRY")
    void suggestAction_lightHallucination_shouldRetry() {
        assertEquals(Action.RETRY, checker.suggestAction(new HallucinationResult(true, fakeFqns(1))));
        assertEquals(Action.RETRY, checker.suggestAction(new HallucinationResult(true, fakeFqns(5))));
    }

    @Test
    @DisplayName("建议动作：虚构类超过 5 → DEGRADED")
    void suggestAction_severeHallucination_shouldDegrade() {
        assertEquals(Action.DEGRADED, checker.suggestAction(new HallucinationResult(true, fakeFqns(6))));
    }

    // ==================== 变异体杀灭补强（PIT survivors 定向，断言精确到值） ====================

    @Test
    @DisplayName("幻觉日志精确：达到阈值输出完整消息，未达阈值静默")
    void check_hallucinationLog_shouldBeExactAndGuarded() {
        Logger logger = (Logger) LoggerFactory.getLogger(HallucinationChecker.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            HallucinationResult flagged = checker.check(String.join(" ", fakeFqns(3)), actualClasses());
            assertTrue(flagged.hasHallucination());
            assertEquals(1, appender.list.size(), "达到阈值必须输出一条告警");
            assertEquals("Hallucination detected: 3 fake class references: "
                            + "[com.fake.Unknown1, com.fake.Unknown2, com.fake.Unknown3]",
                    appender.list.get(0).getFormattedMessage());

            appender.list.clear();
            HallucinationResult below = checker.check(String.join(" ", fakeFqns(2)), actualClasses());
            assertFalse(below.hasHallucination());
            assertTrue(appender.list.isEmpty(), "未达阈值不得输出幻觉告警");
        } finally {
            logger.detachAppender(appender);
        }
    }
}
