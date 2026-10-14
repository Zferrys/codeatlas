package com.codeatlas.server.config;

import com.codeatlas.engine.ai.AiClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.Result;
import org.neo4j.driver.Session;
import org.neo4j.driver.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthIndicators 单元测试（MySQL/Redis/Neo4j/AiApi 四个健康指示器）")
class HealthIndicatorsTest {

    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private RedisConnectionFactory redisConnectionFactory;
    @Mock
    private RedisConnection redisConnection;
    @Mock
    private Driver neo4jDriver;
    @Mock
    private Session neo4jSession;
    @Mock
    private Result neo4jResult;
    @Mock
    private Record neo4jRecord;
    @Mock
    private Value neo4jValue;
    @Mock
    private AiClient aiClient;

    private HealthIndicators indicators(AiClient client) {
        return new HealthIndicators(dataSource, redisConnectionFactory, neo4jDriver, client);
    }

    // ---------- MySQL ----------

    @Test
    @DisplayName("MySQL：连接校验通过 -> UP + type=MySQL 5.7，连接被关闭")
    void mysql_validConnection_up() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(true);

        Health health = indicators(aiClient).new MySqlHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("MySQL 5.7", health.getDetails().get("type"));
        verify(connection).isValid(3);
        verify(connection).close();
    }

    @Test
    @DisplayName("MySQL：连接校验失败 -> DOWN + 精确错误信息，连接仍被关闭")
    void mysql_invalidConnection_down() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(3)).thenReturn(false);

        Health health = indicators(aiClient).new MySqlHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Connection validation failed", health.getDetails().get("error"));
        verify(connection).close();
    }

    @Test
    @DisplayName("MySQL：取连接抛异常 -> DOWN 携带异常详情")
    void mysql_connectionException_down() throws Exception {
        SQLException failure = new SQLException("mysql down");
        when(dataSource.getConnection()).thenThrow(failure);

        Health health = indicators(aiClient).new MySqlHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(failure.getClass().getName() + ": " + failure.getMessage(),
                health.getDetails().get("error"));
    }

    // ---------- Redis ----------

    @Test
    @DisplayName("Redis：PING 返回 PONG -> UP + type=Redis，连接被关闭")
    void redis_pong_up() {
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PONG");

        Health health = indicators(aiClient).new RedisHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("Redis", health.getDetails().get("type"));
        verify(redisConnection).ping();
        verify(redisConnection).close();
    }

    @Test
    @DisplayName("Redis：PING 返回非 PONG -> DOWN + 精确错误信息")
    void redis_badPing_down() {
        when(redisConnectionFactory.getConnection()).thenReturn(redisConnection);
        when(redisConnection.ping()).thenReturn("PING");

        Health health = indicators(aiClient).new RedisHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Ping returned: PING", health.getDetails().get("error"));
        verify(redisConnection).close();
    }

    @Test
    @DisplayName("Redis：取连接抛异常 -> DOWN 携带异常详情")
    void redis_connectionException_down() {
        RuntimeException failure = new RuntimeException("redis down");
        when(redisConnectionFactory.getConnection()).thenThrow(failure);

        Health health = indicators(aiClient).new RedisHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(failure.getClass().getName() + ": " + failure.getMessage(),
                health.getDetails().get("error"));
    }

    // ---------- Neo4j ----------

    @Test
    @DisplayName("Neo4j：查询返回 1 -> UP + type=Neo4j，session 被关闭")
    void neo4j_ok_up() {
        when(neo4jDriver.session()).thenReturn(neo4jSession);
        when(neo4jSession.run("RETURN 1 AS ok")).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(true);
        when(neo4jResult.next()).thenReturn(neo4jRecord);
        when(neo4jRecord.get("ok")).thenReturn(neo4jValue);
        when(neo4jValue.asInt()).thenReturn(1);

        Health health = indicators(aiClient).new Neo4jHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("Neo4j", health.getDetails().get("type"));
        verify(neo4jSession).run("RETURN 1 AS ok");
        verify(neo4jSession).close();
    }

    @Test
    @DisplayName("Neo4j：无结果集 -> DOWN + Unexpected query result（不消费 next）")
    void neo4j_noRows_down() {
        when(neo4jDriver.session()).thenReturn(neo4jSession);
        when(neo4jSession.run("RETURN 1 AS ok")).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(false);

        Health health = indicators(aiClient).new Neo4jHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Unexpected query result", health.getDetails().get("error"));
        verify(neo4jResult, never()).next();
        verify(neo4jSession).close();
    }

    @Test
    @DisplayName("Neo4j：查询结果不为 1 -> DOWN + Unexpected query result")
    void neo4j_unexpectedValue_down() {
        when(neo4jDriver.session()).thenReturn(neo4jSession);
        when(neo4jSession.run("RETURN 1 AS ok")).thenReturn(neo4jResult);
        when(neo4jResult.hasNext()).thenReturn(true);
        when(neo4jResult.next()).thenReturn(neo4jRecord);
        when(neo4jRecord.get("ok")).thenReturn(neo4jValue);
        when(neo4jValue.asInt()).thenReturn(2);

        Health health = indicators(aiClient).new Neo4jHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Unexpected query result", health.getDetails().get("error"));
        verify(neo4jSession).close();
    }

    @Test
    @DisplayName("Neo4j：建会话抛异常 -> DOWN 携带异常详情")
    void neo4j_sessionException_down() {
        RuntimeException failure = new RuntimeException("neo4j down");
        when(neo4jDriver.session()).thenThrow(failure);

        Health health = indicators(aiClient).new Neo4jHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals(failure.getClass().getName() + ": " + failure.getMessage(),
                health.getDetails().get("error"));
    }

    // ---------- AiApi ----------

    @Test
    @DisplayName("AiApi：无 AI 客户端 -> UNKNOWN + 精确原因")
    void aiApi_noClient_unknown() {
        Health health = indicators(null).new AiApiHealthIndicator().health();

        assertEquals(Status.UNKNOWN, health.getStatus());
        assertEquals("No AI client configured", health.getDetails().get("reason"));
    }

    @Test
    @DisplayName("AiApi：健康检查通过 -> UP + model 名称")
    void aiApi_healthy_up() {
        when(aiClient.healthCheck()).thenReturn(true);
        when(aiClient.getModelName()).thenReturn("claude-x");

        Health health = indicators(aiClient).new AiApiHealthIndicator().health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("claude-x", health.getDetails().get("model"));
        verify(aiClient).healthCheck();
        verify(aiClient).getModelName();
    }

    @Test
    @DisplayName("AiApi：健康检查返回 false -> DOWN + model/error 详情")
    void aiApi_unhealthy_down() {
        when(aiClient.healthCheck()).thenReturn(false);
        when(aiClient.getModelName()).thenReturn("claude-x");

        Health health = indicators(aiClient).new AiApiHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("claude-x", health.getDetails().get("model"));
        assertEquals("Health check returned false", health.getDetails().get("error"));
    }

    @Test
    @DisplayName("AiApi：健康检查抛异常 -> DOWN + model/异常详情")
    void aiApi_exception_down() {
        RuntimeException failure = new RuntimeException("ai down");
        when(aiClient.healthCheck()).thenThrow(failure);
        when(aiClient.getModelName()).thenReturn("claude-x");

        Health health = indicators(aiClient).new AiApiHealthIndicator().health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("claude-x", health.getDetails().get("model"));
        assertEquals(failure.getClass().getName() + ": " + failure.getMessage(),
                health.getDetails().get("error"));
    }

    @Test
    @DisplayName("四个内部指示器均可在装配后的外壳实例上创建")
    void innerIndicators_areInstantiable() {
        HealthIndicators outer = indicators(aiClient);

        assertNotNull(outer.new MySqlHealthIndicator());
        assertNotNull(outer.new RedisHealthIndicator());
        assertNotNull(outer.new Neo4jHealthIndicator());
        assertNotNull(outer.new AiApiHealthIndicator());
    }
}
