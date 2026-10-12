-- ============================================================
-- 类摘要表新增方法名列表列
-- 版本: V7
-- 用途: 持久化扫描时解析出的方法名（JSON 数组），供智能问答读取方法级信息
-- 注意: 存量数据该列为 NULL，需重新扫描后填充
-- ============================================================
ALTER TABLE `class_summary`
    ADD COLUMN `method_names` TEXT DEFAULT NULL COMMENT '方法名列表(JSON)' AFTER `dependencies`;
