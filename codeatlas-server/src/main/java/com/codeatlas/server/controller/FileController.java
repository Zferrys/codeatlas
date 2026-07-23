package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.response.ProjectVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.FileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/files")
@Tag(name = "文件上传")
public class FileController {

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    @Operation(summary = "上传源码包（ZIP），自动创建项目并触发扫描")
    @PreAuthorize("hasAnyRole('ADMIN','ARCHITECT','DEVELOPER')")
    @AuditLog(action = "UPLOAD_FILE", targetType = "FILE", targetIdExpression = "#result.data.id", detail = "上传源码包")
    public ApiResponse<ProjectVO> upload(@RequestParam("file") MultipartFile file,
                                          @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(fileService.uploadAndScan(file, principal.getUserId()));
    }
}
