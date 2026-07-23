package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.entity.MapViewSnapshot;
import com.codeatlas.server.mapper.MapViewSnapshotMapper;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/views")
@Tag(name = "地图视图快照")
public class ViewController {

    private final MapViewSnapshotMapper mapper;

    public ViewController(MapViewSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping
    @Operation(summary = "保存当前地图视图")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> saveView(@PathVariable Long projectId,
                                                      @RequestBody Map<String, Object> body,
                                                      @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        MapViewSnapshot snapshot = new MapViewSnapshot();
        snapshot.setProjectId(projectId);
        snapshot.setUserId(principal.getUserId());
        snapshot.setName((String) body.getOrDefault("name", "未命名视图"));
        snapshot.setIsPublic(Boolean.TRUE.equals(body.get("isPublic")) ? 1 : 0);

        Object viewState = body.get("viewState");
        if (viewState == null) {
            return ApiResponse.error(400, "viewState 不能为空");
        }
        snapshot.setViewState(viewState instanceof String ? (String) viewState : viewState.toString());

        mapper.insert(snapshot);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", snapshot.getId());
        result.put("name", snapshot.getName());
        result.put("createdAt", snapshot.getCreatedAt());
        return ApiResponse.success(result);
    }

    @GetMapping
    @Operation(summary = "获取视图列表")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<Map<String, Object>>> listViews(@PathVariable Long projectId,
                                                             @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        List<MapViewSnapshot> snapshots = mapper.findByProjectId(projectId, principal.getUserId());
        List<Map<String, Object>> records = new ArrayList<>();
        for (MapViewSnapshot s : snapshots) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", s.getId());
            item.put("name", s.getName());
            item.put("userId", s.getUserId());
            item.put("isPublic", s.getIsPublic());
            item.put("updatedAt", s.getUpdatedAt());
            records.add(item);
        }
        return ApiResponse.success(records);
    }

    @DeleteMapping("/{viewId}")
    @Operation(summary = "删除视图")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> deleteView(@PathVariable Long projectId,
                                         @PathVariable Long viewId,
                                         @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        int affected = mapper.deleteByIdAndUser(viewId, principal.getUserId());
        if (affected == 0) {
            return ApiResponse.error(404, "视图不存在或无权删除");
        }
        return ApiResponse.success(null);
    }
}
