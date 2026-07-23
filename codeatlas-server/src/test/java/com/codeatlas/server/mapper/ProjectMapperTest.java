package com.codeatlas.server.mapper;

import com.codeatlas.server.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@MybatisTest
@ActiveProfiles("test")
@Sql(scripts = "classpath:test-schema.sql")
@DisplayName("ProjectMapper 集成测试")
class ProjectMapperTest {

    @Autowired
    private ProjectMapper projectMapper;

    private Project createProject(String name, Long userId) {
        Project p = new Project();
        p.setName(name);
        p.setDescription("Test project");
        p.setSourceType("GIT_URL");
        p.setSourceUrl("https://example.com/" + name + ".git");
        p.setDefaultBranch("main");
        p.setLanguage("Java");
        p.setTotalClasses(10);
        p.setTotalModules(3);
        p.setHealthScore(new BigDecimal("85.50"));
        p.setCreatedBy(userId);
        return p;
    }

    @Test
    @DisplayName("插入项目后能通过 id 查询")
    void shouldInsertAndFindById() {
        Project p = createProject("MyProject", 1L);
        projectMapper.insert(p);

        assertNotNull(p.getId());

        Project found = projectMapper.findById(p.getId());
        assertNotNull(found);
        assertEquals("MyProject", found.getName());
        assertEquals("GIT_URL", found.getSourceType());
    }

    @Test
    @DisplayName("按用户 ID 查询项目列表")
    void shouldFindByUserId() {
        projectMapper.insert(createProject("ProjectA", 1L));
        projectMapper.insert(createProject("ProjectB", 1L));
        projectMapper.insert(createProject("OtherUser", 2L));

        List<Project> projects = projectMapper.findByUserId(1L);
        assertEquals(2, projects.size());
    }

    @Test
    @DisplayName("按用户 ID 统计项目数")
    void shouldCountByUserId() {
        assertEquals(0, projectMapper.countByUserId(1L));

        projectMapper.insert(createProject("P1", 1L));
        projectMapper.insert(createProject("P2", 1L));

        assertEquals(2, projectMapper.countByUserId(1L));
        assertEquals(0, projectMapper.countByUserId(99L));
    }

    @Test
    @DisplayName("分页查询项目")
    void shouldFindPaged() {
        for (int i = 1; i <= 5; i++) {
            projectMapper.insert(createProject("Proj" + i, 1L));
        }

        List<Project> page1 = projectMapper.findByUserIdPaged(1L, 0, 2);
        assertEquals(2, page1.size());

        List<Project> page2 = projectMapper.findByUserIdPaged(1L, 2, 2);
        assertEquals(2, page2.size());
    }

    @Test
    @DisplayName("按名称搜索项目")
    void shouldSearchByName() {
        projectMapper.insert(createProject("SpringBootDemo", 1L));
        projectMapper.insert(createProject("MybatisHelper", 1L));
        projectMapper.insert(createProject("OtherLib", 1L));

        List<Project> results = projectMapper.searchByName("Spring", 10);
        assertEquals(1, results.size());
        assertEquals("SpringBootDemo", results.get(0).getName());

        List<Project> noResults = projectMapper.searchByName("NotFound", 10);
        assertEquals(0, noResults.size());
    }

    @Test
    @DisplayName("删除项目")
    void shouldDeleteProject() {
        Project p = createProject("ToDelete", 1L);
        projectMapper.insert(p);

        assertNotNull(projectMapper.findById(p.getId()));

        projectMapper.deleteById(p.getId());

        assertNull(projectMapper.findById(p.getId()));
    }

    @Test
    @DisplayName("更新项目基本信息")
    void shouldUpdateProject() {
        Project p = createProject("OldName", 1L);
        projectMapper.insert(p);

        p.setName("NewName");
        p.setDescription("Updated");
        projectMapper.update(p);

        Project updated = projectMapper.findById(p.getId());
        assertEquals("NewName", updated.getName());
    }

    @Test
    @DisplayName("更新项目统计信息")
    void shouldUpdateStats() {
        Project p = createProject("StatsProject", 1L);
        projectMapper.insert(p);

        p.setTotalClasses(100);
        p.setTotalModules(10);
        p.setHealthScore(new BigDecimal("95.00"));
        p.setLastScanId(5L);
        projectMapper.updateStats(p);

        Project updated = projectMapper.findById(p.getId());
        assertEquals(100, updated.getTotalClasses());
        assertEquals(new BigDecimal("95.00"), updated.getHealthScore());
        assertEquals(5L, updated.getLastScanId());
    }
}
