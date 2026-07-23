package com.codeatlas.server.annotation;

import java.lang.annotation.*;

/**
 * 操作审计日志注解 — 标记需要记录操作日志的方法。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    /** 操作类型: LOGIN/LOGOUT/CREATE_PROJECT/RUN_SCAN/TOGGLE_RULE/... */
    String action();

    /** 操作对象类型: PROJECT/SCAN/RULE/USER */
    String targetType() default "";

    /** 操作对象ID的 SpEL 表达式，如 "#projectId" 或 "#request.id" */
    String targetIdExpression() default "";

    /** 操作描述 */
    String detail() default "";

    /** 用户名的 SpEL 表达式，如 "#request.username"，用于未认证场景（登录） */
    String usernameExpression() default "";
}
