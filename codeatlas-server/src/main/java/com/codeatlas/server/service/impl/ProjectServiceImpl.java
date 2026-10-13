package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.config.WorkspaceConfig;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ProjectMember;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.ConstitutionRuleMapper;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.mapper.MapViewSnapshotMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.mapper.ViolationMapper;
import com.codeatlas.server.service.ChatHistoryService;
import com.codeatlas.server.service.ProjectService;
import com.codeatlas.server.util.SourceUrlValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final ScanMapper scanMapper;
    private final InsightMapper insightMapper;
    private final ClassSummaryMapper classSummaryMapper;
    private final UserMapper userMapper;
    private final WorkspaceConfig workspaceConfig;
    private final ViolationMapper violationMapper;
    private final MapViewSnapshotMapper mapViewSnapshotMapper;
    private final ConstitutionRuleMapper constitutionRuleMapper;
    private final ChatHistoryService chatHistoryService;

    public ProjectServiceImpl(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                              ScanMapper scanMapper, InsightMapper insightMapper,
                              ClassSummaryMapper classSummaryMapper, UserMapper userMapper,
                              WorkspaceConfig workspaceConfig,
                              ViolationMapper violationMapper,
                              MapViewSnapshotMapper mapViewSnapshotMapper,
                              ConstitutionRuleMapper constitutionRuleMapper,
                              ChatHistoryService chatHistoryService) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.scanMapper = scanMapper;
        this.insightMapper = insightMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.userMapper = userMapper;
        this.workspaceConfig = workspaceConfig;
        this.violationMapper = violationMapper;
        this.mapViewSnapshotMapper = mapViewSnapshotMapper;
        this.constitutionRuleMapper = constitutionRuleMapper;
        this.chatHistoryService = chatHistoryService;
    }

    @Override
    @Transactional
    public ProjectVO createProject(CreateProjectRequest request, Long userId) {
        if (!StringUtils.hasText(request.getName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }

        String sourceType = StringUtils.hasText(request.getSourceType())
                ? request.getSourceType() : "GIT_URL";

        if (!"GIT_URL".equals(sourceType) && !"ZIP_UPLOAD".equals(sourceType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的源码类型");
        }

        String resolvedSourceUrl = request.getSourceUrl();
        if ("GIT_URL".equals(sourceType)) {
            SourceUrlValidator.validateGitUrl(resolvedSourceUrl);
        } else {
            // 上传流程写入的本地路径必须真实存在且落在工作区 uploads 目录内
            resolvedSourceUrl = SourceUrlValidator
                    .resolveLocalPathInside(workspaceConfig.getUploadsDir(), resolvedSourceUrl)
                    .toString();
        }

        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setSourceType(sourceType);
        project.setSourceUrl(resolvedSourceUrl);
        project.setDefaultBranch("main");
        project.setLanguage("Java");
        project.setTotalClasses(0);
        project.setTotalModules(0);
        project.setHealthScore(BigDecimal.ZERO);
        project.setCreatedBy(userId);

        projectMapper.insert(project);

        projectMemberMapper.insertOwner(project.getId(), userId);
        log.info("Project created: id={}, name={}, userId={}", project.getId(), project.getName(), userId);

        return toVO(project, 0, 0);
    }

    @Override
    public PageResult<ProjectVO> listProjects(Long userId, int page, int size) {
        long total = projectMapper.countByUserId(userId);
        int offset = (page - 1) * size;
        List<Project> projects = projectMapper.findByUserIdPaged(userId, offset, size);

        List<Long> projectIds = projects.stream().map(Project::getId).collect(Collectors.toList());
        Map<Long, Long> scanCounts = new HashMap<>();
        Map<Long, Long> insightCounts = new HashMap<>();
        if (!projectIds.isEmpty()) {
            for (Map<String, Object> row : scanMapper.countGroupByProjectIds(projectIds)) {
                Long pid = ((Number) row.get("project_id")).longValue();
                Long cnt = ((Number) row.get("cnt")).longValue();
                scanCounts.put(pid, cnt);
            }
            for (Map<String, Object> row : insightMapper.countGroupByProjectIds(projectIds)) {
                Long pid = ((Number) row.get("project_id")).longValue();
                Long cnt = ((Number) row.get("cnt")).longValue();
                insightCounts.put(pid, cnt);
            }
        }

        List<ProjectVO> records = projects.stream()
                .map(p -> toVO(p, scanCounts.getOrDefault(p.getId(), 0L).intValue(),
                        insightCounts.getOrDefault(p.getId(), 0L).intValue()))
                .collect(Collectors.toList());
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public ProjectVO getProjectById(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        checkProjectAccess(project, userId, "查看");
        // 更新最后访问时间
        try {
            projectMapper.updateLastAccessedAt(projectId);
        } catch (Exception e) {
            log.debug("Failed to update lastAccessedAt for projectId={}: {}", projectId, e.getMessage());
        }
        long scanCount = scanMapper.countByProjectId(projectId);
        long insightCount = insightMapper.countByProjectId(projectId);
        return toVO(project, (int) scanCount, (int) insightCount);
    }

    @Override
    public Project getProjectEntity(Long projectId) {
        return projectMapper.findById(projectId);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        boolean isOwner = userId.equals(project.getCreatedBy());
        if (!isOwner) {
            User user = userMapper.findById(userId);
            boolean isAdmin = user != null && "ADMIN".equals(user.getRole());
            if (!isAdmin) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "只有项目创建者或管理员才能删除项目");
            }
        }
        // 清理工作空间文件
        try {
            cleanWorkspaceFiles(projectId);
        } catch (Exception e) {
            log.warn("Failed to clean workspace files for projectId={}: {}", projectId, e.getMessage());
        }
        // 级联清理子表（无外键约束，需显式删除，避免孤儿数据）
        violationMapper.deleteByProjectId(projectId);
        classSummaryMapper.deleteByProjectId(projectId);
        scanMapper.deleteByProjectId(projectId);
        insightMapper.deleteByProjectId(projectId);
        projectMemberMapper.deleteByProjectId(projectId);
        mapViewSnapshotMapper.deleteByProjectId(projectId);
        constitutionRuleMapper.deleteByProjectId(projectId);
        chatHistoryService.deleteByProjectId(projectId);
        projectMapper.deleteById(projectId);
        log.info("Project deleted: id={}, userId={}", projectId, userId);
    }

    private void cleanWorkspaceFiles(Long projectId) {
        String prefix = String.valueOf(projectId);
        // 清理上传目录中属于该项目的文件
        Path uploadsDir = workspaceConfig.getUploadsDir();
        if (uploadsDir != null && Files.exists(uploadsDir)) {
            File[] uploadFiles = uploadsDir.toFile().listFiles(
                    (dir, name) -> name.startsWith(prefix + "_") || name.startsWith(prefix + "-"));
            if (uploadFiles != null) {
                for (File f : uploadFiles) {
                    deleteRecursive(f.toPath());
                }
            }
        }
        // 清理扫描临时目录中属于该项目的文件
        Path scansDir = workspaceConfig.getScansDir();
        if (scansDir != null && Files.exists(scansDir)) {
            File[] scanDirs = scansDir.toFile().listFiles(
                    (dir, name) -> name.startsWith(prefix + "-"));
            if (scanDirs != null) {
                for (File f : scanDirs) {
                    deleteRecursive(f.toPath());
                }
            }
        }
    }

    private void deleteRecursive(Path path) {
        try {
            if (Files.isDirectory(path)) {
                File[] children = path.toFile().listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursive(child.toPath());
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete {}: {}", path, e.getMessage());
        }
    }

    @Override
    @Transactional
    public ProjectVO updateProject(Long projectId, String name, String description, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        checkProjectAccess(project, userId, "修改");
        if (!StringUtils.hasText(name)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }
        project.setName(name);
        project.setDescription(description);
        projectMapper.update(project);
        log.info("Project updated: id={}, name={}", projectId, name);
        long scanCount = scanMapper.countByProjectId(projectId);
        long insightCount = insightMapper.countByProjectId(projectId);
        return toVO(project, (int) scanCount, (int) insightCount);
    }

    private void checkProjectAccess(Project project, Long userId, String action) {
        if (userId.equals(project.getCreatedBy())) return;
        ProjectMember member = projectMemberMapper.findByProjectAndUser(project.getId(), userId);
        if (member != null) return;
        User user = userMapper.findById(userId);
        if (user != null && "ADMIN".equals(user.getRole())) return;
        throw new BusinessException(ErrorCode.FORBIDDEN,
                "无权" + action + "该项目，仅项目创建者、成员或管理员可操作");
    }

    @Override
    @Transactional
    public void addMember(Long projectId, Long targetUserId, String role, Long operatorUserId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        ProjectMember operator = projectMemberMapper.findByProjectAndUser(projectId, operatorUserId);
        boolean isOwner = operator != null && "OWNER".equals(operator.getRole());
        boolean isSystemAdmin = "ADMIN".equals(userMapper.findById(operatorUserId).getRole());
        if (!isSystemAdmin && !isOwner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有项目负责人或管理员才能添加成员");
        }

        ProjectMember existing = projectMemberMapper.findByProjectAndUser(projectId, targetUserId);
        if (existing != null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "该用户已是项目成员");
        }

        Set<String> validRoles = Set.of("ARCHITECT", "DEVELOPER", "VIEWER");
        if (!validRoles.contains(role)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "无效的项目角色: " + role + "，可选: ARCHITECT, DEVELOPER, VIEWER");
        }

        projectMemberMapper.insert(projectId, targetUserId, role);
        log.info("Member added: projectId={}, userId={}, role={}, by={}", projectId, targetUserId, role, operatorUserId);
    }

    @Override
    @Transactional
    public void removeMember(Long projectId, Long targetUserId, Long operatorUserId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }

        ProjectMember target = projectMemberMapper.findByProjectAndUser(projectId, targetUserId);
        if (target == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该用户不是项目成员");
        }
        if ("OWNER".equals(target.getRole())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不能移除项目创建者");
        }

        ProjectMember operator = projectMemberMapper.findByProjectAndUser(projectId, operatorUserId);
        boolean isOwner = operator != null && "OWNER".equals(operator.getRole());
        boolean isSystemAdmin = "ADMIN".equals(userMapper.findById(operatorUserId).getRole());
        if (!isSystemAdmin && !isOwner) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有项目负责人或管理员才能移除成员");
        }

        projectMemberMapper.delete(projectId, targetUserId);
        log.info("Member removed: projectId={}, userId={}, by={}", projectId, targetUserId, operatorUserId);
    }

    private ProjectVO toVO(Project p, int totalScans, int totalInsights) {
        ProjectVO vo = new ProjectVO();
        vo.setId(p.getId());
        vo.setName(p.getName());
        vo.setDescription(p.getDescription());
        vo.setLanguage(p.getLanguage());
        vo.setSourceType(p.getSourceType());
        vo.setSourceUrl(p.getSourceUrl());
        vo.setHealthScore(p.getHealthScore());
        vo.setTotalClasses(p.getTotalClasses());
        vo.setTotalModules(p.getTotalModules());
        vo.setTotalScans(totalScans);
        vo.setTotalInsights(totalInsights);
        vo.setLastScanId(p.getLastScanId());
        vo.setCreatedAt(p.getCreatedAt());
        if (p.getLastScanId() != null) {
            var scan = scanMapper.findById(p.getLastScanId());
            if (scan != null) {
                vo.setLastScanTime(scan.getCreatedAt());
            }
            List<Map<String, Object>> layerRows = classSummaryMapper.countLayerByScanId(p.getLastScanId());
            if (layerRows != null && !layerRows.isEmpty()) {
                long totalCount = layerRows.stream().mapToLong(r -> ((Number) r.get("cnt")).longValue()).sum();
                String[] colors = {"#667eea", "#52c41a", "#fa8c16", "#f5222d", "#1890ff", "#722ed1"};
                List<ProjectVO.LayerItem> items = new ArrayList<>();
                int ci = 0;
                for (Map<String, Object> row : layerRows) {
                    String layer = (String) row.get("layer");
                    int cnt = ((Number) row.get("cnt")).intValue();
                    int pct = totalCount > 0 ? (int) Math.round(cnt * 100.0 / totalCount) : 0;
                    items.add(new ProjectVO.LayerItem(
                            layer != null ? layer : "Unknown",
                            cnt, pct, colors[ci % colors.length]));
                    ci++;
                }
                vo.setLayerDistribution(items);
            }
        }
        return vo;
    }
}
