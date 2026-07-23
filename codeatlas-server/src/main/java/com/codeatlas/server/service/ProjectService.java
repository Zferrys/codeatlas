package com.codeatlas.server.service;

import com.codeatlas.common.dto.PageResult;
import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;

public interface ProjectService {

    ProjectVO createProject(CreateProjectRequest request, Long userId);

    PageResult<ProjectVO> listProjects(Long userId, int page, int size);

    ProjectVO getProjectById(Long projectId, Long userId);

    void deleteProject(Long projectId, Long userId);

    ProjectVO updateProject(Long projectId, String name, String description, Long userId);

    void addMember(Long projectId, Long targetUserId, String role, Long operatorUserId);

    void removeMember(Long projectId, Long targetUserId, Long operatorUserId);
}
