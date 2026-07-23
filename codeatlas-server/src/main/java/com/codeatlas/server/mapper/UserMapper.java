package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.User;
import org.apache.ibatis.annotations.*;

@Mapper
public interface UserMapper {

    @Select("SELECT * FROM `user` WHERE id = #{id}")
    User findById(Long id);

    @Select("SELECT * FROM `user` WHERE username = #{username}")
    User findByUsername(String username);

    @Select("SELECT * FROM `user` WHERE email = #{email}")
    User findByEmail(String email);

    @Select("SELECT COUNT(*) FROM `user` WHERE username = #{username}")
    int countByUsername(String username);

    @Insert("INSERT INTO `user` (username, password_hash, email, role, status, created_at, updated_at) "
            + "VALUES (#{username}, #{passwordHash}, #{email}, #{role}, #{status}, NOW(), NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(User user);

    @Update("UPDATE `user` SET last_login_at = NOW() WHERE id = #{id}")
    int updateLoginTime(Long id);
}
