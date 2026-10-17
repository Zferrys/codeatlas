-- ============================================================
-- CodeAtlas AI 审计日志索引补充
-- 版本: V10
-- 用途: 管理端 Token 流水按使用者/阶段筛选与聚合
-- ============================================================
ALTER TABLE `ai_audit_log`
    ADD INDEX `idx_user_created` (`user_id`, `created_at`),
    ADD INDEX `idx_stage_created` (`stage`, `created_at`);
