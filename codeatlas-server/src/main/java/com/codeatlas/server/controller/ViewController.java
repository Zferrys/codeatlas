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
        try {
            String name = (String) body.getOrDefault("name", "未命名视图");
            boolean isPublic = Boolean.TRUE.equals(body.get("isPublic"));
            Object viewState = body.get("viewState");
            if (viewState == null) {
                return ApiResponse.error(400, "viewState 不能为空");
            }
            String viewStateStr = viewState instanceof String ? (String) viewState : viewState.toString();
            return ApiResponse.success(mapViewService.saveView(projectId, principal.getUserId(), name, isPublic, viewStateStr));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        }
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
        try {
            mapViewService.deleteView(viewId, principal.getUserId());
            return ApiResponse.success(null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        }
    }
}
