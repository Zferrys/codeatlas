package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ProjectMember;
import com.codeatlas.server.entity.ScanRecord;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.mapper.MapViewSnapshotMapper;
import com.codeatlas.server.mapper.ConstitutionRuleMapper;
import com.codeatlas.server.service.impl.ProjectServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    @Mock
    private WorkspaceConfig workspaceConfig;
    @Mock
    private ViolationMapper violationMapper;
    @Mock
    private MapViewSnapshotMapper mapViewSnapshotMapper;
    @Mock
    private ConstitutionRuleMapper constitutionRuleMapper;
    @Mock
    private ChatHistoryService chatHistoryService;

    @TempDir
    Path tempRoot;

    private ProjectServiceImpl projectService;

    @BeforeEach
    void setUp() {
        projectService = new ProjectServiceImpl(projectMapper, projectMemberMapper,
                scanMapper, insightMapper, classSummaryMapper, userMapper, workspaceConfig,
                violationMapper, mapViewSnapshotMapper, constitutionRuleMapper, chatHistoryService);
    }

    @Test
    @DisplayName("创建项目成功")
    void shouldCreateProject() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("TestProject");
        request.setDescription("A test project");
        request.setSourceType("GIT_URL");
        // 用 example.com：部分开发机 hosts 把 github.com 劫持到 127.0.0.1，会被 SourceUrlValidator 判为内网拒绝
        request.setSourceUrl("https://example.com/test/test.git");

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
        request.setSourceUrl("https://example.com/test/default.git");

        ProjectVO result = projectService.createProject(request, 1L);

        assertEquals("GIT_URL", result.getSourceType());
    }

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

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    private User user(Long id, String role) {
        User u = new User();
        u.setId(id);
        u.setUsername("user-" + id);
        u.setRole(role);
        return u;
    }

    private ProjectMember member(Long projectId, Long userId, String role) {
        ProjectMember m = new ProjectMember();
        m.setProjectId(projectId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    private Map<String, Object> countRow(Object projectId, Object count) {
        Map<String, Object> row = new HashMap<>();
        row.put("project_id", projectId);
        row.put("cnt", count);
        return row;
    }

    private Map<String, Object> layerRow(String layer, Object count) {
        Map<String, Object> row = new HashMap<>();
        row.put("layer", layer);
        row.put("cnt", count);
        return row;
    }

    @Test
    @DisplayName("创建项目：不支持的源码类型抛 400")
    void createProject_invalidSourceType_shouldThrow400() {
        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("P");
        request.setSourceType("SVN");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.createProject(request, 1L));

        assertEquals(400, ex.getCode());
        assertEquals("不支持的源码类型", ex.getMessage());
        verifyNoInteractions(projectMapper);
    }

    @Test
    @DisplayName("创建项目：ZIP_UPLOAD 校验本地路径必须位于 uploads 目录内并写入真实路径")
    void createProject_zipUpload_shouldResolvePathInsideUploads() throws IOException {
        Path uploadsRoot = Files.createDirectories(tempRoot.resolve("uploads"));
        Path projectDir = Files.createDirectories(uploadsRoot.resolve("proj-dir"));
        when(workspaceConfig.getUploadsDir()).thenReturn(uploadsRoot);
        doAnswer(invocation -> {
            Project p = invocation.getArgument(0);
            p.setId(11L);
            return 1;
        }).when(projectMapper).insert(any(Project.class));

        CreateProjectRequest request = new CreateProjectRequest();
        request.setName("ZipProj");
        request.setSourceType("ZIP_UPLOAD");
        request.setSourceUrl(projectDir.toString());

        ProjectVO result = projectService.createProject(request, 1L);

        assertEquals("ZIP_UPLOAD", result.getSourceType());
        assertEquals(projectDir.toRealPath().toString(), result.getSourceUrl());
        verify(projectMemberMapper).insertOwner(11L, 1L);
    }

    @Test
    @DisplayName("获取项目实体：命中返回实体，未命中返回 null")
    void getProjectEntity_shouldDelegateToMapper() {
        Project p = createProject(1L, "MyProject");
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMapper.findById(2L)).thenReturn(null);

        assertSame(p, projectService.getProjectEntity(1L));
        assertNull(projectService.getProjectEntity(2L));
    }

    @Test
    @DisplayName("项目列表：扫描数与洞察数按项目聚合映射，缺失项目取 0")
    void listProjects_shouldMapScanAndInsightCounts() {
        Project p1 = createProject(1L, "Proj1");
        Project p2 = createProject(2L, "Proj2");
        when(projectMapper.countByUserId(1L)).thenReturn(2L);
        when(projectMapper.findByUserIdPaged(1L, 0, 10)).thenReturn(Arrays.asList(p1, p2));
        when(scanMapper.countGroupByProjectIds(anyList()))
                .thenReturn(List.of(countRow(1L, 5L), countRow(2L, 2L)));
        when(insightMapper.countGroupByProjectIds(anyList()))
                .thenReturn(List.of(countRow(1L, 1L)));

        PageResult<ProjectVO> result = projectService.listProjects(1L, 1, 10);

        assertEquals(5, result.getRecords().get(0).getTotalScans());
        assertEquals(1, result.getRecords().get(0).getTotalInsights());
        assertEquals(2, result.getRecords().get(1).getTotalScans());
        assertEquals(0, result.getRecords().get(1).getTotalInsights());
    }

    @Test
    @DisplayName("项目列表：存在最近扫描时填充扫描时间与层级分布（含缺失层级名与配色循环）")
    void listProjects_projectWithLastScan_shouldFillLayerDistribution() {
        Project p = createProject(1L, "Proj1");
        p.setLastScanId(500L);
        when(projectMapper.countByUserId(1L)).thenReturn(1L);
        when(projectMapper.findByUserIdPaged(1L, 0, 10)).thenReturn(List.of(p));
        var scan = new ScanRecord();
        scan.setId(500L);
        scan.setCreatedAt(LocalDateTime.of(2026, 5, 6, 7, 8, 9));
        when(scanMapper.findById(500L)).thenReturn(scan);
        when(classSummaryMapper.countLayerByScanId(500L)).thenReturn(List.of(
                layerRow("service", 3L),
                layerRow(null, 1L)));

        PageResult<ProjectVO> result = projectService.listProjects(1L, 1, 10);

        ProjectVO vo = result.getRecords().get(0);
        assertEquals(LocalDateTime.of(2026, 5, 6, 7, 8, 9), vo.getLastScanTime());
        assertEquals(2, vo.getLayerDistribution().size());
        ProjectVO.LayerItem first = vo.getLayerDistribution().get(0);
        assertEquals("service", first.getName());
        assertEquals(3, first.getCount());
        assertEquals(75, first.getPercent());
        assertEquals("#667eea", first.getColor());
        ProjectVO.LayerItem second = vo.getLayerDistribution().get(1);
        assertEquals("Unknown", second.getName());
        assertEquals(1, second.getCount());
        assertEquals(25, second.getPercent());
        assertEquals("#52c41a", second.getColor());
    }

    @Test
    @DisplayName("项目列表：层级统计总数为 0 时占比取 0 防止除零")
    void listProjects_zeroLayerCounts_shouldUseZeroPercent() {
        Project p = createProject(1L, "Proj1");
        p.setLastScanId(500L);
        when(projectMapper.countByUserId(1L)).thenReturn(1L);
        when(projectMapper.findByUserIdPaged(1L, 0, 10)).thenReturn(List.of(p));
        when(scanMapper.findById(500L)).thenReturn(null);
        when(classSummaryMapper.countLayerByScanId(500L))
                .thenReturn(List.of(layerRow("controller", 0L)));

        PageResult<ProjectVO> result = projectService.listProjects(1L, 1, 10);

        ProjectVO vo = result.getRecords().get(0);
        assertNull(vo.getLastScanTime());
        assertEquals(0, vo.getLayerDistribution().get(0).getPercent());
        assertEquals(0, vo.getLayerDistribution().get(0).getCount());
    }

    @Test
    @DisplayName("项目详情：最近访问时间更新失败被吞掉，仍正常返回")
    void getProjectById_updateLastAccessFails_shouldStillReturnVO() {
        Project p = createProject(1L, "MyProject");
        when(projectMapper.findById(1L)).thenReturn(p);
        doThrow(new RuntimeException("db locked")).when(projectMapper).updateLastAccessedAt(1L);
        when(scanMapper.countByProjectId(1L)).thenReturn(2L);
        when(insightMapper.countByProjectId(1L)).thenReturn(3L);

        ProjectVO result = projectService.getProjectById(1L, 1L);

        assertEquals(2, result.getTotalScans());
        assertEquals(3, result.getTotalInsights());
    }

    @Test
    @DisplayName("项目详情：非创建者的项目成员可查看")
    void getProjectById_byMember_shouldPass() {
        Project p = createProject(1L, "MyProject");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "DEVELOPER"));
        when(projectMapper.updateLastAccessedAt(1L)).thenReturn(1);

        ProjectVO result = projectService.getProjectById(1L, 1L);

        assertEquals("MyProject", result.getName());
        verify(userMapper, never()).findById(anyLong());
    }

    @Test
    @DisplayName("项目详情：系统管理员可查看")
    void getProjectById_byAdmin_shouldPass() {
        Project p = createProject(1L, "MyProject");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN"));

        ProjectVO result = projectService.getProjectById(1L, 1L);

        assertEquals("MyProject", result.getName());
    }

    @Test
    @DisplayName("项目详情：非成员非管理员抛 403（用户不存在同样 403）")
    void getProjectById_noPermission_shouldThrow403() {
        Project p = createProject(1L, "MyProject");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);

        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.getProjectById(1L, 1L));
        assertEquals(403, ex.getCode());
        assertEquals("无权查看该项目，仅项目创建者、成员或管理员可操作", ex.getMessage());

        when(userMapper.findById(1L)).thenReturn(null);
        assertThrows(BusinessException.class, () -> projectService.getProjectById(1L, 1L));
    }

    @Test
    @DisplayName("删除项目：系统管理员可删除并级联清理全部子表")
    void deleteProject_byAdmin_shouldDeleteAndCascade() {
        Project p = createProject(1L, "ToDelete");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN"));

        projectService.deleteProject(1L, 1L);

        verify(violationMapper).deleteByProjectId(1L);
        verify(classSummaryMapper).deleteByProjectId(1L);
        verify(scanMapper).deleteByProjectId(1L);
        verify(insightMapper).deleteByProjectId(1L);
        verify(projectMemberMapper).deleteByProjectId(1L);
        verify(mapViewSnapshotMapper).deleteByProjectId(1L);
        verify(constitutionRuleMapper).deleteByProjectId(1L);
        verify(chatHistoryService).deleteByProjectId(1L);
        verify(projectMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除项目：非创建者且非管理员抛 403")
    void deleteProject_notOwnerNotAdmin_shouldThrow403() {
        Project p = createProject(1L, "ToDelete");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.deleteProject(1L, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("只有项目创建者或管理员才能删除项目", ex.getMessage());
        verify(projectMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("删除项目：工作区清理抛异常被吞掉，删除流程继续")
    void deleteProject_workspaceCleanupFails_shouldStillDelete() {
        Project p = createProject(1L, "ToDelete");
        when(projectMapper.findById(1L)).thenReturn(p);
        when(workspaceConfig.getUploadsDir()).thenThrow(new RuntimeException("fs error"));

        assertDoesNotThrow(() -> projectService.deleteProject(1L, 1L));

        verify(projectMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除项目：按 项目ID_/项目ID- 前缀清理 uploads 与 scans（含嵌套子目录）")
    void deleteProject_shouldCleanWorkspaceFilesForProjectId() throws IOException {
        Project p = createProject(1L, "ToDelete");
        when(projectMapper.findById(1L)).thenReturn(p);

        Path uploads = Files.createDirectories(tempRoot.resolve("uploads"));
        Files.writeString(uploads.resolve("1_upload.zip"), "x");
        Path memberDir = Files.createDirectories(uploads.resolve("1-dir"));
        Files.writeString(memberDir.resolve("nested.txt"), "x");
        Files.writeString(uploads.resolve("2_keep.txt"), "x");
        Files.writeString(uploads.resolve("other.txt"), "x");
        Path scans = Files.createDirectories(tempRoot.resolve("scans"));
        Path scanDir = Files.createDirectories(scans.resolve("1-scan"));
        Files.writeString(scanDir.resolve("code.java"), "x");
        Files.createDirectories(scans.resolve("2-scan"));
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);
        when(workspaceConfig.getScansDir()).thenReturn(scans);

        projectService.deleteProject(1L, 1L);

        assertFalse(Files.exists(uploads.resolve("1_upload.zip")));
        assertFalse(Files.exists(uploads.resolve("1-dir")));
        assertFalse(Files.exists(scans.resolve("1-scan")));
        assertTrue(Files.exists(uploads.resolve("2_keep.txt")));
        assertTrue(Files.exists(uploads.resolve("other.txt")));
        assertTrue(Files.exists(scans.resolve("2-scan")));
        verify(projectMapper).deleteById(1L);
    }

    @Test
    @DisplayName("更新项目：项目不存在抛 404")
    void updateProject_missingProject_shouldThrow404() {
        when(projectMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateProject(999L, "New", "desc", 1L));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("更新项目：项目成员可修改")
    void updateProject_byMember_shouldSucceed() {
        Project p = createProject(1L, "OldName");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "DEVELOPER"));
        when(scanMapper.countByProjectId(1L)).thenReturn(1L);
        when(insightMapper.countByProjectId(1L)).thenReturn(2L);

        ProjectVO result = projectService.updateProject(1L, "NewName", "desc", 1L);

        assertEquals("NewName", result.getName());
        assertEquals(1, result.getTotalScans());
        assertEquals(2, result.getTotalInsights());
    }

    @Test
    @DisplayName("更新项目：非成员非管理员抛 403（修改语义）")
    void updateProject_noPermission_shouldThrow403() {
        Project p = createProject(1L, "OldName");
        p.setCreatedBy(99L);
        when(projectMapper.findById(1L)).thenReturn(p);
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "VIEWER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.updateProject(1L, "New", "desc", 1L));

        assertEquals(403, ex.getCode());
        assertEquals("无权修改该项目，仅项目创建者、成员或管理员可操作", ex.getMessage());
        verify(projectMapper, never()).update(any(Project.class));
    }

    @Test
    @DisplayName("添加成员：项目不存在抛 404")
    void addMember_missingProject_shouldThrow404() {
        when(projectMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.addMember(999L, 2L, "DEVELOPER", 1L));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("添加成员：操作者非负责人非管理员抛 403")
    void addMember_operatorNotOwnerOrAdmin_shouldThrow403() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.addMember(1L, 2L, "DEVELOPER", 1L));

        assertEquals(403, ex.getCode());
        assertEquals("只有项目负责人或管理员才能添加成员", ex.getMessage());
    }

    @Test
    @DisplayName("添加成员：负责人操作成功插入成员")
    void addMember_ownerOperator_shouldInsertMember() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "OWNER"));
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(null);

        projectService.addMember(1L, 2L, "VIEWER", 1L);

        verify(projectMemberMapper).insert(1L, 2L, "VIEWER");
    }

    @Test
    @DisplayName("添加成员：系统管理员操作成功插入成员")
    void addMember_systemAdminOperator_shouldInsertMember() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(null);

        projectService.addMember(1L, 2L, "ARCHITECT", 1L);

        verify(projectMemberMapper).insert(1L, 2L, "ARCHITECT");
    }

    @Test
    @DisplayName("添加成员：目标用户已是成员抛 400")
    void addMember_existingMember_shouldThrow400() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "OWNER"));
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "VIEWER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.addMember(1L, 2L, "DEVELOPER", 1L));

        assertEquals(400, ex.getCode());
        assertEquals("该用户已是项目成员", ex.getMessage());
        verify(projectMemberMapper, never()).insert(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("添加成员：非法角色抛 400 且消息列出可选角色")
    void addMember_invalidRole_shouldThrow400() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "OWNER"));
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.addMember(1L, 2L, "GUEST", 1L));

        assertEquals(400, ex.getCode());
        assertEquals("无效的项目角色: GUEST，可选: ARCHITECT, DEVELOPER, VIEWER", ex.getMessage());
    }

    @Test
    @DisplayName("添加成员：操作者用户记录缺失时 findById().getRole() 抛 NPE（主代码隐患，见报告）")
    void addMember_operatorUserMissing_shouldThrowNpe() {
        // 记录当前实现行为：L292 未做 null 保护，用户表缺行时抛 NPE 而非 403。
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(null);

        assertThrows(NullPointerException.class,
                () -> projectService.addMember(1L, 2L, "DEVELOPER", 1L));
    }

    @Test
    @DisplayName("移除成员：项目不存在抛 404")
    void removeMember_missingProject_shouldThrow404() {
        when(projectMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.removeMember(999L, 2L, 1L));

        assertEquals(404, ex.getCode());
        assertEquals("项目不存在", ex.getMessage());
    }

    @Test
    @DisplayName("移除成员：目标用户不是成员抛 404")
    void removeMember_targetNotMember_shouldThrow404() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.removeMember(1L, 2L, 1L));

        assertEquals(404, ex.getCode());
        assertEquals("该用户不是项目成员", ex.getMessage());
    }

    @Test
    @DisplayName("移除成员：目标是 OWNER 抛 400 不能移除创建者")
    void removeMember_targetIsOwner_shouldThrow400() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "OWNER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.removeMember(1L, 2L, 1L));

        assertEquals(400, ex.getCode());
        assertEquals("不能移除项目创建者", ex.getMessage());
        verify(projectMemberMapper, never()).delete(anyLong(), anyLong());
    }

    @Test
    @DisplayName("移除成员：操作者非负责人非管理员抛 403")
    void removeMember_operatorNotOwnerOrAdmin_shouldThrow403() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> projectService.removeMember(1L, 2L, 1L));

        assertEquals(403, ex.getCode());
        assertEquals("只有项目负责人或管理员才能移除成员", ex.getMessage());
    }

    @Test
    @DisplayName("移除成员：负责人操作成功删除成员")
    void removeMember_ownerOperator_shouldDelete() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(member(1L, 1L, "OWNER"));
        when(userMapper.findById(1L)).thenReturn(user(1L, "DEVELOPER"));

        projectService.removeMember(1L, 2L, 1L);

        verify(projectMemberMapper).delete(1L, 2L);
    }

    @Test
    @DisplayName("移除成员：系统管理员操作成功删除成员")
    void removeMember_systemAdminOperator_shouldDelete() {
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "VIEWER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(user(1L, "ADMIN"));

        projectService.removeMember(1L, 2L, 1L);

        verify(projectMemberMapper).delete(1L, 2L);
    }

    @Test
    @DisplayName("移除成员：操作者用户记录缺失时 findById().getRole() 抛 NPE（主代码隐患，见报告）")
    void removeMember_operatorUserMissing_shouldThrowNpe() {
        // 记录当前实现行为：L329 未做 null 保护，用户表缺行时抛 NPE 而非 403。
        when(projectMapper.findById(1L)).thenReturn(createProject(1L, "P"));
        when(projectMemberMapper.findByProjectAndUser(1L, 2L)).thenReturn(member(1L, 2L, "DEVELOPER"));
        when(projectMemberMapper.findByProjectAndUser(1L, 1L)).thenReturn(null);
        when(userMapper.findById(1L)).thenReturn(null);

        assertThrows(NullPointerException.class,
                () -> projectService.removeMember(1L, 2L, 1L));
    }

    @Test
    @DisplayName("删除项目：工作区文件被占用删除失败时记录告警并继续删除流程")
    void deleteProject_workspaceFileLocked_shouldLogAndContinue() throws IOException {
        Project p = createProject(1L, "ToDelete");
        when(projectMapper.findById(1L)).thenReturn(p);
        Path uploads = Files.createDirectories(tempRoot.resolve("uploads"));
        Path locked = Files.writeString(uploads.resolve("1-locked.txt"), "x");
        when(workspaceConfig.getUploadsDir()).thenReturn(uploads);

        // Windows 下用 java.io.FileInputStream 持有句柄（未共享 DELETE）会让 Files.deleteIfExists
        // 抛 AccessDeniedException，命中 deleteRecursive 的 IOException 兜底分支
        try (var ignored = new FileInputStream(locked.toFile())) {
            projectService.deleteProject(1L, 1L);
        }

        verify(projectMapper).deleteById(1L);
        assertTrue(Files.exists(locked), "占用中的文件不应被删除");
        Files.deleteIfExists(locked);
    }
}
