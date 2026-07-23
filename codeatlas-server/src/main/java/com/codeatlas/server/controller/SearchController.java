package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/search")
@Tag(name = "全局搜索")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "全局搜索（仅返回当前用户的项目和类）")
    public ApiResponse<Map<String, Object>> search(@RequestParam String q,
                                                    @RequestParam(defaultValue = "all") String type,
                                                    @AuthenticationPrincipal CodeAtlasUserDetails principal) {
        return ApiResponse.success(searchService.search(q, type, principal.getUserId()));
    }
}
