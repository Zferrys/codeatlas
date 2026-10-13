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

上传代码（Git 或 ZIP），自动解析源码、构建依赖图，生成**交互式 3D 代码拓扑地图**，并输出**健康度评分**、架构叙事与**带三层证据校验的 AI 问答**。

## AI 提示词工程

核心命题：**如何让 LLM 对大型代码库产出专业、可验证、不胡说的架构分析？**

### 1. 提示词设计

不是写一段 prompt 丢给 AI。6 套独立 Markdown 模板，每套对应一个分析阶段，`{{variable}}` 占位符渲染：

| 模板 | 用途 | 关键约束 |
|------|------|---------|
| `architecture-story.md` | 架构叙事主报告 | 800-1500 字，每个结论必须引用具体类名，中文行文+英文术语 |
| `architecture-pattern.md` | 设计模式识别 | MVC/DDD/六边形架构判定，分层合理性评分 |
| `anti-pattern-detect.md` | 反模式检测 | 8 维度（循环依赖/上帝类/跨层调用等），ERROR/WARNING/INFO 三级 |
| `constitution-check.md` | 规约合规检查 | 对用户自定义规则逐条判定，BLOCKER/CRITICAL/MAJOR/MINOR |
| `impact-analysis.md` | 变更影响分析 | BFS 路径 + 直接/间接影响 + 测试建议 + 回滚方案 |
| `context-qa.md` | 上下文问答 | 安全边界：拒绝非代码问题、拒绝执行代码、拒绝承诺修改 |

每个模板内置角色设定（"你是一位拥有15年经验的资深软件架构师"）和输出格式约束，保证结果可解析、可验证。

### 2. Token 优化

```
预估阶段：prompt.length() × 2/3 + 4096  →  预算检查（Redis Lua 原子扣减）
执行阶段：API 返回真实 usage.prompt_tokens / completion_tokens
审计阶段：逐条写入 ai_audit_log，区分 prompt/completion，按月聚合
```

- **月度预算 1000 万 Token**，Redis 分布式计数器 + Lua 原子操作，超限拒绝
- **费用核算**：统一 $8/百万 Token 费率，每次调用记录 latency、cost、success、traceId
- **Redis 预热**：启动时恢复当日已消耗 Token，避免重启逃逸配额
- **Redis 降级**：Redis 不可用时放行，不阻断业务

### 3. 上下文窗口策略

直接 dump 全部类信息进 prompt = Token 爆炸 + 注意力稀释。策略：

**Top-K 筛选**
- 按依赖数量取 **Top 15 关键类**，格式化为 Markdown 表格（FQN / 分层 / 方法数 / 行数 / 被依赖次数）
- 只取 **Top 10 依赖边**，展示为 `ShortName -> ShortName`，去 FQN 噪音
- 分层分布用聚合计数，不逐类列出

**大项目分片（>200 类）**
```
Phase 1：按顶级包前缀分组 → 每组独立 AI 调用（maxTokens=1024, temp=0.3）
         Prompt: "模块: {包名}\n类列表:\n{该包下的类}"
Phase 2：所有组结果拼接 → 汇总 AI 调用（maxTokens=4096, temp=0.3）
         Prompt: "将以下分片合并为完整的架构叙事报告..."
```
分片方案节省 Token 的关键在于：Phase 1 每个分片 prompt 只有 `模块名 + 类列表`，不加系统提示、不套模板；Phase 2 只做合并，不重新分析。

**增量上下文（影响分析）**
- 先 BFS 预计算 5 层影响路径，再喂给 AI
- AI 拿到的是**已计算好的路径图**，而非原始数据，减少推理消耗

### 4. 检索架构

AI 不读源码，读的是扫描阶段**预计算的结构化数据**：

```
源码 → JavaParser AST 解析 → ClassSummaryEntity (结构化字段)
                                    ├── fqn, simpleName, packageName
                                    ├── classType, layer
                                    ├── publicMethods, totalMethods, lineCount
                                    ├── annotations, dependencies (JSON)
                                    ├── methodNames (JSON，方法名列表)
                                    └── moduleName
```

- 依赖关系在扫描时解析为 JSON 数组存入 MySQL，AI 查询时直接反序列化
- 分层归属（Controller/Service/Repository 等）在扫描时由包名+注解规则预判定
- Neo4j 存图结构用于 BFS 影响分析，MySQL 存结构化摘要用于 AI 上下文组装
- 用户问答输入经过 `AiPromptSanitizer`：8000 字符上限、控制字符剥离、注入模式过滤

