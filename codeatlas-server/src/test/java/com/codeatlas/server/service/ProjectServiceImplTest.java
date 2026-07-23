package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectServiceImpl 单元测试")
class ProjectServiceImplTest {

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;
    @Mock
    private ScanMapper scanMapper;
    @Mock
    private InsightMapper insightMapper;
    @Mock
    private ClassSummaryMapper classSummaryMapper;
    @Mock
    private UserMapper userMapper;

    private ProjectServiceImpl projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl(projectMapper, projectMemberMapper,
                scanMapper, insightMapper, classSummaryMapper, userMapper);
    }

    // ========== create ==========

    @Test
    @DisplayName("创建项目成功")
    void shouldCreateProject() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("TestProject");
        request.setDescription("A test project");
        request.setSourceType("GIT_URL");
        request.setSourceUrl("https://github.com/test/test.git");

        // 模拟 MyBatis 自动设置主键
        doAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            p.setId(100L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        ProjectVO result = projectService.createProject(request, 1L);

        assertNotNull(result);
        assertEquals("TestProject", result.getName());
        assertEquals("Java", result.getLanguage());
        verify(projectMapper).insert(any(Project.class));
        verify(projectMemberMapper).insertOwner(100L, 1L);
    }

    @Test
    @DisplayName("名称为空抛异常")
    void shouldRejectEmptyName() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("");

        assertThrows(BusinessException.class,
                () -> projectService.createProject(request, 1L));
    }

    @Test
    @DisplayName("默认 SourceType 为 GIT_URL")
    void shouldDefaultSourceType() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("Test");
        request.setSourceType(null);
        request.setSourceUrl("https://github.com/test/default.git");

        ProjectVO result = projectService.createProject(request, 1L);

        assertEquals("GIT_URL", result.getSourceType());
    }

    // ========== list ==========

    @Test
    @DisplayName("项目列表分页查询")
    void shouldListProjects() {
        Project p1 = createProject(1L, "Proj1");
        Project p2 = createProject(2L, "Proj2");

        when(projectMapper.countByUserId(1L)).thenReturn(2L);
        when(projectMapper.findByUserIdPaged(1L, 0, 10)).thenReturn(Arrays.asList(p1, p2));

        PageResult<ProjectVO> result = projectService.listProjects(1L, 1, 10);

        assertEquals(2L, result.getTotal());
        assertEquals(2, result.getRecords().size());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
    }

    @Test
    @DisplayName("空列表返回空结果")
    void shouldReturnEmptyList() {
        when(projectMapper.countByUserId(1L)).thenReturn(0L);
        when(projectMapper.findByUserIdPaged(1L, 0, 20)).thenReturn(List.of());

        PageResult<ProjectVO> result = projectService.listProjects(1L, 1, 20);

        assertEquals(0L, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
    }

    // ========== get ==========

    @Test
    @DisplayName("获取项目详情")
    void shouldGetProjectById() {
        Project p = createProject(1L, "MyProject");

        when(projectMapper.findById(1L)).thenReturn(p);

        ProjectVO result = projectService.getProjectById(1L, 1L);

        assertEquals("MyProject", result.getName());
    }

    @Test
    @DisplayName("项目不存在抛异常")
    void shouldThrowOnMissingProject() {
        when(projectMapper.findById(999L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> projectService.getProjectById(999L, 1L));
    }

    // ========== delete ==========

    @Test
    @DisplayName("删除项目成功")
    void shouldDeleteProject() {
        Project p = createProject(1L, "ToDelete");

        when(projectMapper.findById(1L)).thenReturn(p);

        assertDoesNotThrow(() -> projectService.deleteProject(1L, 1L));
        verify(projectMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除不存在的项目抛异常")
    void shouldThrowOnDeleteMissing() {
        when(projectMapper.findById(999L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> projectService.deleteProject(999L, 1L));
    }

    // ========== update ==========

    @Test
    @DisplayName("更新项目成功")
    void shouldUpdateProject() {
        Project p = createProject(1L, "OldName");

        when(projectMapper.findById(1L)).thenReturn(p);

        ProjectVO result = projectService.updateProject(1L, "NewName", "New description", 1L);

        assertEquals("NewName", result.getName());
        verify(projectMapper).update(any(Project.class));
    }

    @Test
    @DisplayName("更新时名称为空抛异常")
    void shouldRejectEmptyNameOnUpdate() {
        Project p = createProject(1L, "OldName");

        when(projectMapper.findById(1L)).thenReturn(p);

        assertThrows(BusinessException.class,
                () -> projectService.updateProject(1L, "", "desc", 1L));
    }

    // ========== helper ==========

    private Project createProject(Long id, String name) {
        Project p = new Project();
        p.setId(id);
        p.setName(name);
        p.setDescription("description");
        p.setSourceType("GIT_URL");
        p.setSourceUrl("https://example.com/repo.git");
        p.setLanguage("Java");
        p.setTotalClasses(0);
        p.setTotalModules(0);
        p.setHealthScore(BigDecimal.ZERO);
        p.setCreatedBy(1L);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }
}
