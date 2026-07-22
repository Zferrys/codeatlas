package com.codeatlas.server.security;

/**
 * 轻量级用户主体，避免引入 Spring Security UserDetails 的全套复杂度。
 */
public class CodeAtlasUserDetails {

    private final Long userId;
    private final String username;

    public CodeAtlasUserDetails(Long userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public Long getUserId() { return userId; }
    public String getUsername() { return username; }
}
