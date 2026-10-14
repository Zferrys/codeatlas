package com.codeatlas.server.security;

import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthFilter JWT 认证过滤器测试")
class JwtAuthFilterTest {

    private static final String TOKEN = "header.payload.signature";

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private UserMapper userMapper;

    @Mock
    private TokenBlacklistService tokenBlacklistService;

    @Mock
    private FilterChain filterChain;

    private JwtAuthFilter filter;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtTokenProvider, userMapper, tokenBlacklistService);
        request = new MockHttpServletRequest();
        response = new MockHttpServletResponse();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static User activeUser(Long id, String role) {
        User user = new User();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private void stubValidToken(long userId, String username, String role) {
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenBlacklistService.isBlacklisted(TOKEN)).thenReturn(false);
        when(jwtTokenProvider.getUserId(TOKEN)).thenReturn(userId);
        when(jwtTokenProvider.getUsername(TOKEN)).thenReturn(username);
        when(jwtTokenProvider.getRole(TOKEN)).thenReturn(role);
    }

    @Test
    @DisplayName("无 Authorization 头：不认证直接放行，不触碰任何依赖")
    void noAuthorizationHeader_shouldPassThrough() throws Exception {
        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(jwtTokenProvider, userMapper, tokenBlacklistService);
    }

    @Test
    @DisplayName("非 Bearer 前缀：视为无 token 放行")
    void nonBearerHeader_shouldPassThrough() throws Exception {
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(jwtTokenProvider, userMapper, tokenBlacklistService);
    }

    @Test
    @DisplayName("Bearer 后为空：视为无 token 放行")
    void blankBearerToken_shouldPassThrough() throws Exception {
        request.addHeader("Authorization", "Bearer ");

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verifyNoInteractions(jwtTokenProvider, userMapper, tokenBlacklistService);
    }

    @Test
    @DisplayName("合法 token 且用户正常：principal 为 CodeAtlasUserDetails 且角色来自 token")
    void validToken_activeUser_shouldInjectUserDetails() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(1L, "alice", "DEVELOPER");
        when(userMapper.findById(1L)).thenReturn(activeUser(1L, "DEVELOPER"));

        filter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CodeAtlasUserDetails principal = assertInstanceOf(CodeAtlasUserDetails.class, authentication.getPrincipal());
        assertEquals(1L, principal.getUserId());
        assertEquals("alice", principal.getUsername());
        assertEquals("ROLE_DEVELOPER", principal.getAuthorities().iterator().next().getAuthority());
        assertNull(authentication.getCredentials());
        assertTrue(authentication.isAuthenticated());
        assertEquals(principal.getAuthorities(), authentication.getAuthorities());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("数据库角色与 token 不一致：以数据库角色为准（管理员改角色即时生效）")
    void validToken_roleChanged_shouldSyncRoleFromDatabase() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(2L, "bob", "DEVELOPER");
        when(userMapper.findById(2L)).thenReturn(activeUser(2L, "ADMIN"));

        filter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CodeAtlasUserDetails principal = (CodeAtlasUserDetails) authentication.getPrincipal();
        assertEquals("ROLE_ADMIN", principal.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    @DisplayName("数据库角色为 null：保留 token 中的角色")
    void validToken_userRoleNull_shouldKeepTokenRole() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(3L, "carol", "ARCHITECT");
        when(userMapper.findById(3L)).thenReturn(activeUser(3L, null));

        filter.doFilterInternal(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        CodeAtlasUserDetails principal = (CodeAtlasUserDetails) authentication.getPrincipal();
        assertEquals("ROLE_ARCHITECT", principal.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    @DisplayName("用户已被禁用（status=0）：不注入认证，仅放行")
    void validToken_disabledUser_shouldNotAuthenticate() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(4L, "dave", "DEVELOPER");
        User disabled = activeUser(4L, "DEVELOPER");
        disabled.setStatus(0);
        when(userMapper.findById(4L)).thenReturn(disabled);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("用户状态为 null：视为禁用，不注入认证")
    void validToken_nullStatus_shouldNotAuthenticate() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(5L, "erin", "DEVELOPER");
        User noStatus = activeUser(5L, "DEVELOPER");
        noStatus.setStatus(null);
        when(userMapper.findById(5L)).thenReturn(noStatus);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("用户已被删除（findById 返回 null）：不注入认证")
    void validToken_deletedUser_shouldNotAuthenticate() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        stubValidToken(6L, "frank", "DEVELOPER");
        when(userMapper.findById(6L)).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    @DisplayName("黑名单 token：拒绝认证且不查询用户")
    void blacklistedToken_shouldNotAuthenticate() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(true);
        when(tokenBlacklistService.isBlacklisted(TOKEN)).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verifyNoInteractions(userMapper);
        verify(jwtTokenProvider, never()).getUserId(anyString());
    }

    @Test
    @DisplayName("非法 token：短路跳过黑名单查询与用户查询")
    void invalidToken_shouldShortCircuitBeforeBlacklistCheck() throws Exception {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        when(jwtTokenProvider.validateToken(TOKEN)).thenReturn(false);

        filter.doFilterInternal(request, response, filterChain);

        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(filterChain).doFilter(request, response);
        verify(tokenBlacklistService, never()).isBlacklisted(anyString());
        verifyNoInteractions(userMapper);
        verify(jwtTokenProvider, never()).getUserId(anyString());
    }
}
