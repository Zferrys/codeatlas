package com.codeatlas.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

@DisplayName("CacheConfig 单元测试（缓存 TTL + 类型化 JSON 序列化安全边界）")
class CacheConfigTest {

    private static RedisCacheManager buildManager() {
        RedisConnectionFactory connectionFactory = mock(RedisConnectionFactory.class);
        RedisCacheManagerBuilder builder = RedisCacheManager.builder(connectionFactory);
        new CacheConfig().cacheManagerBuilderCustomizer().customize(builder);
        RedisCacheManager manager = builder.build();
        try {
            manager.afterPropertiesSet();
        } catch (Exception e) {
            throw new IllegalStateException("cache manager init failed", e);
        }
        return manager;
    }

    private static RedisCacheConfiguration configurationOf(RedisCacheManager manager, String cacheName) {
        RedisCache cache = (RedisCache) manager.getCache(cacheName);
        assertNotNull(cache, "缓存 " + cacheName + " 应已注册");
        return cache.getCacheConfiguration();
    }

    private static RedisCacheConfiguration defaultConfiguration(RedisCacheManager manager) {
        return (RedisCacheConfiguration) ReflectionTestUtils.invokeMethod(manager, "getDefaultCacheConfiguration");
    }

    /** SerializationPair 的 writer 是包装类，内部持有实际的 RedisSerializer */
    private static GenericJackson2JsonRedisSerializer serializerOf(RedisCacheConfiguration configuration) {
        Object writer = configuration.getValueSerializationPair().getWriter();
        Object serializer = ReflectionTestUtils.getField(writer, "serializer");
        assertInstanceOf(GenericJackson2JsonRedisSerializer.class, serializer);
        return (GenericJackson2JsonRedisSerializer) serializer;
    }

    @Test
    @DisplayName("默认配置：写值序列化器为 GenericJackson2JsonRedisSerializer 且 TTL 5 分钟")
    void defaultCacheConfiguration_jsonSerializerWithFiveMinuteTtl() {
        RedisCacheManager manager = buildManager();

        RedisCacheConfiguration defaults = defaultConfiguration(manager);

        assertNotNull(serializerOf(defaults));
        assertEquals(Duration.ofMinutes(5), defaults.getTtl());
    }

    @Test
    @DisplayName("具名缓存 TTL 精确：rules=10min / insights=5min / violations=1min / mapData=30min")
    void namedCaches_haveExactTtls() {
        RedisCacheManager manager = buildManager();

        assertEquals(Duration.ofMinutes(10), configurationOf(manager, "rules").getTtl());
        assertEquals(Duration.ofMinutes(5), configurationOf(manager, "insights").getTtl());
        assertEquals(Duration.ofMinutes(1), configurationOf(manager, "violations").getTtl());
        assertEquals(Duration.ofMinutes(30), configurationOf(manager, "mapData").getTtl());
    }

    @Test
    @DisplayName("所有缓存共用同一个 JSON 序列化器实例，且具名缓存挂载了 JSON 序列化器")
    void namedCaches_shareSameSerializerInstance() {
        RedisCacheManager manager = buildManager();

        GenericJackson2JsonRedisSerializer rulesSerializer = serializerOf(configurationOf(manager, "rules"));
        GenericJackson2JsonRedisSerializer insightsSerializer = serializerOf(configurationOf(manager, "insights"));
        GenericJackson2JsonRedisSerializer violationsSerializer = serializerOf(configurationOf(manager, "violations"));
        GenericJackson2JsonRedisSerializer mapDataSerializer = serializerOf(configurationOf(manager, "mapData"));
        GenericJackson2JsonRedisSerializer defaultSerializer = serializerOf(defaultConfiguration(manager));

        assertSame(rulesSerializer, insightsSerializer);
        assertSame(rulesSerializer, violationsSerializer);
        assertSame(rulesSerializer, mapDataSerializer);
        assertSame(rulesSerializer, defaultSerializer);
    }

    @Test
    @DisplayName("序列化往返：应用类型、java.time、java.util、java.math 字段全部保型且值相等")
    void serializerRoundTrip_preservesTypesAndValues() {
        RedisCacheManager manager = buildManager();
        SerializationPair<Object> pair = configurationOf(manager, "rules").getValueSerializationPair();
        SamplePayload payload = new SamplePayload(
                "demo",
                LocalDateTime.of(2026, 10, 14, 1, 2, 3),
                Arrays.asList("alpha", "beta"),
                new BigDecimal("12.34"));

        ByteBuffer buffer = pair.write(payload);
        Object restored = pair.read(buffer);

        assertInstanceOf(SamplePayload.class, restored);
        assertEquals(payload, restored);
        SamplePayload restoredPayload = (SamplePayload) restored;
        assertInstanceOf(List.class, restoredPayload.getTags());
        assertEquals("alpha", restoredPayload.getTags().get(0));
        assertEquals(0, restoredPayload.getAmount().compareTo(new BigDecimal("12.34")));
    }

    @Test
    @DisplayName("白名单防注入：非白名单前缀的类型 id 反序列化被拒绝")
    void serializer_rejectsTypeOutsideAllowList() throws Exception {
        RedisCacheManager manager = buildManager();
        GenericJackson2JsonRedisSerializer serializer = serializerOf(configurationOf(manager, "rules"));

        // 用宽松校验器生成一份携带 java.lang 类型 id 的合法负载（java.lang 不在白名单内）
        ObjectMapper permissive = new ObjectMapper();
        permissive.activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);
        byte[] malicious = permissive.writeValueAsBytes(new IllegalStateException("evil"));

        // 前置校验：同一负载在宽松校验器下可以还原（证明失败来自白名单校验而非数据本身）
        assertNotNull(permissive.readValue(malicious, Object.class));

        assertThrows(RuntimeException.class, () -> serializer.deserialize(malicious));
    }

    static class SamplePayload {

        private String name;
        private LocalDateTime when;
        private List<String> tags;
        private BigDecimal amount;

        SamplePayload() {
        }

        SamplePayload(String name, LocalDateTime when, List<String> tags, BigDecimal amount) {
            this.name = name;
            this.when = when;
            this.tags = tags;
            this.amount = amount;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public LocalDateTime getWhen() {
            return when;
        }

        public void setWhen(LocalDateTime when) {
            this.when = when;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public BigDecimal getAmount() {
            return amount;
        }

        public void setAmount(BigDecimal amount) {
            this.amount = amount;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof SamplePayload)) {
                return false;
            }
            SamplePayload that = (SamplePayload) o;
            return Objects.equals(name, that.name)
                    && Objects.equals(when, that.when)
                    && Objects.equals(tags, that.tags)
                    && Objects.equals(amount, that.amount);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, when, tags, amount);
        }
    }
}
