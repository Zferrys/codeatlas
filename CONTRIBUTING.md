# 贡献指南

感谢你对 CodeAtlas 的关注！

## 快速开始

1. Fork 本仓库
2. 克隆到本地：`git clone <你的仓库地址>`
3. 创建分支：`git checkout -b feat/功能名称`

## 开发环境

详见 [README_ZH.md](README_ZH.md) 快速开始章节。

## 代码规范

### Java
- 遵循项目约定（构造器注入优于 `@Autowired`，SLF4J 日志）
- 禁止通配符导入（`import java.util.*`）
- MyBatis 参数化查询使用 `#{}`，禁止 `${}`
- 补丁式修复优于大范围重构

### Vue / JavaScript
- Vue 组件使用 `<script setup>` 语法
- 遵循现有组件结构和命名模式
- 主题颜色使用 CSS 变量（`var(--color-*)`）

## Pull Request 流程

1. 确保 `mvn compile` 通过
2. 确保 `mvn test` 通过
3. 确保 `codeatlas-web/` 下 `npm run build` 通过
4. 提交信息使用中文，格式：`类型：描述`
5. 关联相关 Issue

## 提交信息格式

- `feat：` — 新功能
- `修复：` — Bug 修复
- `重构：` — 代码结构调整
- `文档：` — 文档变更
- `清理：` — 构建/配置变更

## 运行测试

```bash
# 后端
mvn test

# 前端
cd codeatlas-web && npm run build
```

## 报告问题

通过 GitHub Issue 提交，请包含：
- 复现步骤
- 预期行为 vs 实际行为
- 运行环境信息（JDK 版本、操作系统等）
