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

## 生产级工程实践

这不是一个 Demo，而是一套按照企业标准构建的生产级系统。以下是从代码中可以直接验证的工程决策：

### 安全纵深防御

```
请求进入 → CORS 白名单校验 → JWT 签名验证 → RBAC 方法级鉴权 → 参数化查询 → 响应脱敏
              │                    │               │                  │
              └─ 环境变量配置       └─ 黑名单吊销     └─ @PreAuthorize   └─ MyBatis #{}
```

| 机制 | 实现 |
|------|------|
| 认证 | BCrypt 密码哈希 + JWT 无状态令牌，过期时间按环境可配（开发 24h / 生产 8h） |
| 鉴权 | 4 级 RBAC 角色，方法级 `@PreAuthorize` 注解，AOP 审计日志自动记录操作人 |
| 注入防护 | MyBatis 全链路 `#{}` 参数化，零字符串拼接；文件上传白名单校验（类型+大小） |
| 限流 | Redis Lua 脚本实现分布式令牌桶，登录 10 次/分钟，通用 API 20 QPS |
| 敏感信息 | 所有密钥/密码通过环境变量注入，JWT Token 日志输出自动脱敏 |
| 依赖审计 | CI 集成 OWASP Dependency Check，CVSS ≥ 7 阻断构建 |

### 数据架构设计

```
┌─────────┐     ┌─────────┐     ┌─────────┐
│  MySQL  │     │  Neo4j  │     │  Redis  │
│ 关系存储 │     │ 图存储   │     │ 缓存层   │
├─────────┤     ├─────────┤     ├─────────┤
│ 项目元数据│     │ 类依赖图  │     │ 地图缓存  │
│ 用户/角色 │     │ 方法调用链 │     │ 限流计数  │
│ 审计日志  │     │ 包结构树  │     │ Token预算 │
│ AI审计   │     │ 影响路径  │     │ 会话黑名单 │
│ 扫描记录  │     │ 反模式标记 │     │ 分布式锁  │
└─────────┘     └─────────┘     └─────────┘
```

**为什么用 Neo4j 而不是在 MySQL 里存关联表？** 代码依赖关系本质是有向图——一个类的变更影响分析需要 BFS 多层遍历，SQL 的递归 CTE 在 5 层以上性能急剧退化，而 Neo4j 原生图遍历无压力到 20+ 层。

### 弹性工程三板斧

```
@CircuitBreaker (熔断)      → 失败率 > 40% 自动断开 60s，快速失败优于雪崩
@Retry (重试)              → 指数退避 2s→4s→8s，最多 3 次，仅对瞬时故障
@Bulkhead (舱壁)           → AI 调用独立线程池，饱和时直接拒绝，不拖垮主业务
```

同时作用于：AI API 调用、Redis 连接、Neo4j 查询。不是简单加个注解，而是根据每种资源特性调了独立参数。

### 数据库变更管控

所有 DDL 通过 **Flyway 版本化迁移**，SQL 脚本纳入 Git 版本控制：

```
V1__init_schema.sql       → 核心表结构
V2__seed_rules.sql        → 内置架构规则
V3__seed_admin_user.sql   → 预置管理员（BCrypt 哈希）
V4__ai_audit_log.sql      → AI 审计表
V5__add_performance_indexes.sql → 性能索引
```

CI 流水线有专门的 `flyway-validation` Job，每次 PR 自动校验迁移脚本的 checksum 未被篡改。

### 优雅关闭

```java
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=30s
```

Kill 信号到达时：停止接受新请求 → 等待进行中的扫描写入检查点 → 30s 超时强制终止 → 进程退出。临时工作目录在启动时自动清理孤儿残留（JVM 崩溃也不是问题）。

### CI 质量门禁

```
PR 提交 → mvn compile → Checkstyle (0 容忍) → PMD → 单元测试 (54 个)
       → JaCoCo 覆盖率 ≥ 70% → OWASP 漏洞扫描 → Flyway 校验 → Docker 构建
```

全部通过才算 CI 绿。六道门禁，一道不过就不能合并。

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

# 3. 配置环境变量（生产环境切勿使用弱密码）
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
上传代码 → JavaParser AST 解析 (支持 Java 17 语法)
→ 提取类/方法/字段/注解 → 构建 Neo4j 依赖图 (class_contains_method 等关系)
→ AI 五阶段分析流水线 → 生成 3D 拓扑图 (Three.js 力导向布局)
→ 规约规则引擎 → BFS 变更影响模拟 → 结果持久化
```

## 项目结构

```
codeatlas/
├── codeatlas-common/     # 共享 DTO、错误码、异常类（零外部依赖）
├── codeatlas-engine/     # JavaParser、规则引擎、Git 服务、AI 客户端（纯 Java）
├── codeatlas-server/     # Spring Boot REST API + 安全 + 缓存 + 监控
├── codeatlas-web/        # Vue 3 前端，Three.js 3D 地图 + SSE 实时推送
├── docs/                 # 文档
│   └── REQUIREMENTS.md   # 24 章完整需求规格说明
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

> `codeatlas-engine` 被刻意设计为无 Spring 依赖的纯 Java 模块——规则引擎和解析器可以独立测试，未来可以直接给 Android 或命令行工具复用。

## 参与贡献

欢迎提交贡献！请参阅 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 开源协议

Apache 2.0 — 详见 [LICENSE](LICENSE)。
