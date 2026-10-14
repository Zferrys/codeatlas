package com.codeatlas.server.aspect;

import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.config.AsyncAuditLogWriter;
import com.codeatlas.server.entity.AuditLogEntity;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuditLogAspect 审计切面测试")
class AuditLogAspectTest {

    @Mock
    private AsyncAuditLogWriter auditLogWriter;

    @Mock
    private UserMapper userMapper;

    @Mock
    private ProceedingJoinPoint joinPoint;

    @Mock
    private MethodSignature methodSignature;

    private AuditLogAspect aspect;

    @BeforeEach
    void setUp() {
        aspect = new AuditLogAspect(auditLogWriter, userMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ========== 测试夹具：带 @AuditLog 注解的方法与入参/返回值结构 ==========

    @SuppressWarnings("unused")
    private static class AnnotatedMethods {

        @AuditLog(action = "RUN_SCAN", targetType = "SCAN", detail = "触发代码扫描",
                targetIdExpression = "#projectId")
        public Object runScan(Long projectId) {
            return "scan-result";
        }

        @AuditLog(action = "LOGIN", detail = "用户登录", usernameExpression = "#request.username")
        public Object login(UsernameRequest request) {
            return "login-result";
        }

        @AuditLog(action = "CREATE_PROJECT", targetType = "PROJECT", targetIdExpression = "#result.data.id")
        public Object createProject() {
            return "ok";
        }

        @AuditLog(action = "STRING_TARGET", targetIdExpression = "#result")
        public Object stringResult() {
            return "ok";
        }

        @AuditLog(action = "BROKEN_TARGET", targetIdExpression = "not a valid [spel")
        public Object brokenExpression() {
            return "ok";
        }

        @AuditLog(action = "BROKEN_USER", usernameExpression = "#nope.username")
        public Object brokenUsernameExpression(UsernameRequest request) {
            return "ok";
        }
    }

    public static class UsernameRequest {
        private final String username;

        public UsernameRequest(String username) {
            this.username = username;
        }

        public String getUsername() {
            return username;
        }
    }

    public static class ProjectData {
        private final Long id;

        public ProjectData(Long id) {
            this.id = id;
        }

        public Long getId() {
            return id;
        }
    }

    public static class ProjectResult {
        private final ProjectData data;

        public ProjectResult(ProjectData data) {
            this.data = data;
        }

        public ProjectData getData() {
            return data;
        }
    }

    private void stubJoinPoint(String methodName, Object result, Class<?>... parameterTypes) throws Exception {
        Method method = AnnotatedMethods.class.getMethod(methodName, parameterTypes);
        when(joinPoint.getSignature()).thenReturn(methodSignature);
        when(methodSignature.getMethod()).thenReturn(method);
    }

    private void stubAuthentication(CodeAtlasUserDetails userDetails) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities()));
    }

    private AuditLogEntity captureWritten() {
        ArgumentCaptor<AuditLogEntity> captor = ArgumentCaptor.forClass(AuditLogEntity.class);
        verify(auditLogWriter).write(captor.capture());
        return captor.getValue();
    }

    private static AuditLog annotationOf(String methodName, Class<?>... parameterTypes) throws Exception {
        return AnnotatedMethods.class.getMethod(methodName, parameterTypes).getAnnotation(AuditLog.class);
    }

    // ========== 主流程 ==========

    @Test
    @DisplayName("已认证用户：注解字段 + SpEL 参数 targetId + 上下文用户全部落到审计实体")
    void around_authenticatedUser_shouldWriteFullAuditEntity() throws Throwable {
        stubJoinPoint("runScan", "scan-result", Long.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{5L});
        when(joinPoint.proceed()).thenReturn("scan-result");
        stubAuthentication(new CodeAtlasUserDetails(9L, "alice", "DEVELOPER"));

        Object result = aspect.around(joinPoint, annotationOf("runScan", Long.class));

        assertEquals("scan-result", result);
        AuditLogEntity entity = captureWritten();
        assertEquals("RUN_SCAN", entity.getAction());
        assertEquals("SCAN", entity.getTargetType());
        assertEquals("触发代码扫描", entity.getDetail());
        assertEquals(5L, entity.getTargetId());
        assertEquals(9L, entity.getUserId());
        assertEquals("alice", entity.getUsername());
        assertNull(entity.getIpAddress());
        assertNull(entity.getUserAgent());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    @DisplayName("未认证登录场景：usernameExpression 解析用户名并反查 userId")
    void around_loginUsernameExpression_shouldResolveUserIdByLookup() throws Throwable {
        stubJoinPoint("login", "login-result", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("bob")});
        when(joinPoint.proceed()).thenReturn("login-result");
        User user = new User();
        user.setId(7L);
        when(userMapper.findByUsername("bob")).thenReturn(user);

        aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        AuditLogEntity entity = captureWritten();
        assertEquals("LOGIN", entity.getAction());
        assertEquals("用户登录", entity.getDetail());
        assertEquals("bob", entity.getUsername());
        assertEquals(7L, entity.getUserId());
        assertNull(entity.getTargetType());
        assertNull(entity.getTargetId());
        verify(userMapper).findByUsername("bob");
    }

    @Test
    @DisplayName("用户名反查不到用户：跳过写入，结果原样返回")
    void around_usernameWithoutMatchingUser_shouldSkipWrite() throws Throwable {
        stubJoinPoint("login", "login-result", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("ghost")});
        when(joinPoint.proceed()).thenReturn("login-result");
        when(userMapper.findByUsername("ghost")).thenReturn(null);

        Object result = aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        assertEquals("login-result", result);
        verify(auditLogWriter, never()).write(any());
    }

    @Test
    @DisplayName("usernameExpression 求值失败（变量不存在）：username 为 null，跳过写入")
    void around_brokenUsernameExpression_shouldSkipWrite() throws Throwable {
        stubJoinPoint("brokenUsernameExpression", "ok", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("bob")});
        when(joinPoint.proceed()).thenReturn("ok");

        Object result = aspect.around(joinPoint, annotationOf("brokenUsernameExpression", UsernameRequest.class));

        assertEquals("ok", result);
        verify(auditLogWriter, never()).write(any());
    }

    @Test
    @DisplayName("usernameExpression 求值为 null：username 为 null，跳过写入")
    void around_usernameExpressionEvaluatesToNull_shouldSkipWrite() throws Throwable {
        stubJoinPoint("login", "login-result", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest(null)});
        when(joinPoint.proceed()).thenReturn("login-result");

        Object result = aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        assertEquals("login-result", result);
        verify(auditLogWriter, never()).write(any());
    }

    @Test
    @DisplayName("targetIdExpression 使用 #result 嵌套属性（#result.data.id）")
    void around_resultExpression_shouldResolveNestedTargetId() throws Throwable {
        stubJoinPoint("createProject", "ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        ProjectResult projectResult = new ProjectResult(new ProjectData(42L));
        when(joinPoint.proceed()).thenReturn(projectResult);
        stubAuthentication(new CodeAtlasUserDetails(1L, "carol", "ARCHITECT"));

        aspect.around(joinPoint, annotationOf("createProject"));

        AuditLogEntity entity = captureWritten();
        assertEquals(42L, entity.getTargetId());
        assertEquals("CREATE_PROJECT", entity.getAction());
    }

    @Test
    @DisplayName("SpEL 结果非数字：targetId 保持 null 但审计仍写入")
    void around_nonNumberTargetId_shouldWriteWithNullTargetId() throws Throwable {
        stubJoinPoint("stringResult", "ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        stubAuthentication(new CodeAtlasUserDetails(1L, "carol", "ARCHITECT"));

        aspect.around(joinPoint, annotationOf("stringResult"));

        AuditLogEntity entity = captureWritten();
        assertNull(entity.getTargetId());
        assertEquals("STRING_TARGET", entity.getAction());
    }

    @Test
    @DisplayName("非法 SpEL 表达式：吞掉异常，targetId 为 null 且 targetType/detail 空串转 null")
    void around_brokenExpression_shouldSwallowErrorAndWriteWithNulls() throws Throwable {
        stubJoinPoint("brokenExpression", "ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        stubAuthentication(new CodeAtlasUserDetails(1L, "carol", "ARCHITECT"));

        aspect.around(joinPoint, annotationOf("brokenExpression"));

        AuditLogEntity entity = captureWritten();
        assertNull(entity.getTargetId());
        assertNull(entity.getTargetType());
        assertNull(entity.getDetail());
    }

    @Test
    @DisplayName("SecurityContext principal 非 CodeAtlasUserDetails：不取 principal 字段，走 username 反查")
    void around_nonCodeAtlasPrincipal_shouldFallbackToUsernameLookup() throws Throwable {
        stubJoinPoint("login", "ok", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("dave")});
        when(joinPoint.proceed()).thenReturn("ok");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("raw-principal", null));
        User user = new User();
        user.setId(11L);
        when(userMapper.findByUsername("dave")).thenReturn(user);

        aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        AuditLogEntity entity = captureWritten();
        assertEquals("dave", entity.getUsername());
        assertEquals(11L, entity.getUserId());
    }

    @Test
    @DisplayName("SecurityContext 为空：username 表达式已提供用户名时仍能反查 userId")
    void around_noAuthenticationContext_shouldStillIdentifyUser() throws Throwable {
        stubJoinPoint("login", "ok", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("erin")});
        when(joinPoint.proceed()).thenReturn("ok");
        User user = new User();
        user.setId(12L);
        when(userMapper.findByUsername("erin")).thenReturn(user);

        aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        AuditLogEntity entity = captureWritten();
        assertEquals(12L, entity.getUserId());
        assertEquals("erin", entity.getUsername());
    }

    @Test
    @DisplayName("表达式用户名优先于 principal username（不覆盖），userId 仍取 principal")
    void around_expressionUsernameTakesPrecedence() throws Throwable {
        stubJoinPoint("login", "ok", UsernameRequest.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{new UsernameRequest("bob")});
        when(joinPoint.proceed()).thenReturn("ok");
        stubAuthentication(new CodeAtlasUserDetails(9L, "alice", "DEVELOPER"));

        aspect.around(joinPoint, annotationOf("login", UsernameRequest.class));

        AuditLogEntity entity = captureWritten();
        assertEquals("bob", entity.getUsername());
        assertEquals(9L, entity.getUserId());
        verify(userMapper, never()).findByUsername(any());
    }

    @Test
    @DisplayName("请求上下文存在：记录客户端 IP 与 User-Agent")
    void around_withRequestContext_shouldCaptureIpAndUserAgent() throws Throwable {
        stubJoinPoint("runScan", "scan-result", Long.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{5L});
        when(joinPoint.proceed()).thenReturn("scan-result");
        stubAuthentication(new CodeAtlasUserDetails(9L, "alice", "DEVELOPER"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("10.1.2.3");
        request.addHeader("User-Agent", "JUnit/1.0");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        aspect.around(joinPoint, annotationOf("runScan", Long.class));

        AuditLogEntity entity = captureWritten();
        assertEquals("10.1.2.3", entity.getIpAddress());
        assertEquals("JUnit/1.0", entity.getUserAgent());
    }

    @Test
    @DisplayName("审计写入抛异常：切面吞掉异常并返回业务结果")
    void around_writerThrows_shouldSwallowAndReturnResult() throws Throwable {
        stubJoinPoint("runScan", "scan-result", Long.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{5L});
        when(joinPoint.proceed()).thenReturn("scan-result");
        stubAuthentication(new CodeAtlasUserDetails(9L, "alice", "DEVELOPER"));
        doThrow(new RuntimeException("db down")).when(auditLogWriter).write(any(AuditLogEntity.class));

        Object result = aspect.around(joinPoint, annotationOf("runScan", Long.class));

        assertEquals("scan-result", result);
    }

    @Test
    @DisplayName("业务方法自身抛异常：原样向外传播且不写审计")
    void around_proceedThrows_shouldPropagate() throws Throwable {
        when(joinPoint.proceed()).thenThrow(new IllegalStateException("business failure"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> aspect.around(joinPoint, annotationOf("runScan", Long.class)));

        assertEquals("business failure", thrown.getMessage());
        verifyNoInteractions(auditLogWriter, userMapper);
    }

    @Test
    @DisplayName("注解未声明 targetType/detail：空串缺省转 null，principal 用户名兜底填充")
    void around_authenticatedWithoutAnnotations_shouldNullEmptyOptionalFields() throws Throwable {
        stubJoinPoint("stringResult", "ok");
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenReturn("ok");
        stubAuthentication(new CodeAtlasUserDetails(2L, "frank", "VIEWER"));

        aspect.around(joinPoint, annotationOf("stringResult"));

        AuditLogEntity entity = captureWritten();
        assertNull(entity.getTargetType());
        assertNull(entity.getDetail());
        assertEquals("frank", entity.getUsername());
    }
}
