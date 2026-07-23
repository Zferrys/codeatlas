package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ScanRecord;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface ScanMapper {

    @Select("SELECT * FROM scan WHERE id = #{id}")
    ScanRecord findById(Long id);

    @Select("SELECT * FROM scan WHERE project_id = #{projectId} ORDER BY created_at DESC")
    List<ScanRecord> findByProjectId(Long projectId);

    @Select("SELECT COUNT(*) FROM scan WHERE project_id = #{projectId}")
    long countByProjectId(Long projectId);

    @Select("SELECT * FROM scan WHERE project_id = #{projectId} ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    List<ScanRecord> findByProjectIdPaged(@Param("projectId") Long projectId,
                                          @Param("offset") int offset,
                                          @Param("size") int size);

    @Select("SELECT * FROM scan WHERE project_id = #{projectId} ORDER BY created_at DESC LIMIT 1")
    ScanRecord findLatestByProjectId(Long projectId);

    @Insert("INSERT INTO scan (project_id, commit_hash, branch, status, total_classes, total_lines, "
            + "total_violations, duration_ms, error_message, started_at, completed_at, created_at) "
            + "VALUES (#{projectId}, #{commitHash}, #{branch}, #{status}, #{totalClasses}, #{totalLines}, "
            + "#{totalViolations}, #{durationMs}, #{errorMessage}, #{startedAt}, #{completedAt}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScanRecord scan);

    @Update("UPDATE scan SET status = #{status}, total_classes = #{totalClasses}, total_lines = #{totalLines}, "
            + "total_violations = #{totalViolations}, duration_ms = #{durationMs}, "
            + "error_message = #{errorMessage}, completed_at = #{completedAt} WHERE id = #{id}")
    int updateStats(ScanRecord scan);

    @Select("<script>SELECT project_id, COUNT(*) as cnt FROM scan WHERE project_id IN "
            + "<foreach collection='list' item='id' open='(' separator=',' close=')'>#{id}</foreach> "
            + "GROUP BY project_id</script>")
    List<Map<String, Object>> countGroupByProjectIds(List<Long> projectIds);
}
