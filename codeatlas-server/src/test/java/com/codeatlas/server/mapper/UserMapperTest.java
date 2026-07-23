package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.User;
import org.junit.jupiter.api.*;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import static org.junit.jupiter.api.Assertions.*;

@MybatisTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:test-schema.sql")
@DisplayName("UserMapper 集成测试")
class UserMapperTest {

    @Autowired
    private UserMapper userMapper;

    private User createTestUser() {
        User user = new User();
        user.setUsername("testuser");
        user.setPasswordHash("$2a$10$hashedpasswordvaluehere");
        user.setEmail("test@example.com");
        user.setRole("DEVELOPER");
        user.setStatus(1);
        return user;
    }

    @Test
    @DisplayName("插入用户后能通过 id 查询")
    void shouldInsertAndFindById() {
        User user = createTestUser();
        int rows = userMapper.insert(user);

        assertEquals(1, rows);
        assertNotNull(user.getId());

        User found = userMapper.findById(user.getId());
        assertNotNull(found);
        assertEquals("testuser", found.getUsername());
        assertEquals("DEVELOPER", found.getRole());
    }

    @Test
    @DisplayName("通过 username 查询用户")
    void shouldFindByUsername() {
        userMapper.insert(createTestUser());

        User found = userMapper.findByUsername("testuser");
        assertNotNull(found);
        assertEquals("test@example.com", found.getEmail());
    }

    @Test
    @DisplayName("查询不存在的用户名返回 null")
    void shouldReturnNullForUnknownUser() {
        assertNull(userMapper.findByUsername("nobody"));
    }

    @Test
    @DisplayName("通过 email 查询用户")
    void shouldFindByEmail() {
        userMapper.insert(createTestUser());

        User found = userMapper.findByEmail("test@example.com");
        assertNotNull(found);
        assertEquals("testuser", found.getUsername());
    }

    @Test
    @DisplayName("countByUsername 正确计数")
    void shouldCountByUsername() {
        assertEquals(0, userMapper.countByUsername("testuser"));

        userMapper.insert(createTestUser());

        assertEquals(1, userMapper.countByUsername("testuser"));
        assertEquals(0, userMapper.countByUsername("other"));
    }

    @Test
    @DisplayName("更新最后登录时间")
    void shouldUpdateLoginTime() {
        User user = createTestUser();
        userMapper.insert(user);

        int rows = userMapper.updateLoginTime(user.getId());
        assertEquals(1, rows);
    }
}
