package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ClassSummaryMapper {

    @Insert("INSERT INTO class_summary (scan_id, project_id, fqn, simple_name, package_name, " +
            "class_type, layer, public_methods, total_methods, line_count, annotations, dependencies, module_name) " +
            "VALUES (#{scanId}, #{projectId}, #{fqn}, #{simpleName}, #{packageName}, " +
            "#{classType}, #{layer}, #{publicMethods}, #{totalMethods}, #{lineCount}, " +
            "#{annotations}, #{dependencies}, #{moduleName})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ClassSummaryEntity entity);

    @Select("SELECT * FROM class_summary WHERE scan_id = #{scanId}")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "simpleName", column = "simple_name"),
            @Result(property = "packageName", column = "package_name"),
            @Result(property = "classType", column = "class_type"),
            @Result(property = "publicMethods", column = "public_methods"),
            @Result(property = "totalMethods", column = "total_methods"),
            @Result(property = "lineCount", column = "line_count"),
            @Result(property = "moduleName", column = "module_name"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<ClassSummaryEntity> findByScanId(Long scanId);

    @Select("SELECT * FROM class_summary WHERE project_id = #{projectId} ORDER BY scan_id DESC, id ASC")
    @Results({
            @Result(property = "scanId", column = "scan_id"),
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "simpleName", column = "simple_name"),
            @Result(property = "packageName", column = "package_name"),
            @Result(property = "classType", column = "class_type"),
            @Result(property = "publicMethods", column = "public_methods"),
            @Result(property = "totalMethods", column = "total_methods"),
            @Result(property = "lineCount", column = "line_count"),
            @Result(property = "moduleName", column = "module_name"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<ClassSummaryEntity> findByProjectId(Long projectId);

    @Delete("DELETE FROM class_summary WHERE scan_id = #{scanId}")
    int deleteByScanId(Long scanId);
}
