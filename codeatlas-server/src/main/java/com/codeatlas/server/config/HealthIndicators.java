package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class HealthIndicators {

    private static final Logger log = LoggerFactory.getLogger(HealthIndicators.class);

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final Driver neo4jDriver;
    private final AiClient aiClient;

    public HealthIndicators(DataSource dataSource,
                            RedisConnectionFactory redisConnectionFactory,
                            Driver neo4jDriver,
                            @Autowired(required = false) AiClient aiClient) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.neo4jDriver = neo4jDriver;
        this.aiClient = aiClient;
    }

    @Component("mysql")
    public class MySqlHealthIndicator implements HealthIndicator {
        @Override
        public Health health() {
            try (Connection conn = dataSource.getConnection()) {
                if (conn.isValid(3)) {
                    return Health.up().withDetail("type", "MySQL 5.7").build();
                }
                return Health.down().withDetail("error", "Connection validation failed").build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        }
    }

    @Component("redis")
    public class RedisHealthIndicator implements HealthIndicator {
        @Override
        public Health health() {
            try {
                var conn = redisConnectionFactory.getConnection();
                String pong = conn.ping();
                conn.close();
                if ("PONG".equals(pong)) {
                    return Health.up().withDetail("type", "Redis").build();
                }
                return Health.down().withDetail("error", "Ping returned: " + pong).build();
            } catch (Exception e) {
                return Health.down(e).build();
            }
        }
    }

    /**
     * 非响应式 Neo4j 健康检查。
     * 替代默认的 reactive health indicator，避免启动时 WARN 噪音。
     */
    @Component("neo4jHealth")
    public class Neo4jHealthIndicator implements HealthIndicator {
        @Override
        public Health health() {
            try (var session = neo4jDriver.session()) {
                var result = session.run("RETURN 1 AS ok");
                if (result.hasNext() && result.next().get("ok").asInt() == 1) {
                    return Health.up().withDetail("type", "Neo4j").build();
                }
                return Health.down().withDetail("error", "Unexpected query result").build();
            } catch (Exception e) {
                log.warn("Neo4j health check failed: {}", e.getMessage());
                return Health.down(e).build();
            }
        }
    }

    /**
     * AI API 健康检查。
     * 验证至少有一个 AI 模型可用（Claude 或 DeepSeek）。
     */
    @Component("aiApi")
    public class AiApiHealthIndicator implements HealthIndicator {
        @Override
        public Health health() {
            if (aiClient == null) {
                return Health.unknown().withDetail("reason", "No AI client configured").build();
            }
            try {
                boolean ok = aiClient.healthCheck();
                if (ok) {
                    return Health.up().withDetail("model", aiClient.getModelName()).build();
                }
                return Health.down().withDetail("model", aiClient.getModelName())
                        .withDetail("error", "Health check returned false").build();
            } catch (Exception e) {
                log.warn("AI API health check failed: {}", e.getMessage());
                return Health.down(e).withDetail("model", aiClient.getModelName()).build();
            }
        }
    }
}
