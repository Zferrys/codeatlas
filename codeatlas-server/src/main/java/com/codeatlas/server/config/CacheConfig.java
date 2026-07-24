package com.codeatlas.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    private final GenericJackson2JsonRedisSerializer serializer;

    public CacheConfig() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 必须启用 Default Typing，否则缓存反序列化时类型丢失，Entity 会变成 LinkedHashMap
        mapper.activateDefaultTyping(
                mapper.getPolymorphicTypeValidator(),
                ObjectMapper.DefaultTyping.NON_FINAL);
        this.serializer = new GenericJackson2JsonRedisSerializer(mapper);
    }

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheManagerBuilderCustomizer() {
        return builder -> {
            builder.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(serializer))
                    .entryTtl(Duration.ofMinutes(5)));

            builder.withCacheConfiguration("rules",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(serializer))
                            .entryTtl(Duration.ofMinutes(10)));

            builder.withCacheConfiguration("insights",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(serializer))
                            .entryTtl(Duration.ofMinutes(5)));

            builder.withCacheConfiguration("violations",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(serializer))
                            .entryTtl(Duration.ofMinutes(1)));
        };
    }
}
