package com.codeatlas.server.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Component
public class HealthIndicators {

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthIndicators(DataSource dataSource, RedisConnectionFactory redisConnectionFactory) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
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
}
