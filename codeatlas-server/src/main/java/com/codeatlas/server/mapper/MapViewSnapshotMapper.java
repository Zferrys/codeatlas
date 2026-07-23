package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.MapViewSnapshot;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface MapViewSnapshotMapper {

    @Insert("INSERT INTO map_view_snapshot (project_id, user_id, name, view_state, is_public, created_at, updated_at) "
            + "VALUES (#{projectId}, #{userId}, #{name}, #{viewState}, #{isPublic}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(MapViewSnapshot snapshot);

    @Delete("DELETE FROM map_view_snapshot WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Select("SELECT * FROM map_view_snapshot WHERE id = #{id}")
    MapViewSnapshot findById(Long id);

    @Select("SELECT * FROM map_view_snapshot WHERE project_id = #{projectId} "
            + "AND (user_id = #{userId} OR is_public = 1) ORDER BY updated_at DESC")
    List<MapViewSnapshot> findByProjectId(@Param("projectId") Long projectId, @Param("userId") Long userId);
}
