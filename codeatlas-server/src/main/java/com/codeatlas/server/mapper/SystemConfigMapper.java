package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.SystemConfigEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SystemConfigMapper {

    @Select("SELECT * FROM system_config WHERE config_key = #{configKey}")
    SystemConfigEntity findByKey(@Param("configKey") String configKey);

    @Insert("INSERT INTO system_config (config_key, config_value, description) "
            + "VALUES (#{configKey}, #{configValue}, #{description}) "
            + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)")
    int upsert(SystemConfigEntity entity);
}
