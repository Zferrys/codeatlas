package com.codeatlas.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("TransactionManagerConfig 事务管理器配置测试")
class TransactionManagerConfigTest {

    @Test
    @DisplayName("transactionManager 返回包装了注入 DataSource 的 JDBC 事务管理器")
    void shouldCreateJdbcTransactionManagerWithDataSource() {
        DataSource dataSource = mock(DataSource.class);

        PlatformTransactionManager transactionManager = new TransactionManagerConfig().transactionManager(dataSource);

        DataSourceTransactionManager jdbcManager = assertInstanceOf(DataSourceTransactionManager.class, transactionManager);
        assertSame(dataSource, jdbcManager.getDataSource(), "事务管理器必须持有注入的 DataSource 实例");
    }

    @Test
    @DisplayName("transactionManager 方法标注 @Primary，覆盖默认的 Neo4j 事务管理器")
    void shouldMarkTransactionManagerAsPrimary() throws Exception {
        assertTrue(TransactionManagerConfig.class
                        .getMethod("transactionManager", DataSource.class)
                        .isAnnotationPresent(Primary.class),
                "@Primary 缺失会导致 @Transactional 无参时错误地走 Neo4j 事务管理器");
    }
}
