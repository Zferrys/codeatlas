package com.codeatlas.server.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProjectMemberMapper {

    @Insert("INSERT INTO project_member (project_id, user_id, role) VALUES (#{projectId}, #{userId}, 'OWNER')")
    int insertOwner(Long projectId, Long userId);
}
