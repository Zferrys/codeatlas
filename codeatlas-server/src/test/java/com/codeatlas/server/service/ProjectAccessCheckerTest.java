package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ProjectMember;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectAccessChecker 单元测试")
class ProjectAccessCheckerTest {

    private static final Long PROJECT_ID = 1L;
    private static final Long CREATOR_ID = 7L;
    private static final Long OTHER_USER_ID = 8L;

    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private ProjectMemberMapper projectMemberMapper;

    @InjectMocks
    private ProjectAccessChecker checker;

    private Project project() {
        Project p = new Project();
        p.setId(PROJECT_ID);
        p.setCreatedBy(CREATOR_ID);
        return p;
    }

    @Test
    @DisplayName("check(projectId)：创建者可访问，不查成员表")
    void check_creator_shouldPassWithoutMemberQuery() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project());

        assertDoesNotThrow(() -> checker.check(PROJECT_ID, CREATOR_ID));

        verify(projectMemberMapper, never()).findByProjectAndUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("check(projectId)：项目不存在抛 404 项目不存在")
    void check_missingProject_shouldThrow404() {
        when(projectMapper.findById(99L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.check(99L, CREATOR_ID));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("check(projectId)：userId 为 null 统一按 404 处理，不查成员表")
    void check_nullUserId_shouldThrow404() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.check(PROJECT_ID, null));

        assertEquals(404, ex.getCode());
        verify(projectMemberMapper, never()).findByProjectAndUser(anyLong(), anyLong());
    }

    @Test
    @DisplayName("check(projectId)：项目成员可通过")
    void check_member_shouldPass() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project());
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID))
                .thenReturn(new ProjectMember());

        assertDoesNotThrow(() -> checker.check(PROJECT_ID, OTHER_USER_ID));
    }

    @Test
    @DisplayName("check(projectId)：非创建者且非成员抛 404（不暴露项目存在性）")
    void check_notMember_shouldThrow404() {
        when(projectMapper.findById(PROJECT_ID)).thenReturn(project());
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.check(PROJECT_ID, OTHER_USER_ID));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("check(project,...) 重载：创建者直接通过，不查库")
    void check_withProvidedProject_creator_shouldPassWithoutQueries() {
        assertDoesNotThrow(() -> checker.check(project(), PROJECT_ID, CREATOR_ID));

        verifyNoInteractions(projectMapper);
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("check(project,...) 重载：project 为 null 抛 404")
    void check_withNullProject_shouldThrow404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.check(null, PROJECT_ID, CREATOR_ID));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
        verifyNoInteractions(projectMapper);
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("check(project,...) 重载：userId 为 null 抛 404 且不查成员表")
    void check_withNullUserId_shouldThrow404() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> checker.check(project(), PROJECT_ID, null));

        assertEquals(404, ex.getCode());
        verifyNoInteractions(projectMemberMapper);
    }

    @Test
    @DisplayName("check(project,...) 重载：成员可通过，非成员抛 404")
    void check_withProvidedProject_memberAndNonMember() {
        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, OTHER_USER_ID))
                .thenReturn(new ProjectMember());

        assertDoesNotThrow(() -> checker.check(project(), PROJECT_ID, OTHER_USER_ID));

        when(projectMemberMapper.findByProjectAndUser(PROJECT_ID, 9L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> checker.check(project(), PROJECT_ID, 9L));
    }
}
