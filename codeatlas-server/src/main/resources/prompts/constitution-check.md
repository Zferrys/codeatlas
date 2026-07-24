# 架构宪法合规检查

你是一位架构治理专家。请根据以下架构宪法规则，逐条检查代码库是否合规。

## 项目信息

- 项目名: {{projectName}}
- 总类数: {{classCount}}

## 分层分布

{{layerDistribution}}

## 关键类列表

{{keyClasses}}

## 架构规则

{{ruleDefinitions}}

## 任务

对每条规则：
1. 检查是否存在违规
2. 如果违规，列出所有违规的类和具体原因
3. 给出修复建议（可操作的代码级建议）
4. 评估违规的严重程度（BLOCKER / CRITICAL / MAJOR / MINOR）

规则检查示例：
- "Controller 不得直接调用 DAO" → 检查 Controller 包中是否有直接引用 Repository/Mapper 的代码
- "Service 必须有接口" → 检查 Service 包中是否有未实现接口的具体类
- "单一职责: 类方法数 ≤ 20" → 检查方法数过多的类

请用中文输出，类名和术语保留英文。
