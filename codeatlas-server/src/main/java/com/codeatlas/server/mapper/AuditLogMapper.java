package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

import java.util.List;

@Mapper
public interface AuditLogMapper {

    @Insert("INSERT INTO audit_log (user_id, username, action, target_type, target_id, detail, ip_address, user_agent) "
            + "VALUES (#{userId}, #{username}, #{action}, #{targetType}, #{targetId}, #{detail}, #{ipAddress}, #{userAgent})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLogEntity entity);

    @Select("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditLogEntity> findRecent(int limit);

    @Select("SELECT * FROM audit_log WHERE user_id = #{userId} ORDER BY created_at DESC LIMIT #{limit}")
    List<AuditLogEntity> findByUserId(Long userId, int limit);

    @Select("SELECT * FROM audit_log ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    List<AuditLogEntity> findPaged(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM audit_log")
    long countAll();
}
