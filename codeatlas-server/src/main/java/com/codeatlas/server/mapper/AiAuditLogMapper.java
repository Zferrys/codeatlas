package com.codeatlas.server.mapper;

import com.codeatlas.server.dto.AiUsageQuery;
import com.codeatlas.server.entity.AiAuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

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

    /** 管理端 Token 流水：分页明细（含使用者用户名） */
    List<Map<String, Object>> findUsagePaged(@Param("q") AiUsageQuery query, @Param("offset") int offset);

    long countUsage(@Param("q") AiUsageQuery query);

    Map<String, Object> usageSummary(@Param("q") AiUsageQuery query);

    List<Map<String, Object>> usageByModel(@Param("q") AiUsageQuery query);

    List<Map<String, Object>> usageByStage(@Param("q") AiUsageQuery query);

    List<Map<String, Object>> usageByUser(@Param("q") AiUsageQuery query);
}
