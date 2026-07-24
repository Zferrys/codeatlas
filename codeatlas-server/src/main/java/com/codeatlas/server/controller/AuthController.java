package com.codeatlas.server.controller;

import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.dto.request.ChangePasswordRequest;
import com.codeatlas.server.dto.request.LoginRequest;
import com.codeatlas.server.dto.request.RegisterRequest;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.security.TokenBlacklistService;
import com.codeatlas.server.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "用户认证")
public class AuthController {

    private final UserService userService;
    private final TokenBlacklistService tokenBlacklistService;
    private final JwtTokenProvider jwtTokenProvider;

    public AuthController(UserService userService, TokenBlacklistService tokenBlacklistService,
                          JwtTokenProvider jwtTokenProvider) {
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @PostMapping("/register")
    @Operation(summary = "用户注册")
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(userService.register(
                request.getUsername(), request.getPassword(), request.getEmail()));
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录")
    @AuditLog(action = "LOGIN", detail = "用户登录", usernameExpression = "#request.username")
    public ApiResponse<Map<String, Object>> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(userService.login(request.getUsername(), request.getPassword()));
    }

    @PostMapping("/logout")
    @Operation(summary = "用户登出")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            tokenBlacklistService.blacklist(token, jwtTokenProvider.getExpiresAtMillis(token));
        }
        return ApiResponse.success();
    }

    private String extractToken(HttpServletRequest request) {
        String bearer = request.getHeader("Authorization");
        if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
            return bearer.substring(7);
        }
        return null;
    }

    @GetMapping("/me")
    @Operation(summary = "获取当前用户信息")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal CodeAtlasUserDetails principal) {
        com.codeatlas.server.entity.User user = userService.getCurrentUser(principal.getUserId());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("email", user.getEmail());
        result.put("role", user.getRole());
        result.put("status", user.getStatus());
        result.put("avatarUrl", user.getAvatarUrl());
        result.put("createdAt", user.getCreatedAt());
        return ApiResponse.success(result);
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal CodeAtlasUserDetails principal,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getUserId(),
                request.getOldPassword(), request.getNewPassword());
        return ApiResponse.success();
    }
}
