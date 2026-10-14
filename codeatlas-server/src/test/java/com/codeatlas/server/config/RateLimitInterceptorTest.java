package com.codeatlas.server.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RateLimitInterceptor 单元测试（Redis 优先 + 内存滑动窗口降级 + IP 信任链）")
class RateLimitInterceptorTest {

    private static final String RATE_LIMIT_BODY =
            "{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\",\"data\":null}";

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        removeRateLimitMeters();
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
        removeRateLimitMeters();
    }

    private static void removeRateLimitMeters() {
        for (Meter meter : List.copyOf(Metrics.globalRegistry.getMeters())) {
            if (meter.getId().getName().startsWith("ratelimit.")) {
                Metrics.globalRegistry.remove(meter);
            }
        }
    }

    private double counterCount(String source) {
        Counter counter = registry.find("ratelimit.hit.total").tag("source", source).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRemoteAddr("203.0.113.9");
        return request;
    }

    private static void assertRateLimited(MockHttpServletResponse response) throws Exception {
        assertEquals(429, response.getStatus());
        assertEquals("application/json;charset=UTF-8", response.getContentType());
        assertEquals(RATE_LIMIT_BODY, response.getContentAsString());
    }

    // ---------- Redis 路径 ----------

    @Test
    @DisplayName("Redis 限流放行：key 归一化 + 计数不增加 + 响应无副作用")
    void redisPath_allowed_delegatesWithNormalizedKey() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire("10.0.0.1:/api/v1/projects/{id}", 5, 60_000L)).thenReturn(true);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(5, limiter);
        MockHttpServletRequest request = request("/api/v1/projects/12");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));

        verify(limiter).tryAcquire("10.0.0.1:/api/v1/projects/{id}", 5, 60_000L);
        assertEquals(0.0, counterCount("redis"));
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("Redis 限流拒绝：返回 429 + 统一 JSON 体 + redis 计数 +1")
    void redisPath_rejected_returns429AndCountsHit() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire("10.0.0.1:/api/v1/projects/{id}", 3, 60_000L)).thenReturn(false);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(3, limiter);
        MockHttpServletRequest request = request("/api/v1/projects/12");
        request.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertRateLimited(response);
        assertEquals(1.0, counterCount("redis"));
        assertEquals(0.0, counterCount("memory"));
    }

    // ---------- 内存降级路径 ----------

    @Test
    @DisplayName("内存限流：恰好达到上限仍放行，超出后 429（边界语义 > limit）")
    void memoryPath_allowsUpToLimit_thenRejects() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(3);
        MockHttpServletRequest request = request("/api/v1/projects/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(0.0, counterCount("memory"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertRateLimited(response);
        assertEquals(1.0, counterCount("memory"));
    }

    @Test
    @DisplayName("内存限流：数字路径段归一化后共享同一窗口（/1 与 /2 同 key）")
    void memoryPath_numericSegmentsShareWindow() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(2);
        MockHttpServletRequest first = request("/api/v1/projects/1");
        MockHttpServletRequest second = request("/api/v1/projects/2");
        MockHttpServletRequest third = request("/api/v1/projects/3");
        MockHttpServletRequest otherPath = request("/api/v1/scans");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, response, new Object()));
        assertTrue(interceptor.preHandle(second, response, new Object()));
        assertFalse(interceptor.preHandle(third, response, new Object()));

        // 不同 key 的窗口互不影响
        assertTrue(interceptor.preHandle(otherPath, response, new Object()));
    }

    @Test
    @DisplayName("IP 信任链：X-Real-IP 优先且 trim 后使用")
    void clientIp_xRealIpPreferredAndTrimmed() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest first = request("/api/x");
        first.addHeader("X-Real-IP", " 9.9.9.9 ");
        first.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest second = request("/api/x");
        second.addHeader("X-Real-IP", "9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, response, new Object()));
        assertFalse(interceptor.preHandle(second, response, new Object()));
    }

    @Test
    @DisplayName("IP 信任链：无 X-Real-IP 时取 X-Forwarded-For 最后一段并 trim")
    void clientIp_xForwardedForUsesLastSegment() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest first = request("/api/x");
        first.addHeader("X-Forwarded-For", "client-a, proxy-a, 3.3.3.3");
        MockHttpServletRequest second = request("/api/x");
        second.addHeader("X-Forwarded-For", "client-b,proxy-a,3.3.3.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, response, new Object()));
        assertFalse(interceptor.preHandle(second, response, new Object()));
    }

    @Test
    @DisplayName("IP 信任链：畸形 X-Forwarded-For（逗号/空白）回落 remoteAddr（fail-closed）")
    void clientIp_malformedForwardedForFallsBackToRemoteAddr() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest first = request("/api/x");
        first.setRemoteAddr("7.7.7.7");
        first.addHeader("X-Forwarded-For", ",");
        MockHttpServletRequest second = request("/api/x");
        second.setRemoteAddr("7.7.7.7");
        second.addHeader("X-Real-IP", "   ");
        second.addHeader("X-Forwarded-For", " , ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, response, new Object()));
        assertFalse(interceptor.preHandle(second, response, new Object()));
    }

    @Test
    @DisplayName("IP 信任链：无任何代理头时使用 remoteAddr")
    void clientIp_plainRemoteAddr() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest first = request("/api/x");
        MockHttpServletRequest second = request("/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(first, response, new Object()));
        assertFalse(interceptor.preHandle(second, response, new Object()));
    }

    @Test
    @DisplayName("滑动窗口：过期时间戳在计数前被裁剪，不占用配额")
    void slidingWindow_expiredTimestampsPrunedBeforeCounting() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(2);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> deque = new ConcurrentLinkedDeque<>();
        deque.addLast(now - 61_000L);
        deque.addLast(now - 61_000L);
        windows.put("203.0.113.9:/api/x", deque);

        assertTrue(interceptor.preHandle(request("/api/x"), new MockHttpServletResponse(), new Object()));

        assertEquals(1, windows.get("203.0.113.9:/api/x").size());
    }

    @Test
    @DisplayName("滑动窗口：未过期时间戳保留并参与计数（窗口内累计超限即拒绝）")
    void slidingWindow_freshTimestampsKeptAndCounted() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> deque = new ConcurrentLinkedDeque<>();
        deque.addLast(now - 1000L);
        windows.put("203.0.113.9:/api/x", deque);

        assertFalse(interceptor.preHandle(request("/api/x"), new MockHttpServletResponse(), new Object()));

        assertEquals(2, windows.get("203.0.113.9:/api/x").size());
        assertEquals(1.0, counterCount("memory"));
    }

    @Test
    @DisplayName("定期清理：超过清理间隔后移除过期 key、保留近期 key，并刷新清理时间")
    void periodicCleanup_removesStaleKeysKeepsRecent() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> stale = new ConcurrentLinkedDeque<>();
        stale.addLast(now - 600_000L);
        windows.put("stale:key", stale);
        // 90 秒前：超过单个窗口（60s）但在两个窗口（120s）保留期内 —— 用于区分 2x 保留期语义
        ConcurrentLinkedDeque<Long> recent = new ConcurrentLinkedDeque<>();
        recent.addLast(now - 90_000L);
        windows.put("recent:key", recent);
        ReflectionTestUtils.setField(interceptor, "lastCleanupTime", now - 300_001L);

        assertTrue(interceptor.preHandle(request("/api/x"), new MockHttpServletResponse(), new Object()));

        assertFalse(windows.containsKey("stale:key"));
        assertTrue(windows.containsKey("recent:key"));
        assertEquals(1, windows.get("recent:key").size());
        assertTrue((long) ReflectionTestUtils.getField(interceptor, "lastCleanupTime") >= now);
    }

    @Test
    @DisplayName("定期清理：未到清理间隔时不做全量清理")
    void periodicCleanup_notTriggeredBeforeInterval() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(100);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> stale = new ConcurrentLinkedDeque<>();
        stale.addLast(now - 600_000L);
        windows.put("stale:key", stale);

        assertTrue(interceptor.preHandle(request("/api/x"), new MockHttpServletResponse(), new Object()));

        assertTrue(windows.containsKey("stale:key"));
        assertEquals(1, windows.get("stale:key").size());
    }

    @Test
    @DisplayName("容量保护：队列达到 200 上限后直接拒绝（防内存膨胀）")
    void queueOverflow_rejectsAtCapacityGuard() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1000);
        MockHttpServletRequest request = request("/api/x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        for (int i = 0; i < 200; i++) {
            assertTrue(interceptor.preHandle(request, response, new Object()), "第 " + (i + 1) + " 次应放行");
        }
        assertEquals(0.0, counterCount("memory"));

        assertFalse(interceptor.preHandle(request, response, new Object()));

        assertRateLimited(response);
        assertEquals(1.0, counterCount("memory"));
    }

    @Test
    @DisplayName("Redis 路径不触碰内存窗口（redisRateLimiter 非空时纯委托）")
    void redisPath_doesNotUseMemoryWindows() throws Exception {
        RedisRateLimiter limiter = mock(RedisRateLimiter.class);
        when(limiter.tryAcquire("203.0.113.9:/api/x", 1, 60_000L)).thenReturn(true);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1, limiter);
        MockHttpServletRequest request = request("/api/x");

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        assertTrue(windows.isEmpty());
    }

    @Test
    @DisplayName("单参构造：纯内存模式（无 Redis 依赖）")
    void legacyConstructor_memoryOnlyMode() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        assertNotNull(ReflectionTestUtils.getField(interceptor, "windows"));
        assertEquals(1, ReflectionTestUtils.getField(interceptor, "maxRequests"));
        assertEquals(60_000L, ReflectionTestUtils.getField(interceptor, "windowMs"));
    }

    // ---------- 覆盖率补强：代理头分支生效性与窗口边界（不改动上方既有用例） ----------

    @Test
    @DisplayName("IP 信任链：X-Forwarded-For 存在时以它为 key，不与 remoteAddr 共享窗口")
    void clientIp_xForwardedForBranch_shouldProduceDistinctKeyFromRemoteAddr() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest withXff = request("/api/x");
        withXff.setRemoteAddr("5.5.5.5");
        withXff.addHeader("X-Forwarded-For", "1.1.1.1");
        MockHttpServletRequest plain = request("/api/x");
        plain.setRemoteAddr("5.5.5.5");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(withXff, response, new Object()));
        // XFF=1.1.1.1 与 remoteAddr=5.5.5.5 必须视为不同来源：第二个请求不应与第一个共享窗口
        assertTrue(interceptor.preHandle(plain, response, new Object()));
        assertEquals(0.0, counterCount("memory"));
    }

    @Test
    @DisplayName("IP 信任链：X-Real-IP 与 remoteAddr 同 IP 时共享窗口（取值未被替换为空）")
    void clientIp_xRealIpValue_shouldShareWindowWithRemoteAddr() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest withRealIp = request("/api/x");
        withRealIp.setRemoteAddr("5.5.5.5");
        withRealIp.addHeader("X-Real-IP", "9.9.9.9");
        MockHttpServletRequest plain = request("/api/x");
        plain.setRemoteAddr("9.9.9.9");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(withRealIp, response, new Object()));
        assertFalse(interceptor.preHandle(plain, response, new Object()));
    }

    @Test
    @DisplayName("IP 信任链：X-Forwarded-For 最后一段与 remoteAddr 同 IP 时共享窗口")
    void clientIp_xForwardedLastSegment_shouldShareWindowWithRemoteAddr() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        MockHttpServletRequest withXff = request("/api/x");
        withXff.setRemoteAddr("5.5.5.5");
        withXff.addHeader("X-Forwarded-For", "client-a, 1.1.1.1");
        MockHttpServletRequest plain = request("/api/x");
        plain.setRemoteAddr("1.1.1.1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(withXff, response, new Object()));
        assertFalse(interceptor.preHandle(plain, response, new Object()));
    }

    @Test
    @DisplayName("定期清理：时间戳恰等于两窗口保留边界时不删除（边界含等号）")
    void cleanupStaleWindows_timestampExactlyAtCutoff_shouldBeKept() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);
        long now = System.currentTimeMillis();
        ConcurrentLinkedDeque<Long> exactlyAtCutoff = new ConcurrentLinkedDeque<>();
        exactlyAtCutoff.addLast(now - 120_000L);
        windows.put("exact:key", exactlyAtCutoff);

        ReflectionTestUtils.invokeMethod(interceptor, "cleanupStaleWindows", now);

        assertTrue(windows.containsKey("exact:key"), "恰等于 cutoff 的时间戳应保留");
        assertEquals(1, windows.get("exact:key").size());
    }

    @Test
    @DisplayName("滑动窗口：时间戳恰落在 60s 裁剪边界（cutoff）时保留并占满配额")
    void slidingWindow_timestampExactlyAtCutoff_shouldBeKeptAndReject() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
        @SuppressWarnings("unchecked")
        Map<String, ConcurrentLinkedDeque<Long>> windows =
                (Map<String, ConcurrentLinkedDeque<Long>>) ReflectionTestUtils.getField(interceptor, "windows");
        assertNotNull(windows);

        // cutoff = now - 60_000；seed 取当前时刻减 60_000，preHandle 内读到同一毫秒时即命中“恰等于”边界
        boolean observedRejection = false;
        for (int attempt = 0; attempt < 500 && !observedRejection; attempt++) {
            windows.clear();
            long seed = System.currentTimeMillis() - 60_000L;
            ConcurrentLinkedDeque<Long> deque = new ConcurrentLinkedDeque<>();
            deque.addLast(seed);
            windows.put("203.0.113.9:/api/x", deque);

            boolean allowed = interceptor.preHandle(
                    request("/api/x"), new MockHttpServletResponse(), new Object());
            if (!allowed) {
                observedRejection = true;
            }
        }
        assertTrue(observedRejection,
                "恰等于 cutoff 的旧时间戳应保留并占满配额（500 次尝试内应观察到拒绝）");
    }

    @Test
    @DisplayName("定期清理触发边界：now - lastCleanupTime 恰等于 300000ms 时不清理（严格大于才清理）")
    void preHandle_cleanupExactlyAtInterval_shouldNotTriggerCleanup() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(10_000);
        MockHttpServletResponse response = new MockHttpServletResponse();
        Object handler = new Object();

        // 条件为单点边界：lastCleanupTime 需恰等于 preHandle 内读取到的 now - 300000。
        // 单次调用内 now 与设值时刻同毫秒的概率极高，迭代直至观测到“调用期间未回写清理时间”
        // （原实现：恰相等时严格大于为假，不清理，字段保持哨兵值）。
        // 若条件被改为 >=，则任何一次调用都必然清理并回写 now（now >= 哨兵 + 300000），
        // 该观测将不可能出现——以此锁死边界方向。
        boolean observedNoCleanupAtBoundary = false;
        for (int attempt = 0; attempt < 1000 && !observedNoCleanupAtBoundary; attempt++) {
            long sentinel = System.currentTimeMillis() - 300_000L;
            ReflectionTestUtils.setField(interceptor, "lastCleanupTime", sentinel);

            interceptor.preHandle(request("/api/v1/projects/boundary"), response, handler);

            Long afterCall = (Long) ReflectionTestUtils.getField(interceptor, "lastCleanupTime");
            if (afterCall != null && afterCall == sentinel) {
                observedNoCleanupAtBoundary = true;
            }
        }
        assertTrue(observedNoCleanupAtBoundary,
                "now - lastCleanupTime 恰等于 300000ms 时不应触发清理（1000 次尝试内应观察到未清理）");
    }
}
