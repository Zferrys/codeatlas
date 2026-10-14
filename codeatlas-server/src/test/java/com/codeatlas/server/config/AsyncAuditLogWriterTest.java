package com.codeatlas.server.config;

import com.codeatlas.server.entity.AuditLogEntity;
import com.codeatlas.server.mapper.AuditLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsyncAuditLogWriter 异步审计写入器测试")
class AsyncAuditLogWriterTest {

    @Mock
    private AuditLogMapper auditLogMapper;

    @InjectMocks
    private AsyncAuditLogWriter writer;

    @Test
    @DisplayName("write 将审计实体原样委托给 AuditLogMapper.insert")
    void writeShouldDelegateToMapper() {
        AuditLogEntity entity = new AuditLogEntity();
        entity.setUserId(1L);
        entity.setAction("LOGIN");

        writer.write(entity);

        verify(auditLogMapper).insert(entity);
    }

    @Test
    @DisplayName("mapper 写入失败时吞掉异常（审计失败不影响业务主流程）")
    void writeShouldSwallowMapperException() {
        doThrow(new RuntimeException("db down")).when(auditLogMapper).insert(any(AuditLogEntity.class));

        assertDoesNotThrow(() -> writer.write(new AuditLogEntity()));
    }
}
