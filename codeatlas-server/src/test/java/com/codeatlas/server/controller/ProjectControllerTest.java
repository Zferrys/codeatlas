package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.request.UpdateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectController 单元测试")
class ProjectControllerTest {

    private static final Long USER_ID = 15L;

    @Mock
    private ProjectService projectService;

    private ProjectController controller;
    private CodeAtlasUserDetails principal;

    @BeforeEach
    void setUp() {
        controller = new ProjectController(projectService);
        principal = new CodeAtlasUserDetails(USER_ID, "frank", "DEVELOPER");
    }

    @Test
    @DisplayName("createProject — 透传请求对象与当前用户 id")
    void createProjectPassesRequestAndUser() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("项目A");
        request.setSourceType("GIT");
        ProjectVO vo = new ProjectVO();
        vo.setId(30L);
        vo.setName("项目A");
        when(projectService.createProject(request, USER_ID)).thenReturn(vo);

        ApiResponse<ProjectVO> response = controller.createProject(request, principal);

        assertEquals(200, response.getCode());
        assertSame(vo, response.getData());
        verify(projectService).createProject(request, USER_ID);
    }

    @Test
    @DisplayName("listProjects — 分页参数与用户 id 精确透传")
    void listProjectsPassesPaging() {
        PageResult<ProjectVO> page = new PageResult<>(List.of(), 42L, 3, 10);
        when(projectService.listProjects(USER_ID, 3, 10)).thenReturn(page);

        ApiResponse<PageResult<ProjectVO>> response = controller.listProjects(principal, 3, 10);

        assertEquals(200, response.getCode());
        assertSame(page, response.getData());
        assertEquals(42L, response.getData().getTotal());
        verify(projectService).listProjects(USER_ID, 3, 10);
    }

    @Test
    @DisplayName("getProject — 按 id 与用户 id 查询")
    void getProjectById() {
        ProjectVO vo = new ProjectVO();
        vo.setId(88L);
        when(projectService.getProjectById(88L, USER_ID)).thenReturn(vo);

        ApiResponse<ProjectVO> response = controller.getProject(88L, principal);

        assertEquals(88L, response.getData().getId());
        verify(projectService).getProjectById(88L, USER_ID);
    }

    @Test
    @DisplayName("deleteProject — 调用删除并返回空数据")
    void deleteProjectReturnsEmptyData() {
        ApiResponse<Void> response = controller.deleteProject(88L, principal);

        assertEquals(200, response.getCode());
        assertEquals("success", response.getMessage());
        assertNull(response.getData());
        verify(projectService).deleteProject(88L, USER_ID);
    }

    @Test
    @DisplayName("updateProject — 拆解 name/description 后按位置透传")
    void updateProjectSplitsFields() {
        UpdateProjectRequest request = new UpdateProjectRequest();
        request.setName("新名字");
        request.setDescription("新描述");
        ProjectVO vo = new ProjectVO();
        vo.setId(88L);
        when(projectService.updateProject(88L, "新名字", "新描述", USER_ID)).thenReturn(vo);

        ApiResponse<ProjectVO> response = controller.updateProject(88L, request, principal);

        assertSame(vo, response.getData());
        verify(projectService).updateProject(88L, "新名字", "新描述", USER_ID);
    }

    @Test
    @DisplayName("addMember — userId/role/操作人三者精确透传")
    void addMemberPassesAllArgs() {
        ApiResponse<Void> response = controller.addMember(88L, 21L, "MAINTAINER", principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
        verify(projectService).addMember(88L, 21L, "MAINTAINER", USER_ID);
    }

    @Test
    @DisplayName("removeMember — 被移除者与操作人区分传递")
    void removeMemberPassesIds() {
        ApiResponse<Void> response = controller.removeMember(88L, 21L, principal);

        assertEquals(200, response.getCode());
        assertNull(response.getData());
        verify(projectService).removeMember(88L, 21L, USER_ID);
    }
}
