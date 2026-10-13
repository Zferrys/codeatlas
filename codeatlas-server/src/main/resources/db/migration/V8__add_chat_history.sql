-- ============================================================
-- 对话历史表
-- 版本: V8
-- 用途: 持久化全局问答/类问答/影响分析三类对话记录，服务端每人每项目每类型仅保留最近 10 条（写入时自动裁剪）
-- ============================================================
CREATE TABLE `chat_history` (
    `id`           BIGINT        NOT NULL AUTO_INCREMENT,
    `project_id`   BIGINT        NOT NULL COMMENT '所属项目',
    `user_id`      BIGINT        NOT NULL COMMENT '提问用户',
    `scope`        VARCHAR(20)   NOT NULL COMMENT '对话类型: GLOBAL/CLASS/IMPACT',
    `context_fqn`  VARCHAR(512)  DEFAULT NULL COMMENT '类问答上下文类全限定名，其余类型为 NULL',
    `question`     VARCHAR(2000) NOT NULL COMMENT '用户问题',
    `answer`       MEDIUMTEXT    DEFAULT NULL COMMENT 'AI 回答(Markdown)',
    `verification` TEXT          DEFAULT NULL COMMENT '附加数据(JSON)：问答为证据校验结果，影响分析为结果快照，无则为 NULL',
    `created_at`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_chat_history_user_project_scope` (`user_id`, `project_id`, `scope`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话历史';
