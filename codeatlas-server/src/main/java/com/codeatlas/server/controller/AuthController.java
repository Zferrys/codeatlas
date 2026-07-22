package com.codeatlas.server.controller;

import com.codeatlas.common.constant.ErrorCode;
import com.codeatlas.common.dto.ApiResponse;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import com.codeatlas.server.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@Api(tags = "用户认证")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/register")
    @ApiOperation("用户注册")
    public ApiResponse<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        String email = body.get("email");
        return ApiResponse.success(userService.register(username, password, email));
    }

    @PostMapping("/login")
    @ApiOperation("用户登录")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        return ApiResponse.success(userService.login(username, password));
    }

    @PostMapping("/logout")
    @ApiOperation("用户登出")
    public ApiResponse<Void> logout() {
        // 无状态 JWT，客户端删除 token 即可
        return ApiResponse.success();
    }

    @GetMapping("/me")
    @ApiOperation("获取当前用户信息")
    public ApiResponse<Map<String, Object>> me(@AuthenticationPrincipal CodeAtlasUserDetails principal) {
        if (principal == null) {
            return ApiResponse.error(ErrorCode.UNAUTHORIZED);
        }
        com.codeatlas.server.entity.User user = userService.getCurrentUser(principal.getUserId());
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("id", user.getId());
        result.put("username", user.getUsername());
        result.put("email", user.getEmail());
        result.put("role", user.getRole());
        result.put("avatarUrl", user.getAvatarUrl());
        return ApiResponse.success(result);
    }
}
