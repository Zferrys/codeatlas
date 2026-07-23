package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.Project;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ProjectMapper {

    @Select("SELECT * FROM project WHERE id = #{id}")
    Project findById(Long id);

    @Select("SELECT * FROM project WHERE created_by = #{userId} ORDER BY updated_at DESC")
    List<Project> findByUserId(Long userId);

    @Select("SELECT COUNT(*) FROM project WHERE created_by = #{userId}")
    long countByUserId(Long userId);

    @Select("SELECT * FROM project WHERE created_by = #{userId} ORDER BY updated_at DESC LIMIT #{offset}, #{size}")
    List<Project> findByUserIdPaged(@Param("userId") Long userId,
                                    @Param("offset") int offset,
                                    @Param("size") int size);

    @Insert("INSERT INTO project (name, description, source_type, source_url, default_branch, language, "
            + "total_classes, total_modules, health_score, last_scan_id, created_by, created_at, updated_at) "
            + "VALUES (#{name}, #{description}, #{sourceType}, #{sourceUrl}, #{defaultBranch}, #{language}, "
            + "#{totalClasses}, #{totalModules}, #{healthScore}, #{lastScanId}, #{createdBy}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Project project);

    @Update("UPDATE project SET name = #{name}, description = #{description}, "
            + "source_url = #{sourceUrl}, updated_at = NOW() WHERE id = #{id}")
    int update(Project project);

    @Update("UPDATE project SET total_classes = #{totalClasses}, total_modules = #{totalModules}, "
            + "health_score = #{healthScore}, last_scan_id = #{lastScanId} WHERE id = #{id}")
    int updateStats(Project project);

    @Select("SELECT * FROM project WHERE name LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY updated_at DESC LIMIT #{limit}")
    List<Project> searchByName(@Param("keyword") String keyword, @Param("limit") int limit);

    @Delete("DELETE FROM project WHERE id = #{id}")
    int deleteById(Long id);
}
