-- H2 测试用表结构（与 MySQL 对齐）
CREATE TABLE IF NOT EXISTS `user` (
    `id`            BIGINT AUTO_INCREMENT PRIMARY KEY,
    `username`      VARCHAR(50)  NOT NULL,
    `password_hash` VARCHAR(255) NOT NULL,
    `email`         VARCHAR(100),
    `avatar_url`    VARCHAR(500),
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'VIEWER',
    `status`        TINYINT      NOT NULL DEFAULT 1,
    `last_login_at` TIMESTAMP,
    `created_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_username ON `user` (`username`);
CREATE UNIQUE INDEX IF NOT EXISTS uk_email ON `user` (`email`);

CREATE TABLE IF NOT EXISTS `project` (
    `id`              BIGINT AUTO_INCREMENT PRIMARY KEY,
    `name`            VARCHAR(100) NOT NULL,
    `description`     TEXT,
    `source_type`     VARCHAR(20)  NOT NULL,
    `source_url`      VARCHAR(500),
    `default_branch`  VARCHAR(100) DEFAULT 'main',
    `language`        VARCHAR(50)  DEFAULT 'Java',
    `total_classes`   INT          DEFAULT 0,
    `total_modules`   INT          DEFAULT 0,
    `health_score`    DECIMAL(5,2),
    `last_scan_id`    BIGINT,
    `created_by`      BIGINT       NOT NULL,
    `created_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS `project_member` (
    `id`          BIGINT AUTO_INCREMENT PRIMARY KEY,
    `project_id`  BIGINT NOT NULL,
    `user_id`     BIGINT NOT NULL,
    `role`        VARCHAR(20) NOT NULL DEFAULT 'DEVELOPER',
    `joined_at`   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_user ON `project_member` (`project_id`, `user_id`);

CREATE TABLE IF NOT EXISTS `scan` (
    `id`               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `project_id`       BIGINT NOT NULL,
    `commit_hash`      VARCHAR(64),
    `branch`           VARCHAR(100),
    `status`           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    `total_classes`    INT DEFAULT 0,
    `total_lines`      INT DEFAULT 0,
    `total_violations` INT DEFAULT 0,
    `duration_ms`      BIGINT,
    `error_message`    TEXT,
    `started_at`       TIMESTAMP,
    `completed_at`     TIMESTAMP,
    `created_at`       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
