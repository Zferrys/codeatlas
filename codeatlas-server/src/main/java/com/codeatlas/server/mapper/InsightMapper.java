package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.InsightEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface InsightMapper {

    @Insert("INSERT INTO insight (scan_id, project_id, type, title, content, confidence, sources, metadata) " +
            "VALUES (#{scanId}, #{projectId}, #{type}, #{title}, #{content}, #{confidence}, #{sources}, #{metadata})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(InsightEntity entity);

    @Select("SELECT * FROM insight WHERE project_id = #{projectId} ORDER BY created_at DESC")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<InsightEntity> findByProjectId(Long projectId);

    @Select("SELECT COUNT(*) FROM insight WHERE project_id = #{projectId}")
    long countByProjectId(Long projectId);

    @Select("SELECT * FROM insight WHERE project_id = #{projectId} ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<InsightEntity> findByProjectIdPaged(@Param("projectId") Long projectId,
                                              @Param("offset") int offset,
                                              @Param("size") int size);

    @Select("SELECT * FROM insight WHERE project_id = #{projectId} AND type = #{type} ORDER BY created_at DESC")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<InsightEntity> findByProjectIdAndType(@Param("projectId") Long projectId, @Param("type") String type);

    @Select("SELECT COUNT(*) FROM insight WHERE project_id = #{projectId} AND type = #{type}")
    long countByProjectIdAndType(@Param("projectId") Long projectId, @Param("type") String type);

    @Select("SELECT * FROM insight WHERE project_id = #{projectId} AND type = #{type} ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<InsightEntity> findByProjectIdAndTypePaged(@Param("projectId") Long projectId,
                                                     @Param("type") String type,
                                                     @Param("offset") int offset,
                                                     @Param("size") int size);

    @Delete("DELETE FROM insight WHERE project_id = #{projectId}")
    int deleteByProjectId(Long projectId);
}
