package com.codeatlas.server.config;

import com.codeatlas.server.entity.AuditLogEntity;
import com.codeatlas.server.mapper.AuditLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 异步审计日志写入器，避免同步 I/O 阻塞业务线程。
 */
@Component
public class AsyncAuditLogWriter {

    private static final Logger log = LoggerFactory.getLogger(AsyncAuditLogWriter.class);

    private final AuditLogMapper auditLogMapper;

    public AsyncAuditLogWriter(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    @Async("auditExecutor")
    public void write(AuditLogEntity entity) {
        try {
            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }
    }
}
