package com.codeatlas.server.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Collections;

/**
 * Spring Security UserDetails 实现，用于 JWT 认证主体。
 */
public class CodeAtlasUserDetails implements UserDetails {

    private final Long userId;
    private final String username;
    private final Collection<? extends GrantedAuthority> authorities;

    public CodeAtlasUserDetails(Long userId, String username, String role) {
        this.userId = userId;
        this.username = username;
        this.authorities = Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + role));
    }

    public Long getUserId() { return userId; }

    @Override
    public String getUsername() { return username; }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() { return null; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
