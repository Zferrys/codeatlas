<p align="center">
  <h1 align="center">CodeAtlas</h1>
  <p align="center">
    <strong>AI 驱动的代码架构可视化与智能分析平台</strong>
  </p>
</p>

<p align="center">
  <a href="README_ZH.md">完整中文文档</a> |
  <a href="docs/REQUIREMENTS.md">需求规格说明</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/spring--boot-3.3-brightgreen" alt="Spring Boot 3.3">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
</p>

---

## 项目简介

上传代码（Git 地址或 ZIP 包），CodeAtlas 自动解析源码、构建依赖关系图，并生成**交互式 3D 代码拓扑地图**。像探索地图一样浏览架构，AI 导游为你讲述项目的架构故事。

## 解决什么问题

| 痛点 | 方案 |
|------|------|
| 新人接手项目，代码看不懂 | AI 生成代码地图 + 架构叙事，10 分钟理清脉络 |
| 技术债肉眼不可见 | AI 识别反模式，在地图上标记为"架构腐化区" |
| 不知道改动会影响哪些模块 | AI 模拟变更影响波纹，动画展示传播路径 |
| 架构文档永远是过期的 | 每次扫描后自动更新，始终保持新鲜 |

## 核心功能

- **交互式 3D 代码地图** — Three.js 力导向拓扑图，支持 CSS2D 标签、热力图、边高亮
- **AI 架构叙事生成** — 多模型流水线（Claude → DeepSeek 自动切换），内置幻觉检测
- **架构规约规则引擎** — 可配置的架构治理规则，自动检测违规
- **变更影响模拟** — 基于 BFS 的波纹效应分析，结合 AI 洞察
- **SSE 实时进度推送** — 扫描进度实时流式推送，支持克隆心跳
- **RBAC 权限控制** — 四级角色（管理员/架构师/开发者/观察者）
- **国际化支持** — 中英文自由切换
- **深色/浅色主题** — 全视图覆盖

## 快速开始

### 环境要求

- Java 17+
- Node.js 20+
- MySQL 5.7+
- Redis 7+
- Maven 3.8+

### 本地部署

```bash
# 1. 克隆项目
git clone https://github.com/<your-org>/codeatlas.git
cd codeatlas

# 2. 启动依赖服务
docker-compose up -d mysql redis

# 3. 配置环境变量
export DEEPSEEK_API_KEY=<你的DeepSeek-API-Key>
export ANTHROPIC_API_KEY=<你的Claude-API-Key>
export MYSQL_PASSWORD=<数据库密码>
export NEO4J_PASSWORD=<Neo4j密码>
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

# 4. 构建并启动后端
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. 启动前端
cd codeatlas-web
npm install
npm run dev

# 6. 打开浏览器
# 后端 API：http://localhost:8080
# 前端页面：http://localhost:5173
```

> 首次登录后请立即修改默认管理员密码。

## 技术栈

| 层次 | 技术选型 |
|------|----------|
| 后端 | Java 17、Spring Boot 3.3、MyBatis 3、Neo4j、Redis |
| 前端 | Vue 3、Three.js、G6、Ant Design Vue |
| AI | Claude API / DeepSeek API，支持 fallback 链与幻觉检测 |
| 存储 | MySQL + Neo4j（图谱）+ Redis（缓存/限流/预算） |
| 运维 | Docker、GitHub Actions、Prometheus + Grafana |

## 架构流程

```
上传代码 → 解析（JavaParser） → 构建依赖图（Neo4j）
→ AI 多阶段分析流水线 → 生成 3D 地图 + 架构叙事
→ 规约规则检查 → 影响模拟
```

## 项目结构

```
codeatlas/
├── codeatlas-common/     # 共享 DTO、错误码、异常类
├── codeatlas-engine/     # JavaParser、规则引擎、Git 服务、AI 客户端
├── codeatlas-server/     # Spring Boot REST API
├── codeatlas-web/        # Vue 3 前端
├── docs/                 # 文档
│   └── REQUIREMENTS.md   # 完整需求规格说明
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## 参与贡献

欢迎提交贡献！请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源协议

Apache 2.0 — 详见 [LICENSE](LICENSE)。
