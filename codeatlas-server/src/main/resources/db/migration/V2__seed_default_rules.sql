-- ============================================================
-- 内置宪法规则种子数据
-- ============================================================

INSERT INTO `constitution_rule` (`project_id`, `name`, `description`, `category`, `severity`, `rule_definition`, `is_enabled`, `version`)
VALUES
(NULL, 'no-controller-direct-dao', 'Controller 不得直接调用 DAO/Repository，必须通过 Service',
 'DEPENDENCY', 'BLOCKER',
 '{"type":"forbidden_dependency","from":"CONTROLLER","to":"REPOSITORY"}', 1, 1),

(NULL, 'max-public-methods', '单一类 public 方法不超过 20 个',
 'STRUCTURE', 'WARN',
 '{"type":"method_limit","max":20,"scope":"public"}', 1, 1),

(NULL, 'no-circular-dependency', '模块间不得存在循环依赖',
 'DEPENDENCY', 'BLOCKER',
 '{"type":"no_cycle","scope":"module"}', 1, 1),

(NULL, 'service-interface-required', 'Service 层类必须有对应接口',
 'STRUCTURE', 'ERROR',
 '{"type":"interface_required","layer":"SERVICE"}', 1, 1),

(NULL, 'max-class-line-count', '单类代码行数不超过 500 行',
 'STRUCTURE', 'INFO',
 '{"type":"line_limit","max":500}', 1, 1),

(NULL, 'no-star-import', '禁止使用通配符 import (*)',
 'NAMING', 'INFO',
 '{"type":"forbidden_pattern","pattern":"import\\s+\\w+\\.\\*"}', 1, 1);
