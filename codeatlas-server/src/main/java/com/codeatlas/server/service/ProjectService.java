package com.codeatlas.server.service;

import com.codeatlas.server.dto.request.CreateProjectRequest;
import com.codeatlas.server.dto.response.ProjectVO;

import java.util.List;

public interface ProjectService {

    ProjectVO createProject(CreateProjectRequest request, Long userId);

    List<ProjectVO> listProjects(Long userId);

    ProjectVO getProjectById(Long projectId, Long userId);

    void deleteProject(Long projectId, Long userId);

    ProjectVO updateProject(Long projectId, String name, String description, Long userId);
}
