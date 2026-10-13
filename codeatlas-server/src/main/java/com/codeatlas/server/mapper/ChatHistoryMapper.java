package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.ChatHistoryEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ChatHistoryMapper {

    @Insert("INSERT INTO chat_history (project_id, user_id, scope, context_fqn, question, answer, verification) " +
            "VALUES (#{projectId}, #{userId}, #{scope}, #{contextFqn}, #{question}, #{answer}, #{verification})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ChatHistoryEntity entity);

    @Select("SELECT * FROM chat_history WHERE project_id = #{projectId} AND user_id = #{userId} AND scope = #{scope} " +
            "ORDER BY id DESC LIMIT #{limit}")
    @Results({
            @Result(property = "projectId", column = "project_id"),
            @Result(property = "userId", column = "user_id"),
            @Result(property = "contextFqn", column = "context_fqn"),
            @Result(property = "createdAt", column = "created_at")
    })
    List<ChatHistoryEntity> findByUserAndScope(@Param("projectId") Long projectId,
                                               @Param("userId") Long userId,
                                               @Param("scope") String scope,
                                               @Param("limit") int limit);

    @Delete("DELETE FROM chat_history WHERE id = #{id} AND user_id = #{userId}")
    int deleteByIdAndUser(@Param("id") Long id, @Param("userId") Long userId);

    @Delete("DELETE FROM chat_history WHERE project_id = #{projectId}")
    int deleteByProjectId(Long projectId);

    @Delete("DELETE FROM chat_history WHERE user_id = #{userId} AND project_id = #{projectId} AND scope = #{scope} " +
            "AND id NOT IN (SELECT id FROM (SELECT id FROM chat_history " +
            "WHERE user_id = #{userId} AND project_id = #{projectId} AND scope = #{scope} " +
            "ORDER BY id DESC LIMIT #{keep}) AS keepers)")
    int pruneBeyondLatest(@Param("userId") Long userId,
                          @Param("projectId") Long projectId,
                          @Param("scope") String scope,
                          @Param("keep") int keep);
}
