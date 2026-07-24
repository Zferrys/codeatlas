package com.codeatlas.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * 显式创建 JDBC 事务管理器并标记为 @Primary。
 * 原因：Neo4j 的 Neo4jTransactionManager 先于 DataSourceTransactionManager 注册，
 * 导致 @ConditionalOnMissingBean 跳过 JDBC 事务管理器，@Transactional 无参时全部走 Neo4j。
 */
@Configuration
public class TransactionManagerConfig {

    @Primary
    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
