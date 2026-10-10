-- ============================================================
-- CodeAtlas 系统配置表（通用键值对）
-- 版本: V6
-- 用途: 存储可在管理后台运行期修改的系统级配置（如 AI 当前使用模型）
-- ============================================================
CREATE TABLE IF NOT EXISTS `system_config` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `config_key`   VARCHAR(64)  NOT NULL COMMENT '配置键',
    `config_value` VARCHAR(512) NOT NULL COMMENT '配置值',
    `description`  VARCHAR(255) DEFAULT NULL COMMENT '配置说明',
    `created_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_system_config_key` (`config_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统配置键值表';
