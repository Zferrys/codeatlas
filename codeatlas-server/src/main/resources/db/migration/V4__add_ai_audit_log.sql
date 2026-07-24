-- ============================================================
-- CodeAtlas AI 审计日志表
-- 版本: V4
-- 用途: 记录每次 AI 调用的 token 消耗、费用、耗时
-- ============================================================
CREATE TABLE IF NOT EXISTS `ai_audit_log` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `trace_id`          VARCHAR(64)  DEFAULT NULL COMMENT '请求链路追踪ID',
    `model`             VARCHAR(64)  NOT NULL COMMENT 'AI 模型标识',
    `stage`             VARCHAR(32)  NOT NULL COMMENT '分析阶段: ARCHITECTURE_STYLE/LAYER_IDENTIFICATION/DEPENDENCY_GRAPH/CONSTITUTION_RULES/NARRATIVE',
    `project_id`        BIGINT       DEFAULT NULL COMMENT '关联项目ID',
    `user_id`           BIGINT       DEFAULT NULL COMMENT '触发用户ID',
    `prompt_tokens`     INT          DEFAULT 0 COMMENT '输入 Token 数',
    `completion_tokens` INT          DEFAULT 0 COMMENT '输出 Token 数',
    `total_tokens`      INT          DEFAULT 0 COMMENT 'Token 总数',
    `latency_ms`        INT          DEFAULT 0 COMMENT '调用耗时(毫秒)',
    `cost`              DECIMAL(10,5) DEFAULT 0 COMMENT '预估费用(美元)',
    `success`           TINYINT(1)   DEFAULT 1 COMMENT '是否成功: 1=成功 0=失败',
    `error_message`     VARCHAR(500) DEFAULT NULL COMMENT '失败时的错误信息',
    `created_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_model_created` (`model`, `created_at`),
    INDEX `idx_project_created` (`project_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 调用审计日志';
