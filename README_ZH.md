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

上传代码（Git URL 或 ZIP）后，CodeAtlas 自动解析源码、构建依赖图，并通过 AI 生成交互式 **3D 代码拓扑图**。像浏览地图一样探索架构，AI 导览为你讲述架构故事。

## 为什么选择 CodeAtlas？

| 痛点 | 解决方案 |
|------|---------|
| 新成员难以理解代码库 | AI 自动生成代码地图与架构叙事，10 分钟快速上手 |
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

## AI 架构亮点

CodeAtlas 的 AI 不是简单地调用大模型 API，而是构建了一套完整的**工程化 AI 基础设施**，确保智能分析在生产环境中可靠、可控、可审计。

### 多模型容灾降级链

```
Claude API（主力）──失败──▶ DeepSeek API（备选）──失败──▶ 降级提示
        │                          │
        └── 熔断器保护 ────────────┘
```

- **自动 Fallback**：主模型不可用时毫秒级切换备选，前端无感
- **Resilience4j 熔断**：滑动窗口统计失败率 > 40% 自动熔断 60 秒，防止雪崩
- **舱壁隔离**：AI 调用独立线程池，不阻塞业务请求
- **指数退避重试**：网络抖动时最多重试 3 次，间隔 2s → 4s → 8s

### AI 幻觉检测器

大模型偶尔会"编造"不存在的类名和方法。CodeAtlas 内置幻觉检测机制：

1. 提取 AI 输出中所有 `com.xxx.Xxx` 模式的类引用
2. 与项目实际解析出的类清单（Neo4j 全量 FQN）逐条比对
3. 引用超过 3 个不存在的类 → 判定为幻觉 → 自动重试（temperature 降至 0.1）
4. 重试仍失败 → 标记分析结果为"降级模式"，前端明确告知用户

### Token 预算控制

AI API 调用烧钱，CodeAtlas 实现了细粒度成本管控：

| 维度 | 策略 |
|------|------|
| 单次上限 | 单次分析 ≤ 200K tokens，超过自动截断 |
| 项目配额 | 每个项目每日 Token 预算上限，Redis 分布式计数器 |
| 成本审计 | 每次调用记录 prompt/completion tokens、耗时、费用，写入 `ai_audit_log` |
| 月度报表 | SQL 按月聚合：总调用次数、总 Token 消耗、总费用 |

### 多阶段分析流水线

AI 不是一步到位，而是分阶段深度分析：

```
阶段1：代码概览     → 整体架构风格、技术栈识别
阶段2：模块分析     → 包结构、分层合规性、循环依赖
阶段3：类级别分析   → 关键类职责、设计模式识别
阶段4：架构叙事     → 融合上述结果，生成可读的架构故事
阶段5：风险洞察     → 反模式检测、腐化趋势、改进建议
```

### 告警与可观测性

- AI API 连续失败 3 次 → 企业微信 Webhook P1 告警
- Micrometer 指标：AI 调用量、失败率、Token 消耗、P99 延迟
- 健康检查端点 `/actuator/health` 包含 AI API 连通性探活

## 快速开始

### 环境要求

- Java 17+
- Node.js 20+
- MySQL 5.7+
- Redis 7+
- Maven 3.8+

### 启动步骤

```bash
# 1. 克隆仓库
git clone https://github.com/<your-org>/codeatlas.git
cd codeatlas

# 2. 启动依赖服务
docker-compose up -d mysql redis

# 3. 配置环境变量
export DEEPSEEK_API_KEY=<your-deepseek-api-key>
export ANTHROPIC_API_KEY=<your-claude-api-key>
export MYSQL_PASSWORD=<your-db-password>
export NEO4J_PASSWORD=<your-neo4j-password>
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

# 4. 构建并运行后端
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. 启动前端
cd codeatlas-web
npm install
npm run dev

# 6. 打开浏览器
# 后端 API: http://localhost:8080
# 前端页面: http://localhost:5173
```

> 首次启动后请立即修改默认管理员密码。

## 技术栈

| 层 | 技术 |
|------|------|
| 后端 | Java 17, Spring Boot 3.3, MyBatis 3, Neo4j, Redis |
| 前端 | Vue 3, Three.js, G6, Ant Design Vue |
| AI | Claude API / DeepSeek API（降级链 + 幻觉检测） |
| 存储 | MySQL + Neo4j（图）+ Redis（缓存/限流/预算） |
| DevOps | Docker, GitHub Actions, Prometheus + Grafana |

## 架构流程

```
上传代码 → 解析（JavaParser）→ 构建依赖图（Neo4j）
→ AI 多阶段分析管道 → 生成 3D 地图 + 架构叙事
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
