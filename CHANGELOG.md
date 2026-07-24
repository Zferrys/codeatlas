# 更新日志

格式基于 [Keep a Changelog](https://keepachangelog.com/en/1.0.0/)，版本号遵循[语义化版本](https://semver.org/)。

## [0.2.0] — 未发布

### 新增
- Resilience4j 熔断器保护 AI API 调用
- Redis 分布式限流（Lua 脚本实现）
- SSE 实时扫描进度推送
- 文件上传支持 ZIP 解压并自动扫描
- PDF/HTML 报告导出（iText 7）
- 项目和类的全局搜索
- Neo4j 依赖关系图可视化
- Redis 缓存层（类型安全序列化）
- 健康检查端点（MySQL、Redis、AI API）
- 深色/浅色主题切换，覆盖全部组件
- 自定义 `@Timed` Micrometer 指标（AI 分析、扫描耗时）
- 限流命中计数器（Micrometer）

### 变更
- RuleEngine 迁移至 `codeatlas-engine` 模块（纯 Java，无 Spring 依赖）
- 所有列表接口支持服务端分页
- ClassSummary 和 Violation 改为批量 INSERT（替代 N+1 逐条插入）
- AI 分析改用 `@CircuitBreaker` + `@Retry` + `@Bulkhead` 组合注解

### 修复
- SSE 推送中 `AccessDeniedException` 通过 SecurityConfig 中 `DispatcherType.ASYNC` 放行解决
- Redis 缓存反序列化 `ClassCastException`（LinkedHashMap → Entity）
- 深色主题覆盖项目卡片、洞察、代码地图和认证页面
- JavaParser 语言级别升级至 JAVA_17，支持 Text Block Literals

## [0.1.0] — 2026-07-15

### 新增
- 项目管理（从 Git URL 创建、ZIP 上传、本地路径）
- Java 代码解析（JavaParser AST 提取、类摘要）
- 二维力导向代码地图（G6）
- 三维拓扑地图（Three.js CodeMap3D）
- AI 架构叙事生成（Claude + DeepSeek）
- 架构规约规则引擎（内置 6 条规则）
- JWT 认证与 RBAC 权限控制（ADMIN/ARCHITECT/DEVELOPER/VIEWER）
- AOP 审计日志
- Flyway 数据库迁移（V1 建表、V2 规则种子、V3 管理员账号）
- Knife4j API 文档
- MDC 请求追踪（X-Trace-Id）
