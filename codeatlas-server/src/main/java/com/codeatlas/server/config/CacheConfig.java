package com.codeatlas.server.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
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
        // 需要 Default Typing 才能让 Entity/GraphVO 缓存往返不丢类型；
        // 但类型化反序列化必须收窄白名单（仅放行应用自身与 JDK 容器/时间/数值类型），
        // 不能沿用默认的全量放行，否则 Redis 中任意类型 id 都会参与反序列化
        BasicPolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.codeatlas.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.math.")
                .build();
        mapper.activateDefaultTyping(typeValidator, ObjectMapper.DefaultTyping.NON_FINAL);
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

            builder.withCacheConfiguration("mapData",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(serializer))
                            .entryTtl(Duration.ofMinutes(30)));
        };
    }
}
