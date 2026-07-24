<p align="center">
  <h1 align="center">CodeAtlas</h1>
  <p align="center">
    <strong>AI 驱动的代码架构可视化与智能分析平台</strong>
  </p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/spring--boot-3.3-brightgreen" alt="Spring Boot 3.3">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
</p>

---

上传代码（Git 地址或 ZIP），自动解析源码、构建依赖图，生成**交互式 3D 代码拓扑地图**。AI 导游讲述架构故事，像探索地图一样理解代码。

## 核心功能

- **交互式 3D 代码地图** — Three.js 力导向拓扑图，CSS2D 标签、热力图、边高亮
- **架构规约规则引擎** — 可配置治理规则，自动检测分层违规、循环依赖
- **变更影响模拟** — BFS 波纹效应分析，动画展示传播路径
- **SSE 实时进度** — 扫描进度流式推送，克隆心跳
- **RBAC 权限** — 四级角色，方法级鉴权
- **深色/浅色主题 + 中英文切换**

## AI 架构设计

这不是套壳调 API。核心在于把 AI 当作**不可靠的外部资源**来工程化治理。

### 容灾降级链

```
@CircuitBreaker（熔断） + @Retry（退避重试） + @Bulkhead（线程隔离）
        │
Claude API ──失败──▶ DeepSeek API ──失败──▶ 降级提示
```

- Resilience4j 滑动窗口：失败率 > 40% 触发熔断 60s，快速失败防雪崩
- 舱壁隔离：AI 调用独立线程池 `aiExecutor`，饱和直接拒绝，不拖业务
- 重试策略：2s → 4s → 8s 指数退避，仅对瞬时故障，最多 3 次
- Fallback 对前端透明：主模型不可用时自动切换，响应字段 `modelUsed` + `fallback: true`

### 幻觉检测

大模型会"编造"不存在的类名。CodeAtlas 用 Neo4j 实际解析结果做交叉校验：

```
AI 输出 → 提取所有 com.xxx.Xxx 类引用
       → 与 Neo4j 中真实类 FQN 比对
       → 命中 ≥ 3 个虚假引用 → 判为幻觉
       → temperature 降至 0.1 重试 1 次
       → 仍失败 → 标记 DEGRADED，前端明确告知
```

### Token 成本控制

| 机制 | 实现 |
|------|------|
| 单次硬上限 | ≥ 200K tokens 自动截断 prompt |
| 项目配额 | Redis 原子计数器，每日每项目预算封顶 |
| 全量审计 | `ai_audit_log` 表记录每次调用：tokens(区分 prompt/completion)、耗时 ms、费用、模型、traceId |
| 预算预热 | 启动时从 Redis 恢复当日已用 Token，避免重启丢失配额 |

### 五阶段分析流水线

```
概览(架构风格识别) → 模块(分层合规/循环依赖) → 类级(职责/设计模式)
                 → 叙事(融合生成可读报告) → 洞察(反模式/腐化趋势)
```

每个阶段独立 prompt 模板 + 独立 token 计数，失败不污染其他阶段。

## 快速开始

```bash
git clone https://github.com/<your-org>/codeatlas.git
cd codeatlas

# 启动依赖
docker-compose up -d mysql redis

# 环境变量
export DEEPSEEK_API_KEY=<your-key>
export ANTHROPIC_API_KEY=<your-key>
export MYSQL_PASSWORD=<your-password>
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

# 后端
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 前端
cd codeatlas-web && npm install && npm run dev
```

> 首次登录后立即修改默认管理员密码。

## 技术栈

Java 17 · Spring Boot 3.3 · MyBatis 3 · Neo4j · Redis · Vue 3 · Three.js · G6 · Ant Design Vue · Docker · GitHub Actions · Prometheus

## 项目结构

```
codeatlas/
├── codeatlas-common/     # 共享 DTO、错误码、异常（零外部依赖）
├── codeatlas-engine/     # JavaParser、规则引擎、AI 客户端（纯 Java，无 Spring）
├── codeatlas-server/     # REST API + 安全 + 缓存 + 监控
├── codeatlas-web/        # Vue 3 前端，Three.js 3D 地图
└── docs/REQUIREMENTS.md  # 完整需求规格
```

## License

Apache 2.0