### 5. 输出校验（防幻觉）

提示词工程的外挂校验层——不依赖 prompt 约束模型"不要编造"，而是用实际数据验证输出。两条防线：

**生成侧：报告幻觉检测（HallucinationChecker）**

```
AI 报告输出 → 正则提取 FQN 形态类引用 → 与扫描落库的类摘要（MySQL，FQN/简名命中即真实）比对，常见库前缀豁免
           → 虚构类 ≥ 3 个 → temperature 0.1 重试 1 次（重试稿须过检才采纳；>200 类分片项目跳过重试）
           → 重试未通过或虚构类 > 5 个 → 记录 WARN 日志，报告照常入库（不阻断）
```

**问答侧：三层交叉验证（EvidenceVerifier）**

每次 AI 问答的答复都要过三层检查，全部对照真实数据：

| 层 | 检查内容 | 判定依据 |
|----|---------|---------|
| L1 存在性 | 提到的类是否真实存在 | 三形态提取（FQN / 已知简名 / CamelCase 候选），对照 MySQL 类摘要表；技术词与版本号后缀豁免，宁少报不误报 |
| L2 证据溯源 | 类是否来自本轮真实检索 | 必须出现在本次工具调用返回的结果集中——防"编造一个从未查询过的类" |
| L3 关系一致性 | "A 依赖 B"类声明是否属实 | 对照扫描时解析的真实依赖列表（dependencies JSON） |

未溯源引用 ≥ 2 条或存在关系矛盾时触发**修正轮**：temperature 0.1 低温重生成 + 修正结果重新校验，且**只有异常数量下降才采纳**（防越修越错）；Token 预算闸门仅智能问答路径启用（额度不足则放弃修正），影响分析的上下文问答修正轮无此闸门。前端问答区展示校验徽章：`证据校验通过` / `已自动修正` / `N 项异常`（悬停展开明细）。

### 6. 容灾架构

```
@CircuitBreaker(失败率>40%→熔断60s) + @Retry(2s→4s→8s) + @Bulkhead(独立线程池)
        │
Claude ──失败──▶ DeepSeek ──失败──▶ 降级提示 + 企业微信告警
```

- `codeatlas-engine` 模块零 Spring 依赖，AI 客户端可脱离 Boot 独立使用
- `AiClient` SPI 接口，新增模型只需实现接口并注册 Bean

## 评分体系

扫描完成后输出**健康度评分**（0–100）。扫描落库与实时查询共用同一实现，保证 Dashboard 环图、项目概览、导出报告口径一致。

**总分 = 0.4×架构合规 + 0.3×代码结构 + 0.2×代码质量 + 0.1×依赖健康**

| 维度 | 权重 | 计分规则 |
|------|------|---------|
| 架构合规 | 40% | 每个违规扣 5 分，扣分上限 40 分（10 个违规扣到底） |
| 代码结构 | 30% | 已识别分层（Controller/Service/DAO…）的类占比 |
| 代码质量 | 20% | 平均每类方法数 5–15 满分；不足 5 每少 1 扣 10 分；超 15 每多 1 扣 3 分 |
| 依赖健康 | 10% | 平均每类依赖数 1–20 满分；少于 1 得 70 分；超 20 每多 1 扣 2 分 |

违规由内置规则引擎检出（默认 6 条：Controller 直连 DAO、循环依赖、超大类、单类方法过多、Service 缺接口、星号导入，支持启用/禁用），严重级别分 BLOCKER / ERROR / WARN / INFO。AI 分析产出的建议级别（BLOCKER/CRITICAL/MAJOR/MINOR 等）只作展示，不参与评分——分数只由确定性规则驱动，避免 AI 波动导致漂移。

## 快速开始

```bash
git clone https://github.com/<your-org>/codeatlas.git && cd codeatlas
docker-compose up -d mysql redis

export DEEPSEEK_API_KEY=<key> ANTHROPIC_API_KEY=<key>
export MYSQL_PASSWORD=<pwd> CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

cd codeatlas-web && npm install && npm run dev
```

> 首次登录后立即修改默认管理员密码。

## 技术栈

Java 17 · Spring Boot 3.3 · MyBatis 3 · Neo4j · Redis · Vue 3 · Three.js · Ant Design Vue · Docker · Prometheus

## License

Apache 2.0
