package com.codeatlas.server.aspect;

import com.codeatlas.server.annotation.AuditLog;
import com.codeatlas.server.entity.AuditLogEntity;
import com.codeatlas.server.entity.User;
import com.codeatlas.server.mapper.AuditLogMapper;
import com.codeatlas.server.mapper.UserMapper;
import com.codeatlas.server.security.CodeAtlasUserDetails;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;

@Aspect
@Component
public class AuditLogAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAspect.class);
    private static final ExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer PARAM_DISCOVERER = new DefaultParameterNameDiscoverer();

    private final AuditLogMapper auditLogMapper;
    private final UserMapper userMapper;

    public AuditLogAspect(AuditLogMapper auditLogMapper, UserMapper userMapper) {
        this.auditLogMapper = auditLogMapper;
        this.userMapper = userMapper;
    }

    @Around("@annotation(auditLog)")
    public Object around(ProceedingJoinPoint joinPoint, AuditLog auditLog) throws Throwable {
        Object result = joinPoint.proceed();

        try {
            AuditLogEntity entity = new AuditLogEntity();
            entity.setAction(auditLog.action());
            entity.setTargetType(auditLog.targetType().isEmpty() ? null : auditLog.targetType());
            entity.setDetail(auditLog.detail().isEmpty() ? null : auditLog.detail());

            // 解析 SpEL 表达式获取 targetId
            if (!auditLog.targetIdExpression().isEmpty()) {
                entity.setTargetId(evaluateTargetId(joinPoint, result, auditLog.targetIdExpression()));
            }

            // 优先从 SpEL 表达式获取用户名（用于未认证场景，如登录）
            if (!auditLog.usernameExpression().isEmpty()) {
                String username = evaluateUsername(joinPoint, auditLog.usernameExpression());
                entity.setUsername(username);
            }

            // 从 SecurityContext 获取当前用户（已认证场景）
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof CodeAtlasUserDetails) {
                CodeAtlasUserDetails user = (CodeAtlasUserDetails) auth.getPrincipal();
                entity.setUserId(user.getUserId());
                if (entity.getUsername() == null) {
                    entity.setUsername(user.getUsername());
                }
            }

            // 未认证场景（如登录）：通过 username 反查 userId
            if (entity.getUserId() == null && entity.getUsername() != null) {
                User user = userMapper.findByUsername(entity.getUsername());
                if (user != null) {
                    entity.setUserId(user.getId());
                }
            }

            // 跳过无法确定用户的审计事件
            if (entity.getUserId() == null) {
                log.debug("Skipping audit log: no user identified for action={}", auditLog.action());
                return result;
            }

            // 获取请求IP和UserAgent
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                entity.setIpAddress(attrs.getRequest().getRemoteAddr());
                entity.setUserAgent(attrs.getRequest().getHeader("User-Agent"));
            }

            auditLogMapper.insert(entity);
        } catch (Exception e) {
            log.warn("Failed to write audit log: {}", e.getMessage());
        }

        return result;
    }

    private String evaluateUsername(ProceedingJoinPoint joinPoint, String expression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String[] paramNames = PARAM_DISCOVERER.getParameterNames(method);
            Object[] args = joinPoint.getArgs();

            EvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            Object value = SPEL_PARSER.parseExpression(expression).getValue(context);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            log.debug("SpEL username evaluation failed: {}", e.getMessage());
            return null;
        }
    }

    private Long evaluateTargetId(ProceedingJoinPoint joinPoint, Object result, String expression) {
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();
            String[] paramNames = PARAM_DISCOVERER.getParameterNames(method);
            Object[] args = joinPoint.getArgs();

            EvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }
            context.setVariable("result", result);

            Object value = SPEL_PARSER.parseExpression(expression).getValue(context);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception e) {
            log.debug("SpEL evaluation failed: {}", e.getMessage());
        }
        return null;
    }
}
