package com.codeatlas.server.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class CreateProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(min = 1, max = 100, message = "项目名称长度应为 1-100 位")
    private String name;

    @NotBlank(message = "源码来源不能为空")
    private String sourceType;

    @Size(max = 500, message = "Git URL 长度不能超过 500")
    private String sourceUrl;

    @Size(max = 500, message = "描述长度不能超过 500")
    private String description;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
}
