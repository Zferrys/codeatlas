package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.AiAuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiAuditLogMapper {

    @Insert("INSERT INTO ai_audit_log (trace_id, model, stage, project_id, user_id, "
            + "prompt_tokens, completion_tokens, total_tokens, latency_ms, cost, "
            + "success, error_message, created_at) "
            + "VALUES (#{traceId}, #{model}, #{stage}, #{projectId}, #{userId}, "
            + "#{promptTokens}, #{completionTokens}, #{totalTokens}, #{latencyMs}, #{cost}, "
            + "#{success}, #{errorMessage}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiAuditLogEntity log);

    List<Map<String, Object>> monthlySummary();
}
