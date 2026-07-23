package com.codeatlas.server.service.impl;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.PageResult;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import com.codeatlas.server.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ProjectServiceImpl implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectServiceImpl.class);

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectServiceImpl(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    @Override
    @Transactional
    public ProjectVO createProject(CreateProjectRequest request, Long userId) {
        if (!StringUtils.hasText(request.getName())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "项目名称不能为空");
        }

        Project project = new Project();
        project.setName(request.getName());
        project.setDescription(request.getDescription());
        project.setSourceType(StringUtils.hasText(request.getSourceType())
                ? request.getSourceType() : "GIT_URL");
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

        return toVO(project);
    }

    @Override
    public PageResult<ProjectVO> listProjects(Long userId, int page, int size) {
        long total = projectMapper.countByUserId(userId);
        int offset = (page - 1) * size;
        List<Project> projects = projectMapper.findByUserIdPaged(userId, offset, size);
        List<ProjectVO> records = projects.stream().map(this::toVO).collect(Collectors.toList());
        return new PageResult<>(records, total, page, size);
    }

    @Override
    public ProjectVO getProjectById(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        return toVO(project);
    }

    @Override
    @Transactional
    public void deleteProject(Long projectId, Long userId) {
        Project project = projectMapper.findById(projectId);
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
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
        return toVO(project);
    }

    private ProjectVO toVO(Project p) {
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
        vo.setLastScanId(p.getLastScanId());
        vo.setCreatedAt(p.getCreatedAt());
        return vo;
    }
}
