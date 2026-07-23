package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.dto.response.GraphVO;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.MapService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/map")
@Tag(name = "代码地图")
public class MapController {

    private final MapService mapService;

    public MapController(MapService mapService) {
        this.mapService = mapService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "获取项目依赖图谱")
    public ApiResponse<GraphVO> getProjectMap(@PathVariable Long projectId,
                                              @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(mapService.getProjectMap(projectId, principal.getUserId()));
    }

    @GetMapping("/graph")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "查询类依赖子图（Neo4j 遍历）")
    public ApiResponse<GraphVO> getSubgraph(@PathVariable Long projectId,
                                            @RequestParam String fqn,
                                            @RequestParam(defaultValue = "2") int depth,
                                            @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(mapService.getSubgraph(projectId, fqn, depth, principal.getUserId()));
    }
}
