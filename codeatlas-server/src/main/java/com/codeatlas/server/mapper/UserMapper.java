package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.User;
import org.apache.ibatis.annotations.*;

import java.util.List;

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

    @Update("UPDATE `user` SET password_hash = #{newHash} WHERE id = #{id}")
    int updatePassword(@Param("id") Long id, @Param("newHash") String newHash);

    // --- Admin operations ---

    @Select("SELECT * FROM `user` ORDER BY created_at DESC LIMIT #{offset}, #{size}")
    List<User> findAllPaged(@Param("offset") int offset, @Param("size") int size);

    @Select("SELECT COUNT(*) FROM `user`")
    long countAll();

    @Update("UPDATE `user` SET role = #{role} WHERE id = #{id}")
    int updateRole(@Param("id") Long id, @Param("role") String role);

    @Update("UPDATE `user` SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") int status);
}
