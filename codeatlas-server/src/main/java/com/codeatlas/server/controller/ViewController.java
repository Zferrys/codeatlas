package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.MapViewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/views")
@Tag(name = "地图视图快照")
public class ViewController {

    private final MapViewService mapViewService;

    public ViewController(MapViewService mapViewService) {
        this.mapViewService = mapViewService;
    }

    @PostMapping
    @Operation(summary = "保存当前地图视图")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> saveView(@PathVariable Long projectId,
                                                      @RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        String name = (String) body.getOrDefault("name", "未命名视图");
        boolean isPublic = Boolean.TRUE.equals(body.get("isPublic"));
        Object viewState = body.get("viewState");
        String viewStateStr = viewState == null ? null : String.valueOf(viewState);
        return ApiResponse.success(mapViewService.saveView(projectId, principal.getUserId(), name, isPublic, viewStateStr));
    }

    @GetMapping
    @Operation(summary = "获取视图列表")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> listViews(@PathVariable Long projectId,
                                                             @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(mapViewService.listViews(projectId, principal.getUserId()));
    }

    @DeleteMapping("/{viewId}")
    @Operation(summary = "删除视图")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteView(@PathVariable Long projectId,
                                         @PathVariable Long viewId,
                                         @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        mapViewService.deleteView(viewId, principal.getUserId());
        return ApiResponse.success(null);
    }
}
