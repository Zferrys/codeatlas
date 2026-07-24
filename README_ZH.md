<p align="center">
  <h1 align="center">CodeAtlas</h1>
  <p align="center"><strong>AI 驱动的代码架构可视化与智能分析平台</strong></p>
</p>

<p align="center">
  <a href="README.md">English</a> |
  <a href="docs/REQUIREMENTS.md">完整文档</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/spring--boot-3.3-brightgreen" alt="Spring Boot 3.3">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
</p>

---

## 什么是 CodeAtlas？

上传你的代码（Git URL 或 ZIP 文件）→ CodeAtlas 解析代码、构建依赖图，AI 生成交互式 **3D 代码拓扑图**。像谷歌地球一样探索你的架构，AI 导览为你讲述架构故事。

## 为什么选择 CodeAtlas？

| 痛点 | CodeAtlas 解决方案 |
|------|-------------------|
| 新成员难以理解代码库 | AI 自动生成代码地图 + 架构故事，10 分钟快速上手 |
| 技术债务不可见 | AI 检测反模式，在地图上标记为"衰减区域" |
| 变更影响不可知 | AI 模拟变更的涟漪效应并动画展示 |
| 架构文档永远过时 | 每次扫描自动更新 |

## 核心功能

- **交互式 3D 代码地图** — Three.js 力导向拓扑图，支持 CSS2D 类名标签、热力模式、边高亮
- **AI 架构叙事** — 多模型管道（Claude → DeepSeek 自动降级），带幻觉检测
- **宪法规则引擎** — 可配置的架构治理规则，自动检测违规
- **变更影响模拟** — 基于 BFS 的涟漪效应分析，AI 辅助解读
- **实时进度推送** — SSE 流式推送扫描进度，含克隆心跳
- **RBAC 权限控制** — 四级角色（管理员/架构师/开发者/查看者）
- **国际化** — 中英文语言切换
- **暗色/亮色主题** — 全视图主题支持

## 快速开始

```bash
# 1. 克隆
git clone https://github.com/zferrys/codeatlas.git
cd codeatlas

# 2. 启动依赖
docker-compose up -d mysql redis

# 3. 配置环境变量
export DEEPSEEK_API_KEY=your-deepseek-key
export ANTHROPIC_API_KEY=your-claude-key
export MYSQL_PASSWORD=your-db-password
export NEO4J_PASSWORD=your-neo4j-password
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

# 4. 构建并运行
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. 打开浏览器
open http://localhost:8080
```

默认管理员账号: `admin` / `admin123`

## 技术栈

| 层 | 技术 |
|------|------|
| 后端 | Java 17, Spring Boot 3.3, MyBatis 3, Neo4j, Redis |
| 前端 | Vue 3, Three.js, G6, Ant Design Vue |
| AI | Claude API / DeepSeek API（带降级链 + 幻觉检测） |
| 存储 | MySQL + Neo4j（图）+ Redis（缓存/限流/预算） |
| DevOps | Docker, GitHub Actions, Prometheus + Grafana |

## 架构

```
上传代码 → 解析（JavaParser）→ 构建依赖图（Neo4j）
→ AI 5阶段分析管道 → 生成3D地图 + 架构叙事
→ 宪法规则检查 → 影响模拟
```

## 项目结构

```
codeatlas/
├── codeatlas-common/     # 共享 DTO、错误码、异常
├── codeatlas-engine/     # JavaParser、RuleEngine、GitService、AI 客户端
├── codeatlas-server/     # Spring Boot REST API
├── codeatlas-web/        # Vue 3 前端
├── docs/                 # 文档
│   └── REQUIREMENTS.md   # 完整需求规格
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## 参与贡献

欢迎贡献！详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源协议

Apache 2.0 — 详见 [LICENSE](LICENSE)。
