package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ViolationEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ViolationMapper {

    /** 严重度按 BLOCKER > ERROR > WARN > INFO 排序（字母序 DESC 会把 BLOCKER 排最后） */
    String SEVERITY_ORDER = "CASE severity WHEN 'BLOCKER' THEN 1 WHEN 'ERROR' THEN 2 WHEN 'WARN' THEN 3 ELSE 4 END";

    @Select("SELECT COUNT(*) FROM violation WHERE project_id = #{projectId}")
    long countByProjectId(Long projectId);

    @Select("SELECT * FROM violation WHERE project_id = #{projectId} ORDER BY " + SEVERITY_ORDER + ", created_at DESC LIMIT #{offset}, #{size}")
    List<ViolationEntity> findByProjectIdPaged(@Param("projectId") Long projectId,
                                                @Param("offset") int offset,
                                                @Param("size") int size);

    @Select("SELECT * FROM violation WHERE scan_id = #{scanId} ORDER BY " + SEVERITY_ORDER)
    List<ViolationEntity> findByScanId(Long scanId);

    @Insert("INSERT INTO violation (scan_id, rule_id, project_id, severity, class_fqn, method_name, "
            + "line_number, message, suggestion, is_resolved, created_at) "
            + "VALUES (#{scanId}, #{ruleId}, #{projectId}, #{severity}, #{classFqn}, #{methodName}, "
            + "#{lineNumber}, #{message}, #{suggestion}, #{isResolved}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ViolationEntity violation);

    @Update("UPDATE violation SET is_resolved = 1, resolved_at = NOW() WHERE id = #{id} AND project_id = #{projectId}")
    int resolve(@Param("id") Long id, @Param("projectId") Long projectId);

    @Delete("DELETE FROM violation WHERE scan_id = #{scanId}")
    int deleteByScanId(Long scanId);

    @Delete("DELETE FROM violation WHERE project_id = #{projectId}")
    int deleteByProjectId(Long projectId);

    @Insert("<script>" +
            "INSERT INTO violation (scan_id, rule_id, project_id, severity, class_fqn, method_name, " +
            "line_number, message, suggestion, is_resolved, created_at) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.scanId}, #{item.ruleId}, #{item.projectId}, #{item.severity}, #{item.classFqn}, #{item.methodName}, " +
            "#{item.lineNumber}, #{item.message}, #{item.suggestion}, #{item.isResolved}, NOW())" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ViolationEntity> list);

    @Update("UPDATE violation SET is_resolved = 1, resolved_at = NOW() WHERE rule_id = #{ruleId} AND is_resolved = 0")
    int resolveAllByRuleId(Long ruleId);
}
