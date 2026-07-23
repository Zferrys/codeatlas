package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.entity.ProjectMember;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.ClassSummaryMapper;
import com.codeatlas.server.mapper.InsightMapper;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.mapper.ScanMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
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

    public ProjectServiceImpl(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper,
                              ScanMapper scanMapper, InsightMapper insightMapper,
                              ClassSummaryMapper classSummaryMapper, UserMapper userMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.scanMapper = scanMapper;
        this.insightMapper = insightMapper;
        this.classSummaryMapper = classSummaryMapper;
        this.userMapper = userMapper;
    }

    @Override
    @Transactional
    public ProjectVO createProject(CreateProjectRequest request, Long userId) {
        if (!StringUtils.hasText(request.getName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }

        String sourceType = StringUtils.hasText(request.getSourceType())
                ? request.getSourceType() : "GIT_URL";

        // GIT_URL 格式校验
        if ("GIT_URL".equals(sourceType)) {
            String url = request.getSourceUrl();
            if (!StringUtils.hasText(url)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Git 仓库 URL 不能为空");
            }
            if (!url.matches("^https?://[^\\s]+$")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Git URL 格式不正确，示例：https://github.com/user/repo.git");
            }
        }

        // LOCAL_PATH 校验
        if ("LOCAL_PATH".equals(sourceType) && !StringUtils.hasText(request.getSourceUrl())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "本地路径不能为空");
        }

        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setSourceType(sourceType);
        project.setSourceUrl(request.getSourceUrl());
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

        // batch query scan and insight counts
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
        long scanCount = scanMapper.countByProjectId(projectId);
        long insightCount = insightMapper.countByProjectId(projectId);
        return toVO(project, (int) scanCount, (int) insightCount);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        // 仅项目创建者或系统管理员可删除
        boolean isOwner = userId.equals(project.getCreatedBy());
        if (!isOwner) {
            User user = userMapper.findById(userId);
            boolean isAdmin = user != null && "ADMIN".equals(user.getRole());
            if (!isAdmin) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "只有项目创建者或管理员才能删除项目");
            }
        }
        projectMapper.deleteById(projectId);
        log.info("Project deleted: id={}, userId={}", projectId, userId);
    }

    @Override
    @Transactional
    public ProjectVO updateProject(Long projectId, String name, String description, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
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
            // populate layer distribution from the latest scan
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
