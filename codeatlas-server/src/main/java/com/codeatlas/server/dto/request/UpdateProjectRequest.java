package com.codeatlas.server.dto.request;

import jakarta.validation.constraints.Size;

public class UpdateProjectRequest {

    @Size(min = 1, max = 100, message = "项目名称长度应为 1-100 位")
    private String name;

    @Size(max = 500, message = "描述长度不能超过 500")
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
