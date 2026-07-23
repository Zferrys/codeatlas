package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ConstitutionRuleEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ConstitutionRuleMapper {

    @Select("SELECT * FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL) AND is_enabled = 1 ORDER BY category, severity")
    List<ConstitutionRuleEntity> findByProjectId(Long projectId);

    @Select("SELECT COUNT(*) FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL) AND is_enabled = 1")
    long countByProjectId(Long projectId);

    @Select("SELECT * FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL) AND is_enabled = 1 ORDER BY category, severity LIMIT #{offset}, #{size}")
    List<ConstitutionRuleEntity> findByProjectIdPaged(@Param("projectId") Long projectId,
                                                       @Param("offset") int offset,
                                                       @Param("size") int size);

    @Select("SELECT * FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL) ORDER BY category, severity")
    List<ConstitutionRuleEntity> findAllByProjectId(Long projectId);

    @Select("SELECT COUNT(*) FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL)")
    long countAllByProjectId(Long projectId);

    @Select("SELECT * FROM constitution_rule WHERE (project_id = #{projectId} OR project_id IS NULL) ORDER BY category, severity LIMIT #{offset}, #{size}")
    List<ConstitutionRuleEntity> findAllByProjectIdPaged(@Param("projectId") Long projectId,
                                                          @Param("offset") int offset,
                                                          @Param("size") int size);

    @Select("SELECT * FROM constitution_rule WHERE id = #{id}")
    ConstitutionRuleEntity findById(Long id);

    @Insert("INSERT INTO constitution_rule (project_id, name, description, category, severity, rule_definition, is_enabled, version, created_at, updated_at) "
            + "VALUES (#{projectId}, #{name}, #{description}, #{category}, #{severity}, #{ruleDefinition}, #{isEnabled}, #{version}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ConstitutionRuleEntity rule);

    @Update("UPDATE constitution_rule SET is_enabled = #{isEnabled}, updated_at = NOW() WHERE id = #{id}")
    int toggleEnabled(ConstitutionRuleEntity rule);

    @Update("UPDATE constitution_rule SET rule_definition = #{ruleDefinition}, version = #{version}, "
            + "updated_at = NOW() WHERE id = #{id}")
    int updateDefinition(ConstitutionRuleEntity rule);

    @Update("UPDATE constitution_rule SET description = #{description}, severity = #{severity}, "
            + "rule_definition = #{ruleDefinition}, updated_at = NOW() WHERE id = #{id}")
    int update(ConstitutionRuleEntity rule);
}
