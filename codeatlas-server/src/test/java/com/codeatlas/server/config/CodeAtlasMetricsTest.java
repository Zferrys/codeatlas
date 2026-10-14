package com.codeatlas.server.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("CodeAtlasMetrics 单元测试（Micrometer 真实注册表，计数/计时精确断言）")
class CodeAtlasMetricsTest {

    private SimpleMeterRegistry registry;
    private CodeAtlasMetrics metrics;

    @BeforeEach
    void setUp() {
        // 全局注册表是静态的：先清掉历史 meter，再挂上可断言的简单注册表
        removeCodeAtlasMeters();
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        metrics = new CodeAtlasMetrics();
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
        removeCodeAtlasMeters();
    }

    private static void removeCodeAtlasMeters() {
        for (Meter meter : List.copyOf(Metrics.globalRegistry.getMeters())) {
            if (meter.getId().getName().startsWith("codeatlas.")) {
                Metrics.globalRegistry.remove(meter);
            }
        }
    }

    @Test
    @DisplayName("构造：注册全部 8 个指标且名称/描述精确")
    void constructor_registersAllMetersWithExactNameAndDescription() {
        assertEquals("Total number of scans triggered",
                registry.get("codeatlas.scan.triggered").counter().getId().getDescription());
        assertEquals("Total number of scans completed successfully",
                registry.get("codeatlas.scan.completed").counter().getId().getDescription());
        assertEquals("Total number of scans that failed",
                registry.get("codeatlas.scan.failed").counter().getId().getDescription());
        assertEquals("Total scan duration in seconds",
                registry.get("codeatlas.scan.duration.seconds").counter().getId().getDescription());
        assertEquals("Total number of AI API calls",
                registry.get("codeatlas.ai.calls.total").counter().getId().getDescription());
        assertEquals("Total number of failed AI API calls",
                registry.get("codeatlas.ai.calls.failed").counter().getId().getDescription());
        assertEquals("Total AI tokens consumed",
                registry.get("codeatlas.ai.tokens.consumed").counter().getId().getDescription());
        assertEquals("Scan execution time",
                registry.get("codeatlas.scan.duration").timer().getId().getDescription());
    }

    @Test
    @DisplayName("recordScanTriggered：每次累加 1")
    void recordScanTriggered_incrementsByOne() {
        metrics.recordScanTriggered();

        assertEquals(1.0, registry.get("codeatlas.scan.triggered").counter().count());

        metrics.recordScanTriggered();

        assertEquals(2.0, registry.get("codeatlas.scan.triggered").counter().count());
    }

    @Test
    @DisplayName("recordScanCompleted：次数 +1，秒数按 1000.0 折算（2500ms -> 2.5s），计时器记录毫秒")
    void recordScanCompleted_recordsCountSecondsAndTimer() {
        metrics.recordScanCompleted(2500L);

        assertEquals(1.0, registry.get("codeatlas.scan.completed").counter().count());
        assertEquals(2.5, registry.get("codeatlas.scan.duration.seconds").counter().count());

        Timer timer = registry.get("codeatlas.scan.duration").timer();
        assertEquals(1L, timer.count());
        assertEquals(2500.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("recordScanCompleted：0ms 时秒数计 0")
    void recordScanCompleted_zeroDuration() {
        metrics.recordScanCompleted(0L);

        assertEquals(0.0, registry.get("codeatlas.scan.duration.seconds").counter().count());
        assertEquals(0.0, registry.get("codeatlas.scan.duration").timer().totalTime(TimeUnit.MILLISECONDS), 0.001);
    }

    @Test
    @DisplayName("recordScanFailed：只累加失败计数")
    void recordScanFailed_incrementsFailedCounter() {
        metrics.recordScanFailed();

        assertEquals(1.0, registry.get("codeatlas.scan.failed").counter().count());
        assertEquals(0.0, registry.get("codeatlas.scan.completed").counter().count());
    }

    @Test
    @DisplayName("recordAiCall / recordAiCallFailed：分别累加对应计数")
    void recordAiCall_countsTotalAndFailedSeparately() {
        metrics.recordAiCall();
        metrics.recordAiCall();
        metrics.recordAiCallFailed();

        assertEquals(2.0, registry.get("codeatlas.ai.calls.total").counter().count());
        assertEquals(1.0, registry.get("codeatlas.ai.calls.failed").counter().count());
    }

    @Test
    @DisplayName("recordAiTokensConsumed：按传入 token 数累加（而非次数）")
    void recordAiTokensConsumed_addsTokenAmount() {
        metrics.recordAiTokensConsumed(150);
        metrics.recordAiTokensConsumed(50);

        assertEquals(200.0, registry.get("codeatlas.ai.tokens.consumed").counter().count());
    }
}
