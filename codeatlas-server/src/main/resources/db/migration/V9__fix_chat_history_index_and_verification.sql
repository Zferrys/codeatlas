-- ============================================================
-- 对话历史表修复
-- 版本: V9
-- 用途: (1) 补 project_id 索引 —— 现有索引最左列为 user_id，项目级联删除
--           （DELETE ... WHERE project_id = ?）无法走索引，只能全表扫描
--       (2) verification 列 TEXT → MEDIUMTEXT —— 影响分析结果快照 JSON 序列化后
--           可能超过 TEXT 的 64KB 上限，导致历史记录静默写入失败
-- 注意: V8 已在生产执行，严禁修改 V8 文件（Flyway 校验和），修复一律走本文件
-- ============================================================
ALTER TABLE `chat_history` ADD INDEX `idx_chat_history_project` (`project_id`);

ALTER TABLE `chat_history` MODIFY COLUMN `verification` MEDIUMTEXT DEFAULT NULL
    COMMENT '附加数据(JSON)：问答为证据校验结果，影响分析为结果快照，无则为 NULL';
