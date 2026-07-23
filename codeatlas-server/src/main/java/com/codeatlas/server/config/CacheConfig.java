package com.codeatlas.server.config;

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

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheManagerBuilderCustomizer() {
        return builder -> {
            builder.cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                    .serializeValuesWith(RedisSerializationContext.SerializationPair
                            .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                    .entryTtl(Duration.ofMinutes(5)));

            builder.withCacheConfiguration("rules",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .entryTtl(Duration.ofMinutes(10)));

            builder.withCacheConfiguration("insights",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .entryTtl(Duration.ofMinutes(5)));

            builder.withCacheConfiguration("violations",
                    RedisCacheConfiguration.defaultCacheConfig()
                            .serializeValuesWith(RedisSerializationContext.SerializationPair
                                    .fromSerializer(new GenericJackson2JsonRedisSerializer()))
                            .entryTtl(Duration.ofMinutes(1)));
        };
    }
}
