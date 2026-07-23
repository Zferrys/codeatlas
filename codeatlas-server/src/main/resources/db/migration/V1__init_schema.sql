-- ============================================================
-- CodeAtlas 初始建表脚本
-- 版本: V1
-- 日期: 2026-07-22
-- ============================================================

-- ---- 用户表 ----
CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    `email`         VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar_url`    VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'VIEWER' COMMENT '角色: ADMIN/ARCHITECT/DEVELOPER/VIEWER',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1=正常 0=禁用',
    `last_login_at` DATETIME     DEFAULT NULL COMMENT '最后登录时间',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ---- 项目表 ----
CREATE TABLE `project` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(100) NOT NULL COMMENT '项目名称',
    `description`     TEXT         DEFAULT NULL COMMENT '项目描述',
    `source_type`     VARCHAR(20)  NOT NULL COMMENT '源码来源: GIT_URL/GIT_LAB/GIT_HUB/ZIP_UPLOAD/LOCAL_PATH',
    `source_url`      VARCHAR(500) DEFAULT NULL COMMENT 'Git URL 或文件路径',
    `default_branch`  VARCHAR(100) DEFAULT 'main' COMMENT '默认分支',
    `language`        VARCHAR(50)  DEFAULT 'Java' COMMENT '主语言',
    `total_classes`   INT          DEFAULT 0 COMMENT '总类数',
    `total_modules`   INT          DEFAULT 0 COMMENT '总模块数',
    `health_score`    DECIMAL(5,2) DEFAULT NULL COMMENT '架构健康度评分 0~100',
    `last_scan_id`    BIGINT       DEFAULT NULL COMMENT '最近一次扫描ID',
    `created_by`      BIGINT       NOT NULL COMMENT '创建者',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_created_by` (`created_by`),
    KEY `idx_language` (`language`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- ---- 项目成员表 ----
CREATE TABLE `project_member` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `project_id`  BIGINT   NOT NULL,
    `user_id`     BIGINT   NOT NULL,
    `role`        VARCHAR(20) NOT NULL DEFAULT 'DEVELOPER' COMMENT 'OWNER/ARCHITECT/DEVELOPER/VIEWER',
    `joined_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_user` (`project_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表';

-- ---- 扫描记录表 ----
CREATE TABLE `scan` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `commit_hash`     VARCHAR(64)  DEFAULT NULL COMMENT 'Git commit SHA',
    `branch`          VARCHAR(100) DEFAULT NULL COMMENT '分支名',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/COMPLETED/FAILED/DEGRADED',
    `total_classes`   INT          DEFAULT 0,
    `total_lines`     INT          DEFAULT 0,
    `total_violations` INT         DEFAULT 0,
    `duration_ms`     BIGINT       DEFAULT NULL,
    `error_message`   TEXT         DEFAULT NULL,
    `started_at`      DATETIME     DEFAULT NULL,
    `completed_at`    DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_status` (`status`),
    KEY `idx_project_status` (`project_id`, `status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描记录';

-- ---- 类摘要表 ----
CREATE TABLE `class_summary` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `project_id`      BIGINT       NOT NULL,
    `fqn`             VARCHAR(500) NOT NULL COMMENT '全限定类名',
    `simple_name`     VARCHAR(200) NOT NULL COMMENT '类名',
    `package_name`    VARCHAR(400) DEFAULT NULL COMMENT '包名',
    `class_type`      VARCHAR(20)  NOT NULL COMMENT 'CLASS/INTERFACE/ENUM/ABSTRACT',
    `layer`           VARCHAR(50)  DEFAULT NULL COMMENT '架构分层: CONTROLLER/SERVICE/REPOSITORY/DOMAIN/UTIL/UNKNOWN',
    `public_methods`  INT          DEFAULT 0,
    `total_methods`   INT          DEFAULT 0,
    `line_count`      INT          DEFAULT 0,
    `complexity_score` DECIMAL(8,2) DEFAULT 0,
    `annotations`     TEXT         DEFAULT NULL COMMENT '注解列表(JSON)',
    `dependencies`    TEXT         DEFAULT NULL COMMENT '依赖类列表(JSON)',
    `module_name`     VARCHAR(200) DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_scan_layer` (`scan_id`, `layer`),
    KEY `idx_fqn` (`fqn`(100)),
    KEY `idx_layer` (`layer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类摘要';

-- ---- 宪法规则定义表 ----
CREATE TABLE `constitution_rule` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       DEFAULT NULL COMMENT 'NULL=全局内置规则',
    `name`            VARCHAR(200) NOT NULL COMMENT '规则名称',
    `description`     TEXT         DEFAULT NULL,
    `category`        VARCHAR(50)  NOT NULL COMMENT 'STRUCTURE/DEPENDENCY/NAMING/SECURITY/PERFORMANCE',
    `severity`        VARCHAR(20)  NOT NULL DEFAULT 'WARN' COMMENT 'BLOCKER/ERROR/WARN/INFO',
    `rule_definition` TEXT         NOT NULL COMMENT '规则定义(JSON)',
    `is_enabled`      TINYINT      NOT NULL DEFAULT 1,
    `version`         INT          NOT NULL DEFAULT 1,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_category` (`category`),
    KEY `idx_is_enabled` (`is_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宪法规则定义';

-- ---- 违规记录表 ----
CREATE TABLE `violation` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `rule_id`         BIGINT       NOT NULL,
    `project_id`      BIGINT       NOT NULL,
    `severity`        VARCHAR(20)  NOT NULL,
    `class_fqn`       VARCHAR(500) DEFAULT NULL,
    `method_name`     VARCHAR(200) DEFAULT NULL,
    `line_number`     INT          DEFAULT NULL,
    `message`         TEXT         NOT NULL,
    `suggestion`      TEXT         COMMENT 'AI修复建议',
    `is_resolved`     TINYINT      NOT NULL DEFAULT 0,
    `resolved_at`     DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_project_severity_resolved` (`project_id`, `severity`, `is_resolved`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录';

-- ---- AI 洞察表 ----
CREATE TABLE `insight` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `project_id`      BIGINT       NOT NULL,
    `type`            VARCHAR(50)  NOT NULL COMMENT 'ARCH_STORY/ANTI_PATTERN/PATTERN/IMPACT/QA',
    `title`           VARCHAR(500) NOT NULL,
    `content`         MEDIUMTEXT   NOT NULL COMMENT '洞察内容(Markdown)',
    `confidence`      DECIMAL(4,3) DEFAULT NULL COMMENT 'AI置信度',
    `sources`         TEXT         DEFAULT NULL COMMENT '源码引用(JSON)',
    `metadata`        TEXT         DEFAULT NULL COMMENT '附加元数据(JSON)',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_project_type` (`project_id`, `type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI洞察';

-- ---- 地图视图快照表 ----
CREATE TABLE `map_view_snapshot` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `user_id`         BIGINT       NOT NULL,
    `name`            VARCHAR(200) NOT NULL,
    `view_state`      TEXT         NOT NULL COMMENT '视图状态JSON',
    `is_public`       TINYINT      NOT NULL DEFAULT 0,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地图视图快照';

-- ---- 操作审计日志 ----
CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL,
    `username`      VARCHAR(50)  NOT NULL,
    `action`        VARCHAR(50)  NOT NULL COMMENT 'LOGIN/LOGOUT/CREATE_PROJECT/DELETE_PROJECT/RUN_ANALYSIS/...',
    `target_type`   VARCHAR(50)  DEFAULT NULL,
    `target_id`     BIGINT       DEFAULT NULL,
    `detail`        TEXT         DEFAULT NULL COMMENT '操作详情JSON',
    `ip_address`    VARCHAR(50)  DEFAULT NULL,
    `user_agent`    VARCHAR(500) DEFAULT NULL,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_action` (`action`),
    KEY `idx_created_at_user` (`created_at`, `user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
