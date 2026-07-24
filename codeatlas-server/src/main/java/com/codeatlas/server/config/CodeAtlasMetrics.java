package com.codeatlas.server.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 业务指标收集器。
 * 使用 Micrometer 全局注册表，与 Prometheus / Actuator 集成。
 */
@Component
public class CodeAtlasMetrics {

    private final Counter scanTriggered;
    private final Counter scanCompleted;
    private final Counter scanFailed;
    private final Counter scanDurationSeconds;
    private final Counter aiCallsTotal;
    private final Counter aiCallsFailed;
    private final Counter aiTokensConsumed;
    private final Timer scanTimer;

    public CodeAtlasMetrics() {
        this.scanTriggered = Counter.builder("codeatlas.scan.triggered")
                .description("Total number of scans triggered")
                .register(Metrics.globalRegistry);

        this.scanCompleted = Counter.builder("codeatlas.scan.completed")
                .description("Total number of scans completed successfully")
                .register(Metrics.globalRegistry);

        this.scanFailed = Counter.builder("codeatlas.scan.failed")
                .description("Total number of scans that failed")
                .register(Metrics.globalRegistry);

        this.scanDurationSeconds = Counter.builder("codeatlas.scan.duration.seconds")
                .description("Total scan duration in seconds")
                .register(Metrics.globalRegistry);

        this.aiCallsTotal = Counter.builder("codeatlas.ai.calls.total")
                .description("Total number of AI API calls")
                .register(Metrics.globalRegistry);

        this.aiCallsFailed = Counter.builder("codeatlas.ai.calls.failed")
                .description("Total number of failed AI API calls")
                .register(Metrics.globalRegistry);

        this.aiTokensConsumed = Counter.builder("codeatlas.ai.tokens.consumed")
                .description("Total AI tokens consumed")
                .register(Metrics.globalRegistry);

        this.scanTimer = Timer.builder("codeatlas.scan.duration")
                .description("Scan execution time")
                .register(Metrics.globalRegistry);
    }

    // ---- Scan metrics ----

    public void recordScanTriggered() {
        scanTriggered.increment();
    }

    public void recordScanCompleted(long durationMs) {
        scanCompleted.increment();
        scanDurationSeconds.increment(durationMs / 1000.0);
        scanTimer.record(durationMs, TimeUnit.MILLISECONDS);
    }

    public void recordScanFailed() {
        scanFailed.increment();
    }

    // ---- AI call metrics ----

    public void recordAiCall() {
        aiCallsTotal.increment();
    }

    public void recordAiCallFailed() {
        aiCallsFailed.increment();
    }

    public void recordAiTokensConsumed(int tokens) {
        aiTokensConsumed.increment(tokens);
    }
}
