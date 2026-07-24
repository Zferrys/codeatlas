package com.codeatlas.server.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.TransactionManagementConfigurer;

/**
 * 显式指定默认事务管理器为 JDBC (MySQL)，防止 @Transactional 无参时误选 Neo4j。
 */
@Configuration
public class TransactionManagerConfig implements TransactionManagementConfigurer {

    private final PlatformTransactionManager txManager;

    public TransactionManagerConfig(
            @Qualifier("transactionManager") PlatformTransactionManager txManager) {
        this.txManager = txManager;
    }

    @Override
    public PlatformTransactionManager annotationDrivenTransactionManager() {
        return txManager;
    }
}
