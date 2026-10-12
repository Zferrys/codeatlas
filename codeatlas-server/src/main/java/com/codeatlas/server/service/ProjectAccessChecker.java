package com.codeatlas.server.service;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.Project;
import com.codeatlas.server.mapper.ProjectMapper;
import com.codeatlas.server.mapper.ProjectMemberMapper;
import org.springframework.stereotype.Component;

/**
 * 项目归属鉴权：创建者或项目成员可通过，其余一律按"项目不存在"（404）处理，避免暴露项目存在性。
 */
@Component
public class ProjectAccessChecker {

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectAccessChecker(ProjectMapper projectMapper, ProjectMemberMapper projectMemberMapper) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    public void check(Long projectId, Long userId) {
        check(projectMapper.findById(projectId), projectId, userId);
    }

    /** 调用方已持有 Project 时使用，避免重复查询 */
    public void check(Project project, Long projectId, Long userId) {
        if (project == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
        }
        if (userId != null && userId.equals(project.getCreatedBy())) {
            return;
        }
        if (userId != null && projectMemberMapper.findByProjectAndUser(projectId, userId) != null) {
            return;
        }
        throw new BusinessException(ErrorCode.NOT_FOUND, "项目不存在");
    }
}
