package com.codeatlas.server.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AsyncConfig 线程池配置测试")
class AsyncConfigTest {

    private final AsyncConfig config = new AsyncConfig();
    private final List<ThreadPoolTaskExecutor> executors = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executors.forEach(ThreadPoolTaskExecutor::shutdown);
        executors.clear();
    }

    private ThreadPoolTaskExecutor register(Executor executor) {
        ThreadPoolTaskExecutor taskExecutor = assertInstanceOf(ThreadPoolTaskExecutor.class, executor);
        executors.add(taskExecutor);
        return taskExecutor;
    }

    @Test
    @DisplayName("aiAnalysisExecutor：核心2/最大4/队列50/等待关停30s")
    void aiAnalysisExecutor_shouldMatchPoolConfiguration() {
        ThreadPoolTaskExecutor executor = register(config.aiAnalysisExecutor());

        assertEquals(2, executor.getCorePoolSize());
        assertEquals(4, executor.getMaxPoolSize());
        assertEquals(50, executor.getQueueCapacity());
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"));
        assertEquals(30_000L, ReflectionTestUtils.getField(executor, "awaitTerminationMillis"));
    }

    @Test
    @DisplayName("aiAnalysisExecutor：拒绝策略为 CallerRunsPolicy 且在调用线程执行并打日志")
    void aiAnalysisExecutor_shouldRunRejectedTaskInCallerThread() throws Exception {
        ThreadPoolTaskExecutor executor = register(config.aiAnalysisExecutor());
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        ThreadPoolExecutor.CallerRunsPolicy handler =
                assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, pool.getRejectedExecutionHandler());

        AtomicReference<Thread> executedOn = new AtomicReference<>();
        handler.rejectedExecution(() -> executedOn.set(Thread.currentThread()), pool);

        assertSame(Thread.currentThread(), executedOn.get(), "CallerRunsPolicy 必须在提交任务的调用者线程执行");
    }

    @Test
    @DisplayName("scanExecutor：核心1/最大3/队列10/等待关停60s")
    void scanExecutor_shouldMatchPoolConfiguration() {
        ThreadPoolTaskExecutor executor = register(config.scanExecutor());

        assertEquals(1, executor.getCorePoolSize());
        assertEquals(3, executor.getMaxPoolSize());
        assertEquals(10, executor.getQueueCapacity());
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"));
        assertEquals(60_000L, ReflectionTestUtils.getField(executor, "awaitTerminationMillis"));
    }

    @Test
    @DisplayName("scanExecutor：拒绝策略为 CallerRunsPolicy（日志带最大并发数）且调用线程执行")
    void scanExecutor_shouldRunRejectedTaskInCallerThread() {
        ThreadPoolTaskExecutor executor = register(config.scanExecutor());
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        ThreadPoolExecutor.CallerRunsPolicy handler =
                assertInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class, pool.getRejectedExecutionHandler());

        AtomicReference<Thread> executedOn = new AtomicReference<>();
        handler.rejectedExecution(() -> executedOn.set(Thread.currentThread()), pool);

        assertSame(Thread.currentThread(), executedOn.get());
        assertEquals(3, pool.getMaximumPoolSize());
    }

    @Test
    @DisplayName("auditExecutor：核心1/最大2/队列500/等待关停10s/丢弃最旧策略")
    void auditExecutor_shouldMatchPoolConfiguration() throws Exception {
        ThreadPoolTaskExecutor executor = register(config.auditExecutor());

        assertEquals(1, executor.getCorePoolSize());
        assertEquals(2, executor.getMaxPoolSize());
        assertEquals(500, executor.getQueueCapacity());
        assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"));
        assertEquals(10_000L, ReflectionTestUtils.getField(executor, "awaitTerminationMillis"));

        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();
        assertInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class, pool.getRejectedExecutionHandler());

        AtomicBoolean ran = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);
        pool.getRejectedExecutionHandler().rejectedExecution(() -> {
            ran.set(true);
            latch.countDown();
        }, pool);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "DiscardOldestPolicy 应把任务重新提交到线程池执行");
        assertTrue(ran.get());
    }

    @Test
    @DisplayName("三个执行器的线程名前缀分别为 ai-analysis- / scan-worker- / audit-")
    void executors_shouldUseDedicatedThreadNamePrefixes() throws Exception {
        ThreadPoolTaskExecutor aiExecutor = register(config.aiAnalysisExecutor());
        ThreadPoolTaskExecutor scanExecutor = register(config.scanExecutor());
        ThreadPoolTaskExecutor auditExecutor = register(config.auditExecutor());

        assertTrue(captureThreadName(aiExecutor).startsWith("ai-analysis-"));
        assertTrue(captureThreadName(scanExecutor).startsWith("scan-worker-"));
        assertTrue(captureThreadName(auditExecutor).startsWith("audit-"));
    }

    private static String captureThreadName(ThreadPoolTaskExecutor executor) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> name = new AtomicReference<>();
        executor.execute(() -> {
            name.set(Thread.currentThread().getName());
            latch.countDown();
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS), "任务应在 5 秒内被执行");
        return name.get();
    }
}
