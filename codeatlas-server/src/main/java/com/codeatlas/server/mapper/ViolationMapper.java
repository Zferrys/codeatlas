package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ViolationEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ViolationMapper {

    @Select("SELECT * FROM violation WHERE project_id = #{projectId} ORDER BY severity DESC, created_at DESC")
    List<ViolationEntity> findByProjectId(Long projectId);

    @Select("SELECT * FROM violation WHERE scan_id = #{scanId} ORDER BY severity DESC")
    List<ViolationEntity> findByScanId(Long scanId);

    @Insert("INSERT INTO violation (scan_id, rule_id, project_id, severity, class_fqn, method_name, "
            + "line_number, message, suggestion, is_resolved, created_at) "
            + "VALUES (#{scanId}, #{ruleId}, #{projectId}, #{severity}, #{classFqn}, #{methodName}, "
            + "#{lineNumber}, #{message}, #{suggestion}, #{isResolved}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ViolationEntity violation);

    @Update("UPDATE violation SET is_resolved = 1, resolved_at = NOW() WHERE id = #{id}")
    int resolve(Long id);

    @Delete("DELETE FROM violation WHERE scan_id = #{scanId}")
    int deleteByScanId(Long scanId);

    @Update("UPDATE violation SET is_resolved = 1, resolved_at = NOW() WHERE rule_id = #{ruleId} AND is_resolved = 0")
    int resolveAllByRuleId(Long ruleId);
}
