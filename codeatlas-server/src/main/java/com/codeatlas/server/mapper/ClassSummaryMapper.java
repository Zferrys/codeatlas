package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ClassSummaryEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

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

    @Insert("<script>" +
            "INSERT INTO class_summary (scan_id, project_id, fqn, simple_name, package_name, " +
            "class_type, layer, public_methods, total_methods, line_count, annotations, dependencies, module_name) VALUES " +
            "<foreach collection='list' item='item' separator=','>" +
            "(#{item.scanId}, #{item.projectId}, #{item.fqn}, #{item.simpleName}, #{item.packageName}, " +
            "#{item.classType}, #{item.layer}, #{item.publicMethods}, #{item.totalMethods}, #{item.lineCount}, " +
            "#{item.annotations}, #{item.dependencies}, #{item.moduleName})" +
            "</foreach>" +
            "</script>")
    int insertBatch(@Param("list") List<ClassSummaryEntity> list);

    @Select("SELECT * FROM class_summary WHERE fqn LIKE CONCAT('%', #{keyword}, '%') "
            + "OR simple_name LIKE CONCAT('%', #{keyword}, '%') "
            + "ORDER BY scan_id DESC LIMIT #{limit}")
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
    List<ClassSummaryEntity> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);

    @Delete("DELETE FROM class_summary WHERE scan_id = #{scanId}")
    int deleteByScanId(Long scanId);

    @Select("SELECT layer, COUNT(*) as cnt FROM class_summary WHERE scan_id = #{scanId} GROUP BY layer")
    List<Map<String, Object>> countLayerByScanId(Long scanId);
}
