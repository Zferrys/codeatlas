package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ProjectMember;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProjectMemberMapper {

    @Insert("INSERT INTO project_member (project_id, user_id, role) VALUES (#{projectId}, #{userId}, 'OWNER')")
    int insertOwner(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Insert("INSERT INTO project_member (project_id, user_id, role) VALUES (#{projectId}, #{userId}, #{role})")
    int insert(@Param("projectId") Long projectId, @Param("userId") Long userId, @Param("role") String role);

    @Delete("DELETE FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
    int delete(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select("SELECT * FROM project_member WHERE project_id = #{projectId} AND user_id = #{userId}")
    ProjectMember findByProjectAndUser(@Param("projectId") Long projectId, @Param("userId") Long userId);

    @Select("SELECT * FROM project_member WHERE project_id = #{projectId}")
    List<ProjectMember> findByProjectId(@Param("projectId") Long projectId);
}
