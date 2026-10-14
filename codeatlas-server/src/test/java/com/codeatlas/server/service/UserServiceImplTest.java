package com.codeatlas.server.service;

import com.codeatlas.common.exception.BusinessException;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.JwtTokenProvider;
import com.codeatlas.server.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl 单元测试")
class UserServiceImplTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenProvider jwtTokenProvider;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userService = new UserServiceImpl(userMapper, passwordEncoder, jwtTokenProvider);
    }

    /** 注入 Redis 模板并接通 opsForValue，用于覆盖 Redis 分支 */
    private void injectRedis() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        ReflectionTestUtils.setField(userService, "redisTemplate", redisTemplate);
    }

    // ========== register ==========

    @Test
    @DisplayName("注册成功返回 token")
    void shouldRegisterSuccessfully() {
        when(userMapper.countByUsername("newuser")).thenReturn(0);
        when(passwordEncoder.encode("Test@1234")).thenReturn("hashed_pw");

        // 模拟 MyBatis 自动设置主键
        doAnswer(invocation -> {
            User u = invocation.getArgument(0);
            u.setId(10L);
            return 1;
        }).when(userMapper).insert(any(User.class));

        when(jwtTokenProvider.generateToken(eq(10L), eq("newuser"), eq("DEVELOPER")))
                .thenReturn("jwt_token_xxx");

        Map<String, Object> result = userService.register("newuser", "Test@1234", "test@test.com");

        assertEquals("jwt_token_xxx", result.get("token"));
        assertEquals("newuser", result.get("username"));
        assertEquals("DEVELOPER", result.get("role"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("用户名太短抛异常")
    void shouldRejectShortUsername() {
        assertThrows(BusinessException.class,
                () -> userService.register("ab", "Test@1234", null));
    }

    @Test
    @DisplayName("用户名已存在抛异常")
    void shouldRejectDuplicateUsername() {
        when(userMapper.countByUsername("exist")).thenReturn(1);

        assertThrows(BusinessException.class,
                () -> userService.register("exist", "Test@1234", null));
    }

    @Test
    @DisplayName("密码强度不足抛异常")
    void shouldRejectWeakPassword() {
        when(userMapper.countByUsername("test")).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> userService.register("test", "12345678", null));
    }

    @Test
    @DisplayName("isPasswordStrong 私有 null 防御：直接传入 null 判定为弱密码")
    void isPasswordStrong_nullPassword_shouldReturnFalse() throws Exception {
        // register / changePassword 两个公开入口都会先判 password == null（短路）再调用本方法，
        // null 输入永远到达不了方法体内的 null 防御分支；反射直达该分支，锁死其返回 false。
        Method method = UserServiceImpl.class.getDeclaredMethod("isPasswordStrong", String.class);
        method.setAccessible(true);

        Object result = method.invoke(userService, new Object[] { null });

        assertEquals(Boolean.FALSE, result, "null 密码必须判定为弱密码");
    }

    // ========== login ==========

    @Test
    @DisplayName("登录成功返回 token")
    void shouldLoginSuccessfully() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed_pw");
        user.setRole("DEVELOPER");
        user.setStatus(1);

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("123456", "hashed_pw")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "testuser", "DEVELOPER"))
                .thenReturn("jwt_token_xxx");

        Map<String, Object> result = userService.login("testuser", "123456");

        assertEquals("jwt_token_xxx", result.get("token"));
        assertEquals(1L, result.get("userId"));
    }

    @Test
    @DisplayName("用户不存在登录失败")
    void shouldFailLoginOnWrongUsername() {
        when(userMapper.findByUsername("nobody")).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> userService.login("nobody", "123456"));
    }

    @Test
    @DisplayName("密码错误登录失败")
    void shouldFailLoginOnWrongPassword() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");
        user.setPasswordHash("hashed_pw");
        user.setStatus(1);

        when(userMapper.findByUsername("testuser")).thenReturn(user);
        when(passwordEncoder.matches("wrong", "hashed_pw")).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> userService.login("testuser", "wrong"));
    }

    @Test
    @DisplayName("被禁用用户登录失败")
    void shouldFailLoginOnDisabledUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("disabled");
        user.setPasswordHash("hashed_pw");
        user.setStatus(0);

        when(userMapper.findByUsername("disabled")).thenReturn(user);

        assertThrows(BusinessException.class,
                () -> userService.login("disabled", "123456"));
    }

    // ========== getCurrentUser ==========

    @Test
    @DisplayName("获取当前用户信息")
    void shouldGetCurrentUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("testuser");

        when(userMapper.findById(1L)).thenReturn(user);

        User result = userService.getCurrentUser(1L);
        assertEquals("testuser", result.getUsername());
    }

    @Test
    @DisplayName("获取不存在的用户抛异常")
    void shouldThrowOnMissingUser() {
        when(userMapper.findById(999L)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> userService.getCurrentUser(999L));
    }

    // ==================== 以下为覆盖率补全追加（不改动上方既有用例） ====================

    private User loginUser(Long id, String username, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setPasswordHash("hashed_pw");
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    @Test
    @DisplayName("注册：邮箱已注册抛 EMAIL_EXISTS")
    void register_emailExists_shouldThrowEmailExists() {
        when(userMapper.countByUsername("newuser")).thenReturn(0);
        when(userMapper.findByEmail("dup@test.com")).thenReturn(new User());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register("newuser", "Test@1234", "dup@test.com"));

        assertEquals(40002, ex.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：邮箱为 null 时跳过邮箱重复检查")
    void register_nullEmail_shouldSkipEmailCheck() {
        when(userMapper.countByUsername("newuser")).thenReturn(0);
        when(passwordEncoder.encode("Test@1234")).thenReturn("hashed");
        when(jwtTokenProvider.generateToken(any(), eq("newuser"), eq("DEVELOPER"))).thenReturn("t");

        Map<String, Object> result = userService.register("newuser", "Test@1234", null);

        assertEquals("t", result.get("token"));
        verify(userMapper, never()).findByEmail(any());
    }

    @Test
    @DisplayName("登录：账户锁定中抛 400，剩余分钟数 = ttl/60+1")
    void login_lockedAccount_shouldThrowWithRemainingMinutes() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn("LOCKED");
        when(redisTemplate.getExpire("login:lock:testuser", TimeUnit.SECONDS)).thenReturn(300L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("testuser", "pw"));

        assertEquals(400, ex.getCode());
        assertEquals("账户已被临时锁定，请 6 分钟后再试", ex.getMessage());
        verify(userMapper, never()).findByUsername(anyString());
    }

    @Test
    @DisplayName("登录：锁定但 TTL 丢失时按 15 分钟提示")
    void login_lockedWithoutTtl_shouldSayFifteenMinutes() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn("LOCKED");
        when(redisTemplate.getExpire("login:lock:testuser", TimeUnit.SECONDS)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("testuser", "pw"));

        assertEquals("账户已被临时锁定，请 15 分钟后再试", ex.getMessage());
    }

    @Test
    @DisplayName("登录成功：清理失败计数与锁定键")
    void login_successWithRedis_shouldClearFailureCounters() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(loginUser(1L, "testuser", "DEVELOPER"));
        when(passwordEncoder.matches("pw", "hashed_pw")).thenReturn(true);
        when(jwtTokenProvider.generateToken(1L, "testuser", "DEVELOPER")).thenReturn("jwt");

        Map<String, Object> result = userService.login("testuser", "pw");

        assertEquals("jwt", result.get("token"));
        verify(redisTemplate).delete("login:attempts:testuser");
        verify(redisTemplate).delete("login:lock:testuser");
        verify(userMapper).updateLoginTime(1L);
    }

    @Test
    @DisplayName("登录失败：首次失败写入计数并设置过期时间")
    void login_firstFailure_shouldIncrementAndExpire() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(valueOperations.increment("login:attempts:testuser", 1)).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("testuser", "pw"));

        assertEquals(40005, ex.getCode());
        verify(redisTemplate).expire("login:attempts:testuser", 900, TimeUnit.SECONDS);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("登录失败：达到 5 次写入锁定键")
    void login_fifthFailure_shouldLockAccount() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(valueOperations.increment("login:attempts:testuser", 1)).thenReturn(5L);

        assertThrows(BusinessException.class, () -> userService.login("testuser", "pw"));

        verify(valueOperations).set("login:lock:testuser", "LOCKED", 900, TimeUnit.SECONDS);
        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("登录失败：计数未达上限时既不设过期也不锁定")
    void login_attemptsBelowMaxOrNull_shouldNotLock() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(valueOperations.increment("login:attempts:testuser", 1)).thenReturn(3L, null);

        assertThrows(BusinessException.class, () -> userService.login("testuser", "pw"));
        assertThrows(BusinessException.class, () -> userService.login("testuser", "pw"));

        verify(redisTemplate, never()).expire(anyString(), anyLong(), any(TimeUnit.class));
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("登录失败：Redis 异常被吞掉，仍返回登录失败")
    void login_redisFailure_shouldBeSwallowed() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(null);
        when(valueOperations.increment("login:attempts:testuser", 1))
                .thenThrow(new RuntimeException("redis down"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.login("testuser", "pw"));

        assertEquals(40005, ex.getCode());
    }

    @Test
    @DisplayName("修改密码：旧密码校验通过后更新为新密码哈希")
    void changePassword_success_shouldUpdateHash() {
        when(userMapper.findById(1L)).thenReturn(loginUser(1L, "testuser", "DEVELOPER"));
        when(passwordEncoder.matches("Old@1234", "hashed_pw")).thenReturn(true);
        when(passwordEncoder.encode("New@1234")).thenReturn("new_hash");

        userService.changePassword(1L, "Old@1234", "New@1234");

        verify(userMapper).updatePassword(1L, "new_hash");
    }

    @Test
    @DisplayName("修改密码：用户不存在抛 401")
    void changePassword_missingUser_shouldThrow401() {
        when(userMapper.findById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(999L, "Old@1234", "New@1234"));

        assertEquals(401, ex.getCode());
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("修改密码：旧密码错误抛 400 当前密码错误")
    void changePassword_wrongOldPassword_shouldThrow400() {
        when(userMapper.findById(1L)).thenReturn(loginUser(1L, "testuser", "DEVELOPER"));
        when(passwordEncoder.matches("Wrong@123", "hashed_pw")).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "Wrong@123", "New@1234"));

        assertEquals(400, ex.getCode());
        assertEquals("当前密码错误", ex.getMessage());
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    @Test
    @DisplayName("修改密码：新密码过弱或为 null 抛 PASSWORD_TOO_WEAK")
    void changePassword_weakNewPassword_shouldThrow() {
        when(userMapper.findById(1L)).thenReturn(loginUser(1L, "testuser", "DEVELOPER"));
        when(passwordEncoder.matches("Old@1234", "hashed_pw")).thenReturn(true);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "Old@1234", "12345678"));

        assertEquals(40003, ex.getCode());
        assertEquals("密码至少 8 位，需包含大小写字母、数字和特殊字符中的至少三类", ex.getMessage());

        assertThrows(BusinessException.class,
                () -> userService.changePassword(1L, "Old@1234", null));
        verify(userMapper, never()).updatePassword(anyLong(), anyString());
    }

    // ==================== 变异补强：密码强度边界与注册字段逐项落库 ====================

    private static String repeatChar(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    @Test
    @DisplayName("注册：用户名为下限 3 字符通过校验，恰 3 类字符的 8 位密码通过强度校验并逐字段落库")
    void register_minBoundariesAndExactlyThreeCategories_shouldPersistAllFields() {
        when(passwordEncoder.encode("Abcdef12")).thenReturn("ENCODED_ABCDEF12");

        Map<String, Object> result = userService.register("abc", "Abcdef12", "e@t.com");

        assertEquals("abc", result.get("username"));
        assertEquals("DEVELOPER", result.get("role"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("abc", saved.getUsername());
        assertEquals("ENCODED_ABCDEF12", saved.getPasswordHash());
        assertEquals("e@t.com", saved.getEmail());
        assertEquals("DEVELOPER", saved.getRole());
        assertEquals(Integer.valueOf(1), saved.getStatus());
    }

    @Test
    @DisplayName("注册：小写+数字+特殊（无大写）三类字符的 8 位密码应为强密码")
    void register_lowerDigitSpecial_shouldBeStrong() {
        when(passwordEncoder.encode("abcdef1!")).thenReturn("ENCODED_LDS");

        Map<String, Object> result = userService.register("abcdef", "abcdef1!", null);

        assertEquals("abcdef", result.get("username"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：仅两类字符（小写+数字）判为弱密码，拒绝且不落库")
    void register_twoCategories_shouldRejectAsWeakPassword() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> userService.register("weakuser", "abcdefg1", null));

        assertEquals(40003, ex.getCode());
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    @DisplayName("注册：用户名为上限 50 字符通过校验（边界含等号）")
    void register_usernameAtMaxLength_shouldSucceed() {
        String username50 = repeatChar('u', 50);
        when(passwordEncoder.encode("Abcdef12")).thenReturn("ENC50");

        Map<String, Object> result = userService.register(username50, "Abcdef12", null);

        assertEquals(username50, result.get("username"));
        verify(userMapper).insert(any(User.class));
    }

    @Test
    @DisplayName("登录密码错误且接入 Redis：写入失败计数 login:attempts 键")
    void login_wrongPassword_withRedis_shouldRecordFailure() {
        injectRedis();
        when(valueOperations.get("login:lock:testuser")).thenReturn(null);
        when(userMapper.findByUsername("testuser")).thenReturn(loginUser(1L, "testuser", "DEVELOPER"));
        when(passwordEncoder.matches("wrong", "hashed_pw")).thenReturn(false);

        assertThrows(BusinessException.class, () -> userService.login("testuser", "wrong"));

        verify(valueOperations).increment("login:attempts:testuser", 1);
        verify(userMapper, never()).updateLoginTime(anyLong());
    }
}
