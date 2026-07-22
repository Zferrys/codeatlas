# CodeAtlas — AI 驱动的代码地图与架构叙事平台

> **版本**: v1.1  
> **日期**: 2026-07-22  
> **作者**: Zferrys  
> **定位**: 企业级 AIGC + 代码智能化平台，简历旗舰项目，GitHub 开源  
> **变更**: v1.1 补充日志监控、异常处理、AI 容灾降级、CI/CD、API 文档、健康检查、性能优化、GitHub 运营、DB 迁移 9 大章节

---

## 目录

1. [项目概述](#1-项目概述)
2. [核心创新点](#2-核心创新点)
3. [功能模块拆分](#3-功能模块拆分)
4. [系统架构设计](#4-系统架构设计)
5. [技术栈](#5-技术栈)
6. [AI 集成设计](#6-ai-集成设计)
7. [数据库设计](#7-数据库设计)
8. [前端架构设计](#8-前端架构设计)
9. [API 接口设计](#9-api-接口设计)
10. [安全设计](#10-安全设计)
11. [测试策略](#11-测试策略)
12. [扩展点设计](#12-扩展点设计)
13. [部署架构](#13-部署架构)
14. [开发路线图](#14-开发路线图)
15. [日志与监控](#15-日志与监控)
16. [异常处理与错误码规范](#16-异常处理与错误码规范)
17. [AI 调用容灾、降级与成本控制](#17-ai-调用容灾降级与成本控制)
18. [CI/CD 流水线与代码质量门禁](#18-cicd-流水线与代码质量门禁)
19. [API 文档自动生成](#19-api-文档自动生成)
20. [健康检查与优雅关闭](#20-健康检查与优雅关闭)
21. [性能优化策略](#21-性能优化策略)
22. [GitHub 开源运营规范](#22-github-开源运营规范)
23. [数据库版本迁移](#23-数据库版本迁移)
24. [附录](#24-附录)

---

## 1. 项目概述

### 1.1 一句话定位

> 上传代码 → AI 自动生成交互式 3D 代码拓扑地图，像 Google 地球一样逛代码，AI 导游讲述架构故事。

### 1.2 痛点分析

| 痛点 | 现状 | CodeAtlas 解法 |
|------|------|---------------|
| 新成员看不懂老项目 | 看文档（过期的）、问人（打断别人）、自己看代码（慢） | AI 自动生成代码地图 + 架构叙事，10 分钟看懂架构 |
| 技术债务不可见 | "感觉这块代码有点乱"，但说不出具体哪里乱 | AI 检测架构反模式，在地图上热力高亮"腐化区域" |
| 改代码不知道波及范围 | grep 找调用方，靠经验猜影响面 | AI 模拟变更波及路径，地图上动画展示影响链 |
| 架构文档永远过期 | 手动画的架构图，三个月后就跟代码对不上 | 每次代码变更自动更新地图，永远实时 |

### 1.3 目标用户

| 角色 | 核心场景 |
|------|---------|
| **新入职开发** | 快速理解项目结构，不依赖人肉文档 |
| **架构师/Tech Lead** | 审查架构合规，发现反模式，评审技术决策 |
| **开发工程师** | 改动前评估影响面，日常代码导航 |
| **技术管理者** | 技术债务可视化，团队代码质量趋势 |

### 1.4 参考 Claude 架构的核心设计理念

本项目的架构设计深度参考 Anthropic Claude 的核心理念，但不是简单的"套个 AI 聊天"：

| Claude 概念 | CodeAtlas 映射 | 具体体现 |
|-------------|---------------|---------|
| **Constitutional AI** | 架构宪法规则引擎 | 可配置的架构规则，AI 审查结果必须逐条校验 |
| **Multi-layer Safety** | 多层分析管线 | 语法层→依赖层→架构层→语义层，层层递进 |
| **Tool Use / MCP** | 分析工具编排器 | AI 可调用 JGit、JavaParser、图数据库等多种工具 |
| **Extended Thinking** | 分析推理链可视化 | 用户可看到 AI 分析的每一步推理过程 |
| **Context Management** | 代码上下文窗口 | 高效管理百万行代码的语义上下文 |
| **Evaluation Framework** | 质量评估管线 | 每条 AI 洞察经过多维度评分后才会展示 |

---

## 2. 核心创新点

### 2.1 交互式代码拓扑地图（不是静态架构图）

- **3D 力导向图**：每个包/模块是一个节点，依赖关系是边，力导向布局自动聚类
- **缩放语义**：不同缩放级别显示不同粒度（项目级→模块级→包级→类级→方法级）
- **热力图层**：切换显示复杂度热力、变更频率热力、Bug 密度热力
- **时间轴回放**：拖动时间轴看架构演进过程（基于 Git 历史）

### 2.2 AI 架构叙事引擎（不是聊天框）

- **自动生成"架构故事"**：AI 看完整个项目后，像写博客一样输出：
  - 这个项目是什么，核心业务流程
  - 分层架构及每一层的职责
  - 核心链路（如"订单创建"的全流程涉及的类）
  - 历史遗留问题和改进建议
- **上下文感知问答**：在地图特定节点上提问，AI 只回答这个节点相关的内容（不是通用聊天）
- **导游模式**：AI 带你按推荐路线游览代码库，逐步讲解

### 2.3 变更影响地震模拟

- **输入**：指定要修改的类/方法
- **AI 分析**：分析直接/间接依赖、调用链、事务边界、数据流向
- **输出**：地图上以震源（修改点）为中心，波纹扩散动画展示影响范围，红色越深影响越高风险

### 2.4 架构宪法规则引擎

- **不是硬编码的 Lint 规则**，而是可配置的"架构宪法条款"，例如：
  - "Controller 不得直接调用 DAO，必须通过 Service"
  - "每个 Service 的 public 方法不能超过 15 个"
  - "循环依赖为非法"
- **AI 审查**：AI 理解代码语义后判断是否违反宪法，而非简单的正则/模式匹配
- **违宪热力图**：地图上红色标记所有违宪点

---

## 3. 功能模块拆分

### 3.1 模块全景图

```
CodeAtlas
├── 1. 项目管理模块 (Project Management)
│   ├── 创建/导入项目（Git URL / 上传 ZIP / 本地路径）
│   ├── 项目列表与概览仪表板
│   └── 项目成员与权限管理
├── 2. 代码摄取模块 (Code Ingestion)          ← 核心引擎
│   ├── Git 仓库克隆与管理（JGit）
│   ├── 多语言代码解析（JavaParser + SPI 扩展）
│   ├── AST 提取与结构化存储
│   └── 增量更新（只分析变更文件）
├── 3. 依赖分析模块 (Dependency Analysis)      ← 核心引擎
│   ├── 类级依赖关系图谱构建
│   ├── 包级/模块级/服务级聚合
│   ├── 循环依赖检测
│   └── 依赖冲突检测（JAR 版本冲突）
├── 4. 地图渲染模块 (Map Visualization)        ← 前端焦点
│   ├── 3D 力导向拓扑图（D3.js / Three.js）
│   ├── 多层级缩放（LOD）
│   ├── 热力图层切换
│   ├── 时间轴架构演进回放
│   └── 节点搜索与导航
├── 5. AI 分析模块 (AI Analysis Engine)        ← 核心卖点
│   ├── 架构模式识别（分层、六边形、DDD 等）
│   ├── 架构反模式检测（上帝类、循环依赖、包耦合等）
│   ├── 架构叙事生成（Markdown 格式的架构故事）
│   ├── 变更影响分析
│   └── 上下文感知问答
├── 6. 宪法规则模块 (Constitution Engine)       ← Claude 理念核心
│   ├── 规则定义（DSL + 可视化编辑器）
│   ├── 规则执行管线（多层评估）
│   ├── 违规检测与报告
│   └── 规则模板市场（内置常用规则）
├── 7. 洞察与报告模块 (Insights & Reports)
│   ├── 架构健康度评分（总分 + 各维度分）
│   ├── 技术债务追踪（随时间变化曲线）
│   ├── 团队代码贡献热力图
│   └── PDF/HTML 报告导出
├── 8. 用户系统模块 (User System)
│   ├── 注册/登录（JWT）
│   ├── 角色权限（RBAC：Admin/Architect/Developer/Viewer）
│   └── 操作审计日志
└── 9. 实时协作模块 (Real-time Collaboration)    ← 扩展预留
    ├── WebSocket 实时推送分析进度
    ├── 多人同时浏览地图（光标可见）
    └── 评论与标注（在地图节点上）
```

### 3.2 版本迭代计划

#### Phase 1 - MVP 核心闭环（目标：能跑通全流程）

| 编号 | 功能 | 优先级 | 说明 |
|------|------|--------|------|
| P1-1 | 项目管理（创建、导入 Git 项目） | P0 | 最基础的入口 |
| P1-2 | Java 代码解析（JavaParser AST 提取） | P0 | 先只支持 Java |
| P1-3 | 类级依赖图谱构建（存入 Neo4j） | P0 | 地图的数据基础 |
| P1-4 | 2D 力导向拓扑图渲染（D3.js） | P0 | 先用 2D，后升级 3D |
| P1-5 | 基础 AI 分析（架构叙事生成） | P0 | 调用 Claude/GPT API |
| P1-6 | 用户注册登录（JWT） | P0 | 基本安全 |
| P1-7 | 分析进度实时推送（SSE） | P1 | 体验提升 |

#### Phase 2 - 核心体验增强

| 编号 | 功能 | 优先级 | 说明 |
|------|------|--------|------|
| P2-1 | 3D 力导向图升级（Three.js） | P1 | 视觉冲击力 |
| P2-2 | 架构反模式检测 | P1 | AI 分析核心卖点 |
| P2-3 | 变更影响地震模拟 | P1 | 差异化 killer feature |
| P2-4 | 架构宪法规则引擎 | P1 | Claude 理念落地的核心 |
| P2-5 | 多层级地图缩放（LOD） | P2 | |
| P2-6 | 架构健康度评分 | P2 | |

#### Phase 3 - 企业与扩展

| 编号 | 功能 | 优先级 | 说明 |
|------|------|--------|------|
| P3-1 | 多语言支持（Python、Go → SPI 扩展） | P1 | 通过扩展点实现 |
| P3-2 | 时间轴架构演进回放 | P2 | 基于 Git 历史 |
| P3-3 | PDF/HTML 报告导出 | P2 | |
| P3-4 | RBAC 权限体系完善 | P2 | |
| P3-5 | 多人实时协作 | P3 | |
| P3-6 | 规则模板市场 | P3 | |

---

## 4. 系统架构设计

### 4.1 整体架构拓扑

```
┌─────────────────────────────────────────────────────────────────────┐
│                         FRONTEND (Vue 3)                             │
│  ┌───────────┐  ┌───────────┐  ┌──────────┐  ┌───────────────────┐ │
│  │ Project   │  │ 3D Map    │  │ AI Story │  │ Constitution      │ │
│  │ Dashboard │  │ Renderer  │  │ Panel    │  │ Rule Editor       │ │
│  └─────┬─────┘  └─────┬─────┘  └────┬─────┘  └────────┬──────────┘ │
│        │              │             │                  │            │
│  ┌─────┴──────────────┴─────────────┴──────────────────┴──────────┐ │
│  │                    API Client Layer (Axios + SSE + WS)          │ │
│  └───────────────────────────────┬─────────────────────────────────┘ │
└──────────────────────────────────┼──────────────────────────────────┘
                                   │ HTTPS / WSS
┌──────────────────────────────────┼──────────────────────────────────┐
│                     API GATEWAY (Spring Boot + Nginx)                │
│  ┌───────────────────────────────┴─────────────────────────────────┐│
│  │              Spring Security + JWT + CORS + Rate Limit            ││
│  └───────────────────────────────┬─────────────────────────────────┘│
│                                   │                                   │
│  ┌────────────────────────────────┴──────────────────────────────┐  │
│  │                    BUSINESS LAYER (Spring Boot)                │  │
│  │                                                                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │  │
│  │  │ Project  │  │ Ingest   │  │ Analysis │  │ AI Pipeline   │ │  │
│  │  │ Service  │  │ Service  │  │ Engine   │  │ Orchestrator  │ │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────────┘ │  │
│  │                                                                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │  │
│  │  │ Constit- │  │ Insight  │  │ Report   │  │ User &        │ │  │
│  │  │ ution    │  │ Service  │  │ Service  │  │ Permission    │ │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────────┘ │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   │                                   │
│  ┌────────────────────────────────┴──────────────────────────────┐  │
│  │                     ENGINE LAYER (底层引擎)                     │  │
│  │                                                                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │  │
│  │  │ JGit     │  │ Java-    │  │ Graph    │  │ AI Client     │ │  │
│  │  │ Engine   │  │ Parser   │  │ Builder  │  │ (HTTP Client) │ │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────────┘ │  │
│  │                                                                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │  │
│  │  │ Rule     │  │ Report   │  │ Event    │  │ Extension     │ │  │
│  │  │ Evaluator│  │ Builder  │  │ Bus      │  │ SPI Loader    │ │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────────┘ │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                   │                                   │
│  ┌────────────────────────────────┴──────────────────────────────┐  │
│  │                      DATA LAYER                                │  │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌───────────────┐ │  │
│  │  │ MySQL    │  │ Neo4j    │  │ Redis    │  │ MinIO/OSS     │ │  │
│  │  │ (元数据)  │  │ (依赖图)  │  │ (缓存)   │  │ (文件存储)    │ │  │
│  │  └──────────┘  └──────────┘  └──────────┘  └───────────────┘ │  │
│  └───────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
                                   │
┌──────────────────────────────────┴──────────────────────────────────┐
│                     EXTERNAL SERVICES                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────────────┐  │
│  │ Claude API   │   │ DeepSeek API │   │ GitHub/GitLab API        │  │
│  │ (AI 分析)    │   │ (备选 AI)    │   │ (仓库集成)               │  │
│  └──────────────┘   └──────────────┘   └──────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────┘
```

### 4.2 AI 分析管线（多阶段流水线）

这是整个项目最核心的设计，**直接参考 Claude Extended Thinking + Multi-layer Safety** 思路：

```
                    ┌─────────────┐
                    │  Code Input  │
                    └──────┬──────┘
                           │
            ┌──────────────▼──────────────┐
            │  STAGE 1: 语法解析层         │
            │  │ 工具: JavaParser           │
            │  │ 输出: AST + 类结构 + 方法签名│
            │  │ 校验: 语法错误拦截          │
            └──────────────┬──────────────┘
                           │
            ┌──────────────▼──────────────┐
            │  STAGE 2: 依赖分析层         │
            │  │ 工具: AST → 依赖图构建     │
            │  │ 输出: 类级依赖有向图        │
            │  │ 校验: 外部依赖完整性检查    │
            └──────────────┬──────────────┘
                           │
            ┌──────────────▼──────────────┐
            │  STAGE 3: 架构识别层  ← AI   │
            │  │ AI 分析依赖图 + 代码片段    │
            │  │ 输出: 架构模式(分层/六边形)  │
            │  │ 输出: 模块职责与边界         │
            │  │ 校验: 识别置信度评分         │
            └──────────────┬──────────────┘
                           │
            ┌──────────────▼──────────────┐
            │  STAGE 4: 宪法审视层  ← AI   │
            │  │ AI 逐条校验架构规则         │
            │  │ 输出: 违规列表 + 位置 + 建议 │
            │  │ 校验: 规则覆盖率检查         │
            └──────────────┬──────────────┘
                           │
            ┌──────────────▼──────────────┐
            │  STAGE 5: 叙事生成层  ← AI   │
            │  │ AI 生成架构故事 (Markdown)  │
            │  │ AI 生成关键链路说明          │
            │  │ 校验: 内容质量评分 + 去幻觉   │
            └──────────────┬──────────────┘
                           │
                    ┌──────▼──────┐
                    │  Final Output│
                    │  → 地图数据  │
                    │  → 叙事文档  │
                    │  → 违规报告  │
                    │  → 健康评分  │
                    └─────────────┘
```

**每层设计的核心原则**：每一层**只做自己的事**，输出经过校验后传给下一层。这跟 Claude 的 Model → Safety → Output 完全对应。

### 4.3 代码项目模块划分

```
codeatlas/
├── codeatlas-common/          # 公共模块：常量、异常、工具类、DTO
├── codeatlas-engine/          # 核心引擎模块（底层，不依赖 Spring）
│   ├── git/                   # JGit 操作封装
│   ├── parser/                # 代码解析器接口 + Java 实现
│   │   ├── LanguageParser     # SPI 接口 ← 扩展点
│   │   ├── JavaParserImpl     # JavaParser 实现
│   │   └── parser-registry    # 解析器注册中心
│   ├── graph/                 # 依赖图谱构建
│   │   ├── DependencyGraph    # 图构建核心
│   │   ├── CycleDetector      # 循环依赖检测
│   │   └── GraphExporter      # 图数据导出（Neo4j/JSON）
│   ├── ai/                    # AI 客户端（纯 HTTP，无框架）
│   │   ├── AiClient           # 统一 AI 调用接口 ← 扩展点
│   │   ├── ClaudeClient       # Claude API 实现
│   │   ├── DeepSeekClient     # DeepSeek 实现
│   │   └── PromptTemplate     # Prompt 模板管理
│   └── rule/                  # 规则引擎
│       ├── RuleDefinition     # 规则定义模型
│       ├── RuleEvaluator      # 规则评估器
│       └── RuleDsl            # 规则 DSL 解析
├── codeatlas-server/          # Spring Boot 业务服务
│   ├── controller/            # REST API 控制器
│   ├── service/               # 业务服务层
│   │   ├── ProjectService
│   │   ├── IngestService      # 代码摄取服务
│   │   ├── AnalysisService    # 分析编排服务
│   │   ├── AiPipelineService  # AI 管线编排
│   │   ├── ConstitutionService # 宪法规则服务
│   │   ├── InsightService     # 洞察服务
│   │   ├── ReportService      # 报告服务
│   │   └── UserService
│   ├── repository/            # MyBatis Mapper
│   ├── config/                # Spring 配置
│   ├── security/              # 安全配置（JWT + RBAC）
│   └── websocket/             # WebSocket 实时推送
├── codeatlas-web/             # Vue 3 前端
│   ├── src/
│   │   ├── views/             # 页面
│   │   ├── components/        # 组件
│   │   │   ├── map/           # 地图可视化组件
│   │   │   ├── ai/            # AI 面板组件
│   │   │   └── common/        # 通用组件
│   │   ├── stores/            # Pinia 状态管理
│   │   ├── api/               # API 请求封装
│   │   └── router/            # 路由
│   └── package.json
└── docs/                      # 文档
    ├── REQUIREMENTS.md         # 本文档
    ├── ARCHITECTURE.md         # 架构详解
    ├── API.md                  # 接口文档
    └── EXTENSION.md            # 扩展开发指南
```

### 4.4 核心分层依赖规则

```
┌──────────────────────────────────────────────────────┐
│  Controller 层                                       │
│  职责: 接收请求、参数校验、调用 Service、返回结果        │
│  依赖: ↓ Service                                     │
│  禁止: ✗ 直接调 Repository ✗ 直接调 Engine            │
├──────────────────────────────────────────────────────┤
│  Service 层                                          │
│  职责: 业务编排、事务管理、权限校验                      │
│  依赖: ↓ Repository  ↓ Engine                        │
│  禁止: ✗ 循环依赖 ✗ 跨模块直接调 Controller             │
├──────────────────────────────────────────────────────┤
│  Repository 层 (MyBatis Mapper)                      │
│  职责: 数据访问、SQL 执行                              │
│  依赖: ↓ MySQL / Neo4j                               │
├──────────────────────────────────────────────────────┤
│  Engine 层 (纯 POJO，不依赖 Spring)                    │
│  职责: 核心算法、AI 调用、规则评估                      │
│  依赖: ↓ SPI 接口                                    │
│  禁止: ✗ 依赖 Spring ✗ 依赖 Service/Controller        │
├──────────────────────────────────────────────────────┤
│  SPI 扩展层                                          │
│  职责: 定义扩展契约，供第三方实现                        │
│  依赖: 无（纯净接口）                                  │
└──────────────────────────────────────────────────────┘
```

---

## 5. 技术栈

### 5.1 后端

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **语言** | Java | 8 (JDK 1.8) | 兼容用户环境 |
| **框架** | Spring Boot | 2.7.x | 稳定版，不用 3.x（Spring AI 依赖陷阱） |
| **Web** | Spring MVC | — | REST API |
| **ORM** | MyBatis | 3.x | 手写 SQL，灵活可控 |
| **图数据库** | Neo4j | 4.x / 5.x (Community) | 依赖图谱存储与查询 |
| **关系数据库** | MySQL | 5.7.19 | 元数据存储 |
| **缓存** | Redis | 5.x / 6.x | 会话、热数据缓存、进度状态 |
| **安全** | Spring Security + JWT | — | 认证 + 授权 |
| **Git 操作** | JGit | 5.x | 纯 Java Git 实现，无系统依赖 |
| **代码解析** | JavaParser | 3.x | Java AST 解析 |
| **HTTP 客户端** | OkHttp | 4.x | AI API HTTP 调用 |
| **JSON** | Jackson | — | JSON 序列化 |
| **连接池** | HikariCP | — | Spring Boot 默认，MySQL 连接池 |
| **实时通信** | Spring WebSocket + SSE | — | 分析进度推送 |
| **构建工具** | Maven | 3.6+ | 多模块构建 |

**刻意不用的框架（及原因）**：

| 框架 | 不用原因 |
|------|---------|
| LangChain4j | 封装太厚，学不到底层；约束性强，难以定制 AI 管线 |
| Spring AI | 绑定 Spring 生态，JDK 8 支持差；我们直接调 API 更灵活 |
| MyBatis Plus | 简化 CRUD 但手写 SQL 更可控，且本项目数据模型复杂需精细控制 SQL |

### 5.2 前端

| 类别 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **框架** | Vue | 3.x (Composition API) | 渐进式，适合本项目复杂度 |
| **构建工具** | Vite | 5.x | 快 |
| **状态管理** | Pinia | 2.x | Vue 3 官方推荐 |
| **路由** | Vue Router | 4.x | |
| **UI 组件** | Ant Design Vue | 4.x | 企业级中后台组件 |
| **2D 可视化** | D3.js | 7.x | 力导向图核心 |
| **3D 可视化** | Three.js | r160+ | 3D 拓扑图（Phase 2） |
| **代码展示** | CodeMirror | 6.x | 代码预览 + 语法高亮 |
| **HTTP** | Axios | 1.x | API 请求 |
| **图表** | ECharts | 5.x | 统计图表（健康度仪表盘等） |
| **Markdown** | marked + highlight.js | — | AI 叙事内容渲染 |
| **CSS** | SCSS + CSS Variables | — | 主题变量 |

### 5.3 AI 服务

| 服务 | 用途 | 调用方式 |
|------|------|---------|
| **Claude API** (Anthropic) | 主要 AI 分析引擎 | OkHttp → HTTP POST → `/v1/messages` |
| **DeepSeek API** | 备选 AI（低成本方案） | OkHttp → HTTP POST → `/v1/chat/completions` |
| **扩展：OpenAI API** | 扩展预留 | 通过 AiClient SPI 切换 |

### 5.4 基础设施（可选，按需升级）

| 环境 | 方案 |
|------|------|
| **开发环境** | Windows / macOS 本地：MySQL + Neo4j + Redis 全本地 |
| **测试环境** | Docker Compose 一键启动（MySQL + Neo4j + Redis） |
| **生产环境** | 云服务器（阿里云/腾讯云）：Nginx 反向代理 + 域名 |

---

## 6. AI 集成设计

### 6.1 设计原则

1. **零框架依赖**：直接用 OkHttp 发 HTTP POST，不依赖任何 AI 框架
2. **SPI 可替换**：通过 `AiClient` 接口抽象，换模型只需加一个实现类
3. **Prompt 外置管理**：所有 prompt 模板存放在 `resources/prompts/` 目录，不在代码里硬编码
4. **流式返回**：SSE (Server-Sent Events) 推送分析进度到前端
5. **去幻觉**：每段 AI 输出必须附带 `source` 引用（具体类名/方法名），前端可点击验证

### 6.2 AiClient 接口设计

```java
// 统一 AI 客户端接口 — 扩展点
public interface AiClient {

    /** 非流式调用（用于分析结果生成） */
    AiResponse chat(AiRequest request);

    /** 流式调用（用于实时分析进度） */
    void chatStream(AiRequest request, StreamCallback callback);

    /** 获取模型名称 */
    String getModelName();

    /** 健康检查 */
    boolean healthCheck();
}

// 请求模型
public class AiRequest {
    private String prompt;           // 完整 prompt
    private String systemPrompt;     // system 角色 prompt
    private double temperature;      // 0.0~1.0
    private int maxTokens;           // 最大 token 数
    private Map<String, Object> metadata; // 扩展字段（如 reasoning 模式）
}

// 响应模型
public class AiResponse {
    private String content;          // AI 回复正文
    private int tokensUsed;          // 消耗 token 数
    private long latencyMs;          // 耗时
    private List<AiSource> sources;  // 引用的源码位置
}

// 源码引用（去幻觉的关键）
public class AiSource {
    private String className;        // 全限定类名
    private String methodName;       // 方法名
    private int lineNumber;          // 行号
    private String snippet;          // 代码片段（前端验证用）
}
```

### 6.3 Prompt 模板管理

Prompt 存放在 `resources/prompts/` 目录，使用占位符语法：

```
resources/prompts/
├── architecture-pattern.md        # 架构模式识别 prompt
├── architecture-story.md          # 架构叙事生成 prompt
├── anti-pattern-detect.md         # 反模式检测 prompt
├── constitution-check.md          # 宪法规则校验 prompt
├── impact-analysis.md             # 变更影响分析 prompt
└── context-qa.md                  # 上下文问答 prompt
```

**模板示例** (`architecture-story.md`)：

```markdown
## System
You are a senior software architect. Analyze the given codebase and produce an architectural narrative.

## Context
- Project Name: {{projectName}}
- Primary Language: {{language}}
- Total Classes: {{classCount}}
- Total Modules: {{moduleCount}}

## Dependency Graph (JSON)
```json
{{dependencyGraph}}
```

## Key Class Summaries
{{classSummaries}}

## Task
Generate a comprehensive architectural story in Markdown covering:
1. **Overview**: What this project does, the core business domain
2. **Architecture Style**: Identify the architecture pattern (Layered / Hexagonal / DDD / Microkernel / ...)
3. **Layer Analysis**: Explain each layer's responsibility and key classes
4. **Core Flow**: Walk through 2-3 most important business flows with class interactions
5. **Design Decisions**: Notable design choices and their tradeoffs
6. **Improvement Suggestions**: 2-3 actionable improvements with reasoning

## Output Requirements
- Each architectural claim must reference specific classes (e.g., `com.example.order.OrderService`)
- Use plain, conversational tone (like a senior dev explaining to a new teammate)
- Format: Clean Markdown with headers, bullets, and code references

## Strictly Forbidden
- Do NOT hallucinate classes that don't exist in the provided data
- Do NOT skip the source references
- If you're uncertain about something, say "likely" or "possibly" rather than stating it as fact
```

### 6.4 AI 分析管线实现

```
AiPipelineOrchestrator
    │
    ├── Stage1: SyntaxParseStage
    │   ├── 输入: Git clone 的源码路径
    │   ├── 工具: JavaParser (AST)
    │   ├── 输出: ClassSummary 列表 (类名、方法、字段、注解)
    │   └── 校验: 语法错误检查
    │
    ├── Stage2: DependencyBuildStage
    │   ├── 输入: ClassSummary 列表
    │   ├── 工具: DependencyGraphBuilder
    │   ├── 输出: 有向图 (类→依赖类)
    │   ├── 输出: 循环依赖列表
    │   └── 校验: 图连通性检查
    │
    ├── Stage3: ArchitectureStyleStage [AI]
    │   ├── 输入: 依赖图JSON + 类摘要
    │   ├── AI prompt: architecture-pattern.md
    │   ├── 输出: 架构模式 + 分层结构
    │   ├── 校验: 对输出的每个类引用做存在性验证
    │   └── ← 扩展点: AiClient 可替换
    │
    ├── Stage4: ConstitutionCheckStage [AI]
    │   ├── 输入: 架构模式 + 宪法规则列表
    │   ├── AI prompt: constitution-check.md
    │   ├── 输出: 违规列表 + 违反的规则 + 建议
    │   ├── 校验: 每条违规必须有源码位置
    │   └── ← 扩展点: RuleDefinition 可新增
    │
    ├── Stage5: NarrativeGenerationStage [AI]
    │   ├── 输入: 架构模式 + 类摘要 + 核心流程
    │   ├── AI prompt: architecture-story.md
    │   ├── 输出: Markdown 格式的架构故事
    │   ├── 校验: 事实准确性交叉验证
    │   └── ← 扩展点: 可生成不同风格的叙事
    │
    └── Stage6: MapDataAssemblyStage
        ├── 输入: 依赖图 + 分层 + 叙事
        ├── 输出: 前端渲染用的 MapData JSON
        └── 存入: Redis(热数据) + MySQL(持久化)
```

### 6.5 SSE 分析进度推送

```
                         Client (Vue)
                            │
               HTTP GET /analysis/{id}/progress
               Accept: text/event-stream
                            │
        ┌───────────────────┼───────────────────┐
        │ id: 1             │ id: 2              │ id: n
        │ event: stage      │ event: progress    │ event: complete
        │ data: {"stage":"  │ data: {"pct":65,   │ data: {"result":{..}}
        │   dependency",    │   "msg":"分析依赖中" } │
        │   "status":"running"}                   │
        └───────────────────┴────────────────────┘
```

---

## 7. 数据库设计

### 7.1 数据库分层策略

```
┌─────────────────────────────────────────────┐
│  MySQL (元数据 + 业务数据)                     │
│  │ 用户、项目、扫描记录、AI分析结果、规则、      │
│  │ 违规、洞察、报告、审计日志、配置              │
│  │ 特性: ACID、事务、关联查询                   │
├─────────────────────────────────────────────┤
│  Neo4j (依赖图)                               │
│  │ 节点: Class / Package / Module             │
│  │ 边: DEPENDS_ON / CONTAINS / EXTENDS       │
│  │ 特性: 图遍历、最短路径、循环检测、中心性计算   │
├─────────────────────────────────────────────┤
│  Redis (缓存 + 实时状态)                       │
│  │ Session (JWT Token)、分析进度、热数据       │
│  │ 特性: 高性能、TTL、发布订阅                   │
└─────────────────────────────────────────────┘
```

### 7.2 MySQL 表设计

#### 7.2.1 用户与权限

```sql
-- 用户表
CREATE TABLE `user` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `username`      VARCHAR(50)  NOT NULL COMMENT '用户名，登录用',
    `password_hash` VARCHAR(255) NOT NULL COMMENT 'BCrypt 加密密码',
    `email`         VARCHAR(100) DEFAULT NULL COMMENT '邮箱',
    `avatar_url`    VARCHAR(500) DEFAULT NULL COMMENT '头像URL',
    `role`          VARCHAR(20)  NOT NULL DEFAULT 'VIEWER' COMMENT '角色: ADMIN/ARCHITECT/DEVELOPER/VIEWER',
    `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态: 1=正常 0=禁用',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 操作审计日志表
CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NOT NULL COMMENT '操作用户ID',
    `username`      VARCHAR(50)  NOT NULL COMMENT '操作用户名（冗余，便于查询）',
    `action`        VARCHAR(50)  NOT NULL COMMENT '操作类型: LOGIN/LOGOUT/CREATE_PROJECT/DELETE_PROJECT/RUN_ANALYSIS/...',
    `target_type`   VARCHAR(50)  DEFAULT NULL COMMENT '操作对象类型: PROJECT/SCAN/RULE',
    `target_id`     BIGINT       DEFAULT NULL COMMENT '操作对象ID',
    `detail`        TEXT         DEFAULT NULL COMMENT '操作详情JSON',
    `ip_address`    VARCHAR(50)  DEFAULT NULL COMMENT '请求IP',
    `user_agent`    VARCHAR(500) DEFAULT NULL COMMENT 'User-Agent',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_action` (`action`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作审计日志';
```

#### 7.2.2 项目管理

```sql
-- 项目表
CREATE TABLE `project` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(100) NOT NULL COMMENT '项目名称',
    `description`     TEXT         DEFAULT NULL COMMENT '项目描述',
    `source_type`     VARCHAR(20)  NOT NULL COMMENT '源码来源: GIT_URL / GIT_LAB / GIT_HUB / ZIP_UPLOAD / LOCAL_PATH',
    `source_url`      VARCHAR(500) DEFAULT NULL COMMENT 'Git URL 或文件路径',
    `default_branch`  VARCHAR(100) DEFAULT 'main' COMMENT '默认分支',
    `language`        VARCHAR(50)  DEFAULT 'Java' COMMENT '主语言: Java/Python/Go/...',
    `total_classes`   INT          DEFAULT 0 COMMENT '总类数（分析后更新）',
    `total_modules`   INT          DEFAULT 0 COMMENT '总模块数（分析后更新）',
    `health_score`    DECIMAL(5,2) DEFAULT NULL COMMENT '架构健康度评分 0~100',
    `last_scan_id`    BIGINT       DEFAULT NULL COMMENT '最近一次扫描ID',
    `created_by`      BIGINT       NOT NULL COMMENT '创建者',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_created_by` (`created_by`),
    KEY `idx_language` (`language`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目表';

-- 项目成员表（协作）
CREATE TABLE `project_member` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `project_id`  BIGINT   NOT NULL,
    `user_id`     BIGINT   NOT NULL,
    `role`        VARCHAR(20) NOT NULL DEFAULT 'DEVELOPER' COMMENT '项目角色: OWNER/ARCHITECT/DEVELOPER/VIEWER',
    `joined_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_project_user` (`project_id`, `user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目成员表';
```

#### 7.2.3 扫描与分析

```sql
-- 扫描记录表
CREATE TABLE `scan` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `commit_hash`     VARCHAR(64)  DEFAULT NULL COMMENT 'Git commit SHA',
    `branch`          VARCHAR(100) DEFAULT NULL COMMENT '分支名',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT '状态: PENDING/RUNNING/COMPLETED/FAILED',
    `total_classes`   INT          DEFAULT 0 COMMENT '扫描到的类数',
    `total_lines`     INT          DEFAULT 0 COMMENT '扫描到的代码行数',
    `total_violations` INT         DEFAULT 0 COMMENT '发现的违规数',
    `duration_ms`     BIGINT       DEFAULT NULL COMMENT '耗时（毫秒）',
    `error_message`   TEXT         DEFAULT NULL COMMENT '失败原因',
    `started_at`      DATETIME     DEFAULT NULL,
    `completed_at`    DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_status` (`status`),
    KEY `idx_created_at` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='扫描记录';

-- 类摘要表（结构化存储分析结果）
CREATE TABLE `class_summary` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `project_id`      BIGINT       NOT NULL,
    `fqn`             VARCHAR(500) NOT NULL COMMENT '全限定类名 com.example.OrderService',
    `simple_name`     VARCHAR(200) NOT NULL COMMENT '类名 OrderService',
    `package_name`    VARCHAR(400) DEFAULT NULL COMMENT '包名 com.example',
    `class_type`      VARCHAR(20)  NOT NULL COMMENT '类型: CLASS/INTERFACE/ENUM/ABSTRACT',
    `layer`           VARCHAR(50)  DEFAULT NULL COMMENT '架构分层: CONTROLLER/SERVICE/REPOSITORY/DOMAIN/UTIL/UNKNOWN',
    `public_methods`  INT          DEFAULT 0 COMMENT 'public方法数',
    `total_methods`   INT          DEFAULT 0 COMMENT '总方法数',
    `line_count`      INT          DEFAULT 0 COMMENT '代码行数',
    `complexity_score` DECIMAL(8,2) DEFAULT 0 COMMENT '圈复杂度评分',
    `annotations`     TEXT         DEFAULT NULL COMMENT '注解列表（JSON数组）',
    `dependencies`    TEXT         DEFAULT NULL COMMENT '直接依赖的类列表（JSON数组）',
    `module_name`     VARCHAR(200) DEFAULT NULL COMMENT '所属模块/服务',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_fqn` (`fqn`(100)),
    KEY `idx_layer` (`layer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='类摘要';
```

#### 7.2.4 架构宪法

```sql
-- 宪法规则定义表
CREATE TABLE `constitution_rule` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       DEFAULT NULL COMMENT 'NULL=全局内置规则，非NULL=项目自定义规则',
    `name`            VARCHAR(200) NOT NULL COMMENT '规则名称',
    `description`     TEXT         DEFAULT NULL COMMENT '规则描述',
    `category`        VARCHAR(50)  NOT NULL COMMENT '分类: STRUCTURE/DEPENDENCY/NAMING/SECURITY/PERFORMANCE',
    `severity`        VARCHAR(20)  NOT NULL DEFAULT 'WARN' COMMENT '严重程度: BLOCKER/ERROR/WARN/INFO',
    `rule_definition` TEXT         NOT NULL COMMENT '规则定义（DSL或JSON）',
    `is_enabled`      TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用',
    `version`         INT          NOT NULL DEFAULT 1 COMMENT '版本号',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_category` (`category`),
    KEY `idx_is_enabled` (`is_enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='宪法规则定义';

-- 内置规则数据
-- INSERT INTO constitution_rule (project_id, name, description, category, severity, rule_definition) VALUES
-- (NULL, 'no-controller-direct-dao', 'Controller 不得直接调用 DAO/Repository',
--  'DEPENDENCY', 'BLOCKER', '{"type":"forbidden_dependency","from":"CONTROLLER","to":"REPOSITORY"}'),
-- (NULL, 'max-public-methods', '单一类 public 方法不超过20个', 'STRUCTURE', 'WARN',
--  '{"type":"method_limit","max":20,"scope":"public"}'),
-- (NULL, 'no-circular-dependency', '模块间不得存在循环依赖', 'DEPENDENCY', 'BLOCKER',
--  '{"type":"no_cycle","scope":"module"}'),
-- (NULL, 'service-interface-required', 'Service 层必须有对应接口', 'STRUCTURE', 'ERROR',
--  '{"type":"interface_required","layer":"SERVICE"}');

-- 违规记录表
CREATE TABLE `violation` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `rule_id`         BIGINT       NOT NULL COMMENT '关联的规则',
    `project_id`      BIGINT       NOT NULL,
    `severity`        VARCHAR(20)  NOT NULL COMMENT '严重程度（快照）',
    `class_fqn`       VARCHAR(500) DEFAULT NULL COMMENT '违规位置（类）',
    `method_name`     VARCHAR(200) DEFAULT NULL COMMENT '违规位置（方法）',
    `line_number`     INT          DEFAULT NULL COMMENT '违规位置（行号）',
    `message`         TEXT         NOT NULL COMMENT '违规描述',
    `suggestion`      TEXT         COMMENT '修复建议（AI 生成）',
    `is_resolved`     TINYINT      NOT NULL DEFAULT 0 COMMENT '是否已修复',
    `resolved_at`     DATETIME     DEFAULT NULL,
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_severity` (`severity`),
    KEY `idx_is_resolved` (`is_resolved`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='违规记录';
```

#### 7.2.5 AI 洞察与地图数据

```sql
-- AI 洞察表
CREATE TABLE `insight` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `scan_id`         BIGINT       NOT NULL,
    `project_id`      BIGINT       NOT NULL,
    `type`            VARCHAR(50)  NOT NULL COMMENT '类型: ARCH_STORY/ANTI_PATTERN/PATTERN/IMPACT/QA',
    `title`           VARCHAR(500) NOT NULL COMMENT '洞察标题',
    `content`         MEDIUMTEXT   NOT NULL COMMENT '洞察内容（Markdown）',
    `confidence`      DECIMAL(4,3) DEFAULT NULL COMMENT 'AI 置信度 0.000~1.000',
    `sources`         TEXT         DEFAULT NULL COMMENT '源码引用列表（JSON）',
    `metadata`        TEXT         DEFAULT NULL COMMENT '附加元数据（JSON）',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_scan_id` (`scan_id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_type` (`type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI洞察';

-- 地图视图快照（前端保存的视图状态）
CREATE TABLE `map_view_snapshot` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `project_id`      BIGINT       NOT NULL,
    `user_id`         BIGINT       NOT NULL,
    `name`            VARCHAR(200) NOT NULL COMMENT '视图名称',
    `view_state`      TEXT         NOT NULL COMMENT '视图状态JSON（缩放级别、位置、图层等）',
    `is_public`       TINYINT      NOT NULL DEFAULT 0 COMMENT '是否公开',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_project_id` (`project_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='地图视图快照';
```

### 7.3 Neo4j 图模型

```
节点 (Node Labels):
┌──────────────────────────────────────────────────────────────┐
│  :Project       {id, name, language}                        │
│  :Module        {id, name, layer}                           │
│  :Package       {id, name, fqn}                             │
│  :Class         {id, fqn, simpleName, classType, layer,     │
│                  publicMethods, totalMethods, lineCount,     │
│                  complexityScore}                            │
│  :Method        {id, name, signature, visibility}           │
│  :Interface     {id, fqn, simpleName}                       │
│  :Annotation    {id, name}                                  │
│  :ExternalLib   {id, groupId, artifactId, version}          │
└──────────────────────────────────────────────────────────────┘

关系 (Relationships):
┌──────────────────────────────────────────────────────────────┐
│  [:CONTAINS]        Project→Module→Package→Class→Method     │
│  [:DEPENDS_ON]      Class→Class（直接依赖，带 weight 属性）    │
│  [:EXTENDS]         Class→Class                              │
│  [:IMPLEMENTS]      Class→Interface                          │
│  [:ANNOTATED_WITH]  Class/Method→Annotation                  │
│  [:CALLS]           Method→Method                            │
│  [:IMPORTS]         Class→ExternalLib                        │
│  [:BELONGS_TO]      Class→Module（模块归属）                  │
└──────────────────────────────────────────────────────────────┘
```

**典型 Cypher 查询示例**：

```cypher
-- 查询两个类之间的最短依赖路径
MATCH path = shortestPath((a:Class {fqn: $from})-[*..10]->(b:Class {fqn: $to}))
RETURN path

-- 检测循环依赖
MATCH cycle = (c:Class)-[:DEPENDS_ON*2..10]->(c)
RETURN cycle

-- 查询某个模块的核心类（PageRank 中心性）
MATCH (c:Class {moduleName: $module})
WITH c, size((c)-[:DEPENDS_ON]->()) AS outgoing,
        size((c)<-[:DEPENDS_ON]-()) AS incoming
RETURN c.fqn, incoming AS fanIn, outgoing AS fanOut
ORDER BY incoming DESC
LIMIT 20
```

### 7.4 Redis 设计

| Key Pattern | 类型 | TTL | 说明 |
|-------------|------|-----|------|
| `session:{token}` | String (JSON) | 24h | JWT 会话信息 |
| `analysis:progress:{scanId}` | String (JSON) | 1h | 分析进度 |
| `map:data:{projectId}` | String (JSON) | 30min | 地图数据热缓存 |
| `insight:{projectId}:latest` | String (JSON) | 30min | 最新的 AI 洞察 |
| `rate_limit:{userId}:{action}` | String | 可配置 | 接口限流计数器 |
| `config:{projectId}:rules` | String (JSON) | 无过期 | 宪法规则缓存（主动失效） |

---

## 8. 前端架构设计

### 8.1 页面路由

```
/                         → 首页（平台介绍、特色展示）
/login                    → 登录页
/register                 → 注册页

/dashboard                → 用户仪表板（项目列表）
/project/:id              → 项目详情页
  /project/:id/overview   → 项目概览（健康度仪表盘 + 扫描历史）
  /project/:id/map        → ★ 代码地图页（核心页面）
  /project/:id/story      → ★ 架构叙事页（AI 生成的架构故事）
  /project/:id/rules      → 宪法规则管理页
  /project/:id/violations → 违规列表页
  /project/:id/insights   → AI 洞察列表页
  /project/:id/settings   → 项目设置

/admin                    → 管理后台（仅 ADMIN）
  /admin/users            → 用户管理
  /admin/audit-log        → 审计日志
```

### 8.2 核心页面组件树

```
代码地图页 (/project/:id/map) — 最核心的页面

ProjectMapView.vue
├── MapToolbar.vue                  ← 工具栏
│   ├── SearchBar.vue               ← 类/包搜索（语义搜索）
│   ├── LayerToggle.vue             ← 图层切换
│   │   ├── 依赖关系图层
│   │   ├── 架构分层图层
│   │   ├── 复杂度热力图层
│   │   ├── 违规高亮图层          ← 对应宪法规则检查结果
│   │   └── 变更频率图层 (Git history)
│   ├── ZoomControls.vue            ← 缩放控件
│   └── ViewActions.vue             ← 导出/分享/截图
│
├── MapCanvas.vue                   ← 核心画布
│   ├── ForceGraph.vue              ← 力导向图 (D3.js / Three.js)
│   │   ├── NodeComponent.vue       ← 节点渲染（类/包/模块）
│   │   ├── EdgeComponent.vue       ← 边渲染（依赖关系）
│   │   ├── ClusterComponent.vue    ← 聚类边界渲染
│   │   └── HotLayerComponent.vue  ← 热力图层
│   ├── ImpactSimulator.vue         ← ★ 变更影响地震模拟
│   └── MiniMap.vue                 ← 缩略导航图
│
├── SidePanelManager.vue            ← 侧边面板管理器
│   ├── NodeDetailPanel.vue         ← 类/包详情（点击节点弹出）
│   │   ├── ClassInfo.vue           ← 类基本信息
│   │   ├── MethodList.vue          ← 方法列表
│   │   ├── DependencyList.vue      ← 依赖关系
│   │   └── CodePreview.vue         ← 代码预览 (CodeMirror)
│   │
│   ├── AiContextPanel.vue          ← ★ AI 上下文面板（不是聊天框）
│   │   ├── NodeContext.vue         ← 当前节点相关的 AI 洞察
│   │   ├── ArchitectureStory.vue   ← 该模块的架构叙事片段
│   │   ├── RelatedInsight.vue      ← 相关的 AI 发现
│   │   └── AskAboutNode.vue        ← 对该节点的上下文提问
│   │
│   └── ViolationPanel.vue          ← 违规详情面板
│       ├── ViolationList.vue       ← 该节点的违规列表
│       └── SuggestionView.vue      ← AI 修复建议
│
└── TimelineBar.vue                 ← ★ 时间轴（架构演进回放）
    ├── CommitSelector.vue          ← 选择 Git commit
    ├── PlaybackControls.vue        ← 播放控制
    └── DiffIndicator.vue           ← 变更量指示器
```

### 8.3 核心交互流程

```
用户进入地图页
    │
    ├── 地图初始加载
    │   ├── 调用 /api/project/{id}/map-data 获取最新地图JSON
    │   ├── D3.js/Three.js 渲染力导向图
    │   └── 初始缩放级别: 模块级
    │
    ├── 用户滚轮缩放
    │   ├── 缩小时: 模块合并为服务/系统 → 显示大局观
    │   ├── 放大时: 模块展开为包 → 包展开为类 → 显示细节
    │   └── 前端 LOD (Level of Detail) 策略:
    │       ├── L1 (服务级): 只显示模块节点 + 模块间依赖
    │       ├── L2 (包级): 显示包节点 + 包间依赖
    │       ├── L3 (类级): 显示类节点 + 类间依赖
    │       └── L4 (方法级): 显示方法 + 调用关系
    │
    ├── 用户点击节点
    │   ├── 右侧滑出 NodeDetailPanel
    │   ├── 同时加载 AI 上下文（AiContextPanel）
    │   └── 地图上高亮该节点的所有关联路径
    │
    ├── 用户搜索类名
    │   ├── 输入 "OrderService"
    │   ├── 地图相机飞到目标节点位置
    │   ├── 节点脉冲动画高亮
    │   └── 自动展开侧边详情
    │
    ├── 用户打开"影响模拟"
    │   ├── 选择要修改的类
    │   ├── 调用 /api/analysis/{projectId}/impact-simulate
    │   ├── SSE 流式返回影响路径
    │   └── 地图上波纹动画展示影响范围
    │
    └── 用户拖动时间轴
        ├── 选择历史 commit
        ├── 重新计算该 commit 对应的依赖图
        └── 地图平滑过渡到历史状态
```

---

## 9. API 接口设计

### 9.1 RESTful API 规范

```
Base URL: /api/v1

通用响应格式:
{
  "code": 200,           // 业务码
  "message": "success",  // 提示信息
  "data": {},            // 响应数据
  "timestamp": 1234567890
}

分页响应:
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [],
    "total": 100,
    "page": 1,
    "size": 20
  }
}
```

### 9.2 接口清单

#### 9.2.1 用户认证

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/register` | 注册 |
| POST | `/api/v1/auth/login` | 登录，返回 JWT |
| POST | `/api/v1/auth/logout` | 登出（Redis 中失效 token） |
| GET  | `/api/v1/auth/me` | 获取当前用户信息 |

#### 9.2.2 项目管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST   | `/api/v1/projects` | 创建项目 |
| GET    | `/api/v1/projects` | 项目列表（分页、搜索） |
| GET    | `/api/v1/projects/{id}` | 项目详情 |
| PUT    | `/api/v1/projects/{id}` | 更新项目 |
| DELETE | `/api/v1/projects/{id}` | 删除项目 |
| POST   | `/api/v1/projects/{id}/members` | 添加成员 |
| DELETE | `/api/v1/projects/{id}/members/{userId}` | 移除成员 |

#### 9.2.3 扫描与分析

| 方法 | 路径 | 说明 |
|------|------|------|
| POST  | `/api/v1/projects/{id}/scan` | 触发全量扫描 |
| GET   | `/api/v1/projects/{id}/scans` | 扫描历史列表 |
| GET   | `/api/v1/projects/{id}/scans/{scanId}` | 扫描详情 |
| GET   | `/api/v1/projects/{id}/scans/{scanId}/progress` | ★ SSE 流式分析进度 |
| POST  | `/api/v1/projects/{id}/scan-increment` | 增量扫描（只扫变更文件） |

#### 9.2.4 地图数据

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v1/projects/{id}/map-data` | 获取完整地图 JSON（含缓存） |
| GET | `/api/v1/projects/{id}/map-data?commit={sha}` | 获取指定 commit 的地图（历史回放） |
| GET | `/api/v1/projects/{id}/classes/{fqn}` | 获取单个类详情 |
| GET | `/api/v1/projects/{id}/classes/search?q={keyword}` | 语义搜索类 |

#### 9.2.5 AI 分析

| 方法 | 路径 | 说明 |
|------|------|------|
| POST  | `/api/v1/projects/{id}/ai/story` | 生成/刷新架构叙事 |
| GET   | `/api/v1/projects/{id}/ai/story` | 获取最新架构叙事 |
| POST  | `/api/v1/projects/{id}/ai/impact-simulate` | ★ 变更影响模拟 |
| POST  | `/api/v1/projects/{id}/ai/context-qa` | 上下文问答（限定范围） |
| GET   | `/api/v1/projects/{id}/ai/insights` | AI 洞察列表 |
| GET   | `/api/v1/projects/{id}/ai/health-score` | 架构健康度评分 |

#### 9.2.6 宪法规则

| 方法 | 路径 | 说明 |
|------|------|------|
| GET    | `/api/v1/projects/{id}/rules` | 规则列表 |
| POST   | `/api/v1/projects/{id}/rules` | 创建自定义规则 |
| PUT    | `/api/v1/projects/{id}/rules/{ruleId}` | 更新规则 |
| DELETE | `/api/v1/projects/{id}/rules/{ruleId}` | 删除规则 |
| PUT    | `/api/v1/projects/{id}/rules/{ruleId}/toggle` | 启用/禁用 |
| GET    | `/api/v1/projects/{id}/violations` | 违规列表 |
| PUT    | `/api/v1/projects/{id}/violations/{vid}/resolve` | 标记违规已修复 |
| GET    | `/api/v1/rules/templates` | 获取内置规则模板 |

#### 9.2.7 视图管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/projects/{id}/views` | 保存当前地图视图 |
| GET  | `/api/v1/projects/{id}/views` | 视图列表 |
| GET  | `/api/v1/projects/{id}/views/{viewId}` | 加载视图 |
| DELETE | `/api/v1/projects/{id}/views/{viewId}` | 删除视图 |

#### 9.2.8 报告导出

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/projects/{id}/reports` | 生成报告 |
| GET  | `/api/v1/projects/{id}/reports` | 报告列表 |
| GET  | `/api/v1/projects/{id}/reports/{reportId}/download` | ★ 下载 PDF/HTML 报告 |

### 9.3 关键接口详细设计

#### 触发扫描 (POST /projects/{id}/scan)

```
Request:
{
  "branch": "main",           // 可选，默认 main
  "fullScan": true,           // true=全量 false=增量
  "options": {
    "aiStory": true,          // 是否生成架构叙事
    "constitutionCheck": true, // 是否执行宪法检查
    "language": "Java"        // 指定语言
  }
}

Response (HTTP 202 Accepted):
{
  "code": 200,
  "data": {
    "scanId": 12345,
    "status": "PENDING",
    "progressUrl": "/api/v1/projects/1/scans/12345/progress"
  }
}
```

#### SSE 分析进度 (GET /projects/{id}/scans/{scanId}/progress)

```
Response (text/event-stream):

event: stage
data: {"stage":"PARSING","status":"RUNNING","message":"正在解析源代码...","pct":10}

event: progress
data: {"stage":"DEPENDENCY","status":"RUNNING","message":"正在构建依赖图...","pct":30,"detail":{"parsed":128,"total":128}}

event: progress
data: {"stage":"AI_ARCHITECTURE","status":"RUNNING","message":"AI 正在识别架构模式...","pct":50}

event: progress
data: {"stage":"AI_CONSTITUTION","status":"RUNNING","message":"AI 正在执行宪法规则检查...","pct":70}

event: progress
data: {"stage":"AI_NARRATIVE","status":"RUNNING","message":"AI 正在生成架构叙事...","pct":85}

event: insight
data: {"type":"ANTI_PATTERN","title":"发现循环依赖","severity":"BLOCKER","class":"com.example.Foo"}

event: complete
data: {"scanId":12345,"healthScore":72.5,"totalViolations":8,"durationMs":45200}
```

#### 变更影响模拟 (POST /projects/{id}/ai/impact-simulate)

```
Request:
{
  "targetClass": "com.example.service.OrderService",
  "changeType": "MODIFY_METHOD",     // MODIFY_METHOD / ADD_FIELD / REMOVE_CLASS / CHANGE_SIGNATURE
  "targetMethod": "createOrder",     // 可选
  "description": "修改 createOrder 方法的参数列表，增加 discountCode 参数"
}

Response (SSE 流式):
event: impact_path
data: {"path":["OrderService","OrderController","OrderApiClient"],"depth":3,"risk":"HIGH","reason":"Controller 直接暴露了此方法"}
```

---

## 10. 安全设计

### 10.1 认证流程

```
1. 用户 POST /auth/login {username, password}
2. 服务端验证 BCrypt(password, stored_hash)
3. 生成 JWT: {sub: userId, role: "DEVELOPER", exp: now+24h}
4. JWT 存入 Redis: session:{token} → {userId, role, issuedAt}
5. 返回 {token, user}

后续请求:
   Authorization: Bearer <token>
   → JwtAuthFilter 解析
   → Redis 校验 token 有效性（支持主动失效）
   → 注入 SecurityContext
```

### 10.2 RBAC 权限矩阵

| 操作 | ADMIN | ARCHITECT | DEVELOPER | VIEWER |
|------|-------|-----------|-----------|--------|
| 创建项目 | ✅ | ✅ | ✅ | ❌ |
| 查看项目 | ✅ | ✅(自己的/被邀请的) | ✅(自己的/被邀请的) | ✅(被邀请的) |
| 触发扫描 | ✅ | ✅ | ✅(自己的项目) | ❌ |
| 查看分析结果 | ✅ | ✅ | ✅ | ✅ |
| 管理宪法规则 | ✅ | ✅(项目OWNER) | ❌ | ❌ |
| 管理用户 | ✅ | ❌ | ❌ | ❌ |
| 查看审计日志 | ✅ | ❌ | ❌ | ❌ |
| 导出报告 | ✅ | ✅ | ✅ | ❌ |

### 10.3 安全措施清单

| 类别 | 措施 | 实现 |
|------|------|------|
| **认证** | JWT + Redis Session | `JwtAuthFilter` + `TokenService` |
| **密码** | BCrypt 加密存储 | `BCryptPasswordEncoder` |
| **授权** | RBAC + 方法级注解 | `@PreAuthorize("hasRole('ARCHITECT')")` |
| **CSRF** | 前后端分离 + Token 方式 | 不依赖 Cookie，天然防 CSRF |
| **XSS** | 输入转义 + 输出编码 | Spring 全局 `HtmlUtils.htmlEscape()` |
| **SQL 注入** | MyBatis 参数化查询 | `#{param}` 而非 `${param}` |
| **文件上传** | 类型白名单 + 大小限制 | `MultipartFile` 校验 |
| **AI 注入** | AI 输入过滤 + prompt 隔离 | 用户输入与系统 prompt 分离 |
| **速率限制** | Redis 令牌桶 | `RateLimitInterceptor` |
| **审计日志** | AOP 切面记录 | `@AuditLog` 自定义注解 |
| **HTTPS** | Nginx SSL 终止 | 生产环境 |
| **CORS** | 白名单域名 | `WebMvcConfigurer.addCorsMappings()` |
| **敏感信息** | 配置文件加密 | `jasypt-spring-boot` 加密 `application.yml` 中的 key |
| **依赖安全** | OWASP Dependency Check | Maven 插件 `dependency-check-maven` |

### 10.4 AI Prompt 注入防护

由于本项目深度使用 AI API，prompt 注入是特有风险：

```java
public class AiPromptSanitizer {

    /**
     * AI 输入净化：分离系统指令与用户输入
     */
    public AiRequest sanitize(AiRequest request) {
        // 1. 系统 prompt 不可由用户修改（代码注入防线）
        // 2. 用户输入包装在 <user_input> 标签内，与系统指令隔离
        // 3. 限制用户输入长度（防止 token 浪费攻击）
        // 4. 过滤已知注入模式

        String userInput = request.getMetadata().get("userInput");
        if (userInput != null) {
            userInput = truncate(userInput, 2000);           // 限长
            userInput = removeControlChars(userInput);       // 去掉控制字符
            userInput = sanitizeInjectionPatterns(userInput); // 过滤 "ignore previous instructions" 等
            request.getMetadata().put("userInput", userInput);
        }
        return request;
    }

    private String sanitizeInjectionPatterns(String input) {
        String[] patterns = {
            "(?i)ignore (all |)(previous|above|prior) instructions?",
            "(?i)system prompt",
            "(?i)you are now",
            "(?i)new instructions?",
        };
        for (String pattern : patterns) {
            input = input.replaceAll(pattern, "[FILTERED]");
        }
        return input;
    }
}
```

---

## 11. 测试策略

### 11.1 测试金字塔

```
            ╱─────╲
           ╱  E2E  ╲            ← Selenium / Playwright（5%）
          ╱─────────╲
         ╱ 集成测试   ╲          ← Spring Boot Test + Testcontainers（20%）
        ╱─────────────╲
       ╱   单元测试     ╲        ← JUnit 5 + Mockito（75%）
      ╱─────────────────╲
```

### 11.2 测试清单

#### 单元测试 (JUnit 5 + Mockito)

| 模块 | 测试内容 | 覆盖率要求 |
|------|---------|-----------|
| **Engine 层** | 所有核心算法 | ≥ 90% |
| `JavaParserImpl` | AST 解析正确性 | ≥ 95% |
| `DependencyGraphBuilder` | 依赖图构建、循环检测 | ≥ 95% |
| `RuleEvaluator` | 规则 DSL 解析、规则评估 | ≥ 90% |
| `PromptTemplate` | 模板渲染、变量替换 | ≥ 90% |
| `AiPromptSanitizer` | 注入过滤 | ≥ 95% |
| **Service 层** | 业务逻辑 | ≥ 80% |
| `IngestService` | 代码摄取流程 | ≥ 80% |
| `AiPipelineService` | AI 管线编排（mock AI 响应） | ≥ 85% |
| `ConstitutionService` | 宪法规则 CRUD、执行 | ≥ 80% |
| **Controller 层** | 参数校验、返回格式 | ≥ 70% |

#### 集成测试 (Spring Boot Test)

| 测试场景 | 工具 |
|---------|------|
| MySQL CRUD 操作正确性 | `@SpringBootTest` + `@Transactional` |
| Neo4j 图写入与查询 | Testcontainers Neo4j |
| Redis 缓存读写 | Testcontainers Redis |
| JWT 认证流程完整链路 | `MockMvc` + `@WithMockUser` |
| API 端到端（不调 AI） | `MockMvc` + mock AI Client |
| 文件上传（ZIP 项目导入） | `MockMvc` + `MockMultipartFile` |

#### E2E 测试（Phase 2+）

| 测试场景 | 工具 |
|---------|------|
| 核心用户闭环：注册→创建项目→扫描→查看地图→查看叙事 | Playwright |
| 地图交互：缩放、拖拽、节点点击 | Playwright |
| 宪法规则：创建规则→触发扫描→查看违规 | Playwright |

### 11.3 AI 测试的特殊处理

由于 AI API 调用不可预测，采用以下策略：

```java
// 策略1: Mock AI Client（单元/集成测试）
@MockBean
private AiClient aiClient;

@Test
void testAiPipeline() {
    when(aiClient.chat(any()))
        .thenReturn(new AiResponse("预定义的架构叙事内容", 500, 1200, List.of()));
    // 验证管线编排逻辑正确
}

// 策略2: AI 输出验证器（集成测试中调用真实 API）
@Test
@Tag("ai-integration")  // 标记为慢速测试，CI 中可选执行
void testArchitectureStoryGeneration() {
    AiResponse response = claudeClient.chat(buildStoryPrompt(testProject));
    // 验证输出结构（但不断言具体内容）
    assertThat(response.getContent()).isNotBlank();
    assertThat(response.getTokensUsed()).isPositive();
    // 验证每个 source 引用确实存在
    for (AiSource source : response.getSources()) {
        assertThat(classExists(source.getClassName())).isTrue();
    }
}

// 策略3: 去幻觉验证器
public class HallucinationChecker {
    public boolean validate(AiResponse response, ProjectContext context) {
        for (AiSource source : response.getSources()) {
            if (!context.containsClass(source.getClassName())) {
                log.warn("AI hallucinated class: {}", source.getClassName());
                return false;
            }
        }
        return true;
    }
}
```

### 11.4 测试运行命令

```bash
# 单元测试
mvn test

# 集成测试（需要 Docker）
mvn verify -P integration-test

# 跳过 AI 集成测试
mvn test -DexcludeTags=ai-integration

# 生成覆盖率报告
mvn jacoco:report
# 报告位置: target/site/jacoco/index.html
```

---

## 12. 扩展点设计

这是项目的核心竞争力之一。以下所有扩展点都通过 **Java SPI (ServiceLoader)** 或 **接口注入** 实现，保证不修改核心代码即可扩展。

### 12.1 扩展点全景图

```
┌─────────────────────────────────────────────────────────────────┐
│                     CodeAtlas Extension System                   │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ Language    │  │ AI Model    │  │ Rule        │             │
│  │ Parser SPI  │  │ Client SPI  │  │ Extension   │             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         │               │               │                       │
│  ┌──────┴──────┐  ┌─────┴──────┐  ┌─────┴──────┐              │
│  │ JavaParser  │  │ Claude     │  │ Built-in   │              │
│  │ PythonParser│  │ DeepSeek   │  │ Custom     │              │
│  │ GoParser    │  │ OpenAI     │  │ Plugin     │              │
│  │ ...         │  │ ...        │  │ ...        │              │
│  └─────────────┘  └────────────┘  └────────────┘              │
│                                                                 │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐             │
│  │ Source      │  │ Visualization│  │ Report      │             │
│  │ Control SPI │  │ Plugin SPI   │  │ Exporter SPI│             │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘             │
│         │               │               │                       │
│  ┌──────┴──────┐  ┌─────┴──────┐  ┌─────┴──────┐              │
│  │ Git (JGit) │  │ D3.js 2D   │  │ PDF Export │              │
│  │ GitHub API │  │ Three.js 3D│  │ HTML Export│              │
│  │ SVN        │  │ Custom     │  │ Markdown   │              │
│  │ ...        │  │ ...        │  │ ...        │              │
│  └─────────────┘  └────────────┘  └────────────┘              │
└─────────────────────────────────────────────────────────────────┘
```

### 12.2 SPI 接口定义

#### 12.2.1 语言解析器扩展 (LanguageParser)

```java
// 文件位置: codeatlas-engine/src/main/java/com/codeatlas/engine/parser/spi/LanguageParser.java

package com.codeatlas.engine.parser.spi;

import java.util.List;

/**
 * 代码解析器 SPI 接口。
 *
 * 扩展方式: 实现此接口，在 META-INF/services/ 注册即可。
 * jar 放入 classpath 即生效，无需改核心代码。
 */
public interface LanguageParser {

    /** 返回支持的语言标识: "Java" / "Python" / "Go" / ... */
    String getLanguage();

    /** 返回支持的文件扩展名: [".java"] / [".py"] / [".go"] / ... */
    List<String> getSupportedExtensions();

    /** 解析单个源文件，返回结构化摘要列表 */
    List<ClassSummary> parseFile(Path sourceFile);

    /** 解析整个目录 */
    List<ClassSummary> parseDirectory(Path sourceDir);

    /** 是否支持增量解析（只解析变更文件） */
    boolean supportsIncremental();

    /** 获取解析器版本 */
    String getVersion();
}

// 注册方式: META-INF/services/com.codeatlas.engine.parser.spi.LanguageParser
// 内容:
// com.codeatlas.engine.parser.java.JavaParserImpl
// com.example.python.PythonParserImpl        ← 第三方扩展
```

#### 12.2.2 AI 模型客户端扩展 (AiClient)

```java
// 文件位置: codeatlas-engine/src/main/java/com/codeatlas/engine/ai/spi/AiClient.java

package com.codeatlas.engine.ai.spi;

/**
 * AI 模型客户端 SPI 接口。
 *
 * 扩展方式: 实现此接口即可接入新的 AI 模型。
 */
public interface AiClient {

    /** 模型标识: "claude-3.5-sonnet" / "gpt-4" / "deepseek-chat" */
    String getModelIdentifier();

    /** 非流式调用 */
    AiResponse chat(AiRequest request) throws AiException;

    /** 流式调用（按 token 回调） */
    void chatStream(AiRequest request, StreamCallback callback) throws AiException;

    /** 获取该模型支持的最大 context 长度（用于 prompt 截断） */
    int getMaxContextTokens();

    /** 健康检查 */
    boolean healthCheck();
}

/**
 * 流式回调接口
 */
public interface StreamCallback {
    void onToken(String token);           // 每个 token 到达时调用
    void onComplete(AiResponse response); // 流结束时调用
    void onError(Throwable error);        // 异常时调用
}
```

#### 12.2.3 报告导出器扩展 (ReportExporter)

```java
// 文件位置: codeatlas-engine/src/main/java/com/codeatlas/engine/report/spi/ReportExporter.java

public interface ReportExporter {

    /** 导出格式: "PDF" / "HTML" / "Markdown" */
    String getFormat();

    /** 生成文件名后缀 */
    String getFileExtension();

    /** 生成报告 */
    byte[] export(ReportContext context) throws ExportException;

    /** 验证报告上下文是否满足格式要求 */
    boolean supports(ReportContext context);
}

// 扩展示例: 实现 Word 格式导出
// public class WordReportExporter implements ReportExporter { ... }
```

#### 12.2.4 源码控制扩展 (SourceControlProvider)

```java
// 文件位置: codeatlas-engine/src/main/java/com/codeatlas/engine/git/spi/SourceControlProvider.java

public interface SourceControlProvider {

    /** 提供者类型: "GIT" / "SVN" / "GITHUB" / "GITLAB" */
    String getProviderType();

    /** 克隆/拉取源码到本地 */
    Path checkout(SourceConfig config) throws SourceException;

    /** 获取提交历史（用于时间轴演进回放） */
    List<CommitInfo> getHistory(Path localPath, int limit);

    /** 获取两个 commit 之间的变更文件列表 */
    List<Path> getChangedFiles(Path localPath, String fromCommit, String toCommit);
}
```

### 12.3 扩展加载机制

```java
// ExtensionLoader — 统一扩展加载器
public class ExtensionLoader {

    private static final Map<Class<?>, List<Object>> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public static <T> List<T> loadExtensions(Class<T> spiInterface) {
        return (List<T>) cache.computeIfAbsent(spiInterface, clz -> {
            List<T> extensions = new ArrayList<>();
            // 1. 加载 SPI 注册的实现（META-INF/services/）
            ServiceLoader<T> loader = ServiceLoader.load(spiInterface);
            for (T impl : loader) {
                extensions.add(impl);
            }
            // 2. 加载 Spring 容器中的实现（如果可用）
            // 3. 加载 plugins/ 目录下的外部 jar
            loadExternalPlugins(spiInterface, extensions);
            return extensions;
        });
    }

    private static <T> void loadExternalPlugins(Class<T> spi, List<T> list) {
        // 扫描 plugins/ 目录中的 jar，用 URLClassLoader 加载
        Path pluginDir = Paths.get("plugins");
        if (!Files.exists(pluginDir)) return;
        // ... 遍历 jar，实例化后 add
    }
}
```

### 12.4 扩展开发指南（给第三方开发者）

```markdown
# 如何为 CodeAtlas 开发一个 Python 语言解析器

1. 创建 Maven 项目，添加依赖:
   <dependency>
     <groupId>com.codeatlas</groupId>
     <artifactId>codeatlas-engine</artifactId>
     <version>1.0.0</version>
     <scope>provided</scope>
   </dependency>

2. 实现 LanguageParser 接口:
   public class PythonParser implements LanguageParser {
       @Override
       public String getLanguage() { return "Python"; }
       @Override
       public List<String> getSupportedExtensions() { return Arrays.asList(".py"); }
       @Override
       public List<ClassSummary> parseFile(Path sourceFile) { ... }
       // ...
   }

3. 在 META-INF/services/ 注册:
   文件名: com.codeatlas.engine.parser.spi.LanguageParser
   内容: com.yourcompany.python.PythonParser

4. 打包为 jar，放入 CodeAtlas 的 plugins/ 目录
5. 重启服务，Python 项目即可被解析
```

---

## 13. 部署架构

### 13.1 开发环境

```
开发者本地 Windows/macOS:
┌─────────────────────────────────────────────────────┐
│  codeatlas-web (Vite dev server)  :5173             │
│  codeatlas-server (Spring Boot)   :8080             │
│  MySQL 5.7 (本地)                  :3306             │
│  Neo4j Community (本地)           :7474/:7687       │
│  Redis (本地)                     :6379             │
└─────────────────────────────────────────────────────┘
```

### 13.2 测试环境 (Docker Compose)

```yaml
# docker-compose.yml
version: '3.8'
services:
  mysql:
    image: mysql:5.7
    environment:
      MYSQL_ROOT_PASSWORD: codeatlas
      MYSQL_DATABASE: codeatlas
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
      - ./sql/init.sql:/docker-entrypoint-initdb.d/init.sql

  neo4j:
    image: neo4j:5-community
    environment:
      NEO4J_AUTH: neo4j/codeatlas
    ports:
      - "7474:7474"
      - "7687:7687"
    volumes:
      - neo4j_data:/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  server:
    build: ./codeatlas-server
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: docker
    depends_on:
      - mysql
      - neo4j
      - redis

volumes:
  mysql_data:
  neo4j_data:
```

### 13.3 生产环境

```
                    Internet
                       │
                ┌──────▼──────┐
                │   Nginx      │  SSL 终止 + 反向代理 + 静态资源
                │   :443       │
                └──────┬──────┘
                       │
          ┌────────────┼────────────┐
          │            │            │
    ┌─────▼─────┐ ┌───▼────┐ ┌────▼─────┐
    │ 前端静态   │ │ API   │ │ WebSocket│
    │ /         │ │ /api  │ │ /ws      │
    │ :8080     │ │ :8080 │ │ :8080    │
    └───────────┘ └───┬────┘ └──────────┘
                      │
          ┌───────────┼───────────┐
          │           │           │
    ┌─────▼─────┐ ┌──▼────┐ ┌───▼─────┐
    │  MySQL    │ │ Neo4j │ │  Redis  │
    │  :3306    │ │ :7687 │ │  :6379  │
    └───────────┘ └───────┘ └─────────┘
```

---

## 14. 开发路线图

### Phase 1: MVP（预计 8 周）

| 周次 | 任务 | 产出 |
|------|------|------|
| W1 | 项目初始化：多模块 Maven 项目、Spring Boot 骨架、Vue 3 脚手架 | 项目能跑 |
| W2 | MySQL 表创建 + MyBatis 配置 + 用户系统（注册/登录/JWT） | 能注册登录 |
| W3 | JGit 集成 + JavaParser 集成 + 代码摄取流程 | 能拉代码、解析 AST |
| W4 | Neo4j 集成 + 依赖图构建 + 循环检测 | 有依赖图数据 |
| W5 | D3.js 力导向图渲染 + 地图基础交互（缩放、拖拽、点击） | 2D 地图可见 |
| W6 | AiClient 接口 + Claude Client 实现 + Prompt 模板系统 | 能调 AI |
| W7 | AI 分析管线编排 + SSE 进度推送 + 架构叙事生成 | 全流程跑通 |
| W8 | 架构健康度评分 + 前端仪表盘 + Bug 修复 + 文档 | MVP 完成 |

### Phase 2: 核心体验增强（预计 6 周）

| 周次 | 任务 |
|------|------|
| W9-10 | 架构反模式检测（AI 分析）+ Constitution Engine 第一版 |
| W11-12 | 3D 拓扑图升级（Three.js）+ 多层级 LOD 缩放 |
| W13-14 | 变更影响地震模拟 + AI 上下文面板 |

### Phase 3: 企业化（预计 4 周）

| 周次 | 任务 |
|------|------|
| W15-16 | 多语言扩展（Python → SPI 验证）+ 时间轴演进回放 |
| W17-18 | PDF/HTML 报告导出 + RBAC 权限完善 + 审计日志 + 压测优化 |

---

### 参考：配置文件示例

```yaml
# application.yml
spring:
  profiles:
    active: dev
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/codeatlas?useUnicode=true&characterEncoding=utf8mb4&useSSL=false
    username: root
    password: ENC(encrypted_password)  # jasypt 加密
    driver-class-name: com.mysql.jdbc.Driver
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      idle-timeout: 300000

  neo4j:
    uri: bolt://127.0.0.1:7687
    authentication:
      username: neo4j
      password: codeatlas

  redis:
    host: 127.0.0.1
    port: 6379
    timeout: 3000ms
    lettuce:
      pool:
        max-active: 8
        max-idle: 8
        min-idle: 0

# AI 配置
codeatlas:
  ai:
    primary: claude
    clients:
      claude:
        api-key: ${CLAUDE_API_KEY}          # 环境变量注入
        base-url: https://api.anthropic.com
        model: claude-sonnet-4-6
        max-tokens: 4096
        temperature: 0.3
      deepseek:
        api-key: ${DEEPSEEK_API_KEY}
        base-url: https://api.deepseek.com
        model: deepseek-chat
        max-tokens: 4096
        temperature: 0.3

  analysis:
    max-file-size: 10485760     # 单个文件最大 10MB
    max-project-size: 524288000 # 项目总大小 500MB
    timeout-minutes: 30          # 单次分析超时时间
    max-dependency-depth: 10    # 依赖链最大深度

  security:
    jwt:
      secret: ${JWT_SECRET}
      expiration-hours: 24
    rate-limit:
      enabled: true
      default-rps: 10  # 默认每秒请求数
```

### 参考：关键 Maven 依赖

```xml
<!-- codeatlas-engine/pom.xml (核心引擎，不依赖 Spring) -->
<dependencies>
    <!-- Git 操作 -->
    <dependency>
        <groupId>org.eclipse.jgit</groupId>
        <artifactId>org.eclipse.jgit</artifactId>
        <version>5.13.3.202401111512-r</version>
    </dependency>
    <!-- Java 代码解析 -->
    <dependency>
        <groupId>com.github.javaparser</groupId>
        <artifactId>javaparser-core</artifactId>
        <version>3.25.10</version>
    </dependency>
    <!-- HTTP 客户端（AI API 调用） -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>
    <!-- JSON -->
    <dependency>
        <groupId>com.fasterxml.jackson.core</groupId>
        <artifactId>jackson-databind</artifactId>
        <version>2.17.2</version>
    </dependency>
    <!-- 单元测试 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.10.3</version>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.mockito</groupId>
        <artifactId>mockito-core</artifactId>
        <version>5.12.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 参考：核心类图（概要）

```
┌─────────────────────────────────────────────────────────────┐
│  Engine 层核心类（无 Spring 依赖）                            │
│                                                             │
│  LanguageParser (SPI interface)                             │
│    └── JavaParserImpl                                       │
│                                                             │
│  DependencyGraphBuilder                                     │
│    ├── addClass(ClassSummary)                               │
│    ├── buildGraph() → Graph<ClassNode, DependencyEdge>      │
│    └── detectCycles() → List<Cycle>                         │
│                                                             │
│  AiClient (SPI interface)                                   │
│    ├── ClaudeClient (OkHttp → Anthropic API)                │
│    └── DeepSeekClient (OkHttp → DeepSeek API)               │
│                                                             │
│  PromptTemplateEngine                                       │
│    ├── loadTemplate(String name)                            │
│    └── render(Map<String, Object> variables)                 │
│                                                             │
│  RuleEvaluator                                              │
│    ├── evaluate(Graph, RuleDefinition) → Violation          │
│    └── evaluateAll(Graph, List<Rule>) → List<Violation>     │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Service 层核心类（Spring Bean）                             │
│                                                             │
│  AiPipelineOrchestrator                                     │
│    ├── List<PipelineStage> stages                           │
│    └── execute(ProjectContext) → PipelineResult             │
│                                                             │
│  IngestService                                              │
│    ├── ingestFromGit(String url, String branch)             │
│    ├── ingestFromZip(MultipartFile file)                    │
│    └── ingestFromLocal(Path path)                           │
│                                                             │
│  ConstitutionService                                        │
│    ├── listRules(Long projectId)                            │
│    ├── createRule(RuleDTO dto)                              │
│    ├── executeRules(Long scanId) → List<Violation>          │
│    └── getViolations(Long projectId, Filter filter)         │
│                                                             │
│  MapDataService                                             │
│    ├── getMapData(Long projectId) → MapData                 │
│    └── getHistoricalMap(Long projectId, String commit)      │
│                                                             │
│  ImpactAnalysisService ← SSE 流式                           │
│    └── simulate(ImpactRequest req, SseEmitter emitter)      │
└─────────────────────────────────────────────────────────────┘
```

### 参考：前端关键技术点

```
地图渲染核心:
┌──────────────────────────────────────────┐
│  D3.js forceSimulation                    │
│  │  nodes: ClassNode[]                    │
│  │  links: DependencyLink[]               │
│  │  forces: [                             │
│  │    forceLink(),      // 依赖约束       │
│  │    forceManyBody(),  // 排斥力         │
│  │    forceCenter(),    // 居中           │
│  │    forceCollide(),   // 防重叠         │
│  │    forceCluster()    // 按模块聚类     │
│  │  ]                                     │
│  └── tick() → 更新 SVG/Canvas             │
│                                           │
│  3D 升级 (Phase 2):                       │
│  ┌──────────────────────────────────────┐ │
│  │  Three.js ForceGraph3D               │ │
│  │  │  THREE.CSS2DRenderer (标签)       │ │
│  │  │  OrbitControls (旋转/平移/缩放)   │ │
│  │  │  Raycaster (点击检测)             │ │
│  │  │  Post-processing (发光效果)       │ │
│  │  └── VR/AR 预留                      │ │
│  └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘

性能策略:
- 虚拟化: 10000+ 节点时只渲染视口内的节点
- Web Worker: 力导向布局计算在 Worker 线程
- 节流: 缩放/拖拽事件 60fps 节流
- LOD: 不同缩放级别使用不同粒度的数据
- 增量更新: 只更新变更的节点/边，不全量重绘
```

---

> **文档状态**: ✅ 完整 | **下一步**: 项目骨架搭建 + 数据库建表 + 用户登录注册  
> **文档维护**: 任何架构变更必须同步更新本文档

---

## 15. 日志与监控

线上项目出问题的最短恢复路径：**监控发现 → 日志定位 → 快速修复**。本章覆盖全链路可观测性。

### 15.1 日志框架与规范

```
技术选型: SLF4J + Logback（Spring Boot 默认）

日志级别使用规范:
  ERROR   — 需要人工介入（DB 连接失败、AI API 反复失败、OOM）
  WARN    — 可自动恢复的异常（单次 AI 调用超时重试、文件解析跳过非关键文件）
  INFO    — 业务关键节点（扫描开始/结束、AI 分析阶段切换、用户登录/登出）
  DEBUG   — 开发调试（SQL 参数、AI prompt 全文、依赖图构建细节）
  TRACE   — 最细粒度（AST 解析每个文件的详情，默认关闭）
```

**日志配置核心片段** (`logback-spring.xml`):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <!-- 控制台输出（开发环境彩色） -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 按天滚动的文件日志 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/codeatlas.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/codeatlas.%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxHistory>30</maxHistory>
            <timeBasedFileNamingAndTriggeringPolicy
                class="ch.qos.logback.core.rolling.SizeAndTimeBasedFNATP">
                <maxFileSize>100MB</maxFileSize>
            </timeBasedFileNamingAndTriggeringPolicy>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %X{traceId} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 错误日志单独文件 -->
    <appender name="ERROR_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <file>logs/codeatlas-error.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/codeatlas-error.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>90</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %X{traceId} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- AI 调用专项日志（用于 Token 用量审计） -->
    <appender name="AI_AUDIT" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/ai-audit.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/ai-audit.%d{yyyy-MM-dd}.log</fileNamePattern>
            <maxHistory>365</maxHistory>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 业务日志级别 -->
    <logger name="com.codeatlas" level="DEBUG" />
    <logger name="com.codeatlas.engine.ai" level="INFO" />
    <!-- AI 审计专用 logger -->
    <logger name="ai-audit" level="INFO" additivity="false">
        <appender-ref ref="AI_AUDIT" />
    </logger>

    <root level="INFO">
        <appender-ref ref="CONSOLE" />
        <appender-ref ref="FILE" />
        <appender-ref ref="ERROR_FILE" />
    </root>
</configuration>
```

### 15.2 请求链路追踪

```
方案: 使用 SLF4J MDC (Mapped Diagnostic Context) 植入 traceId

实现:
  1. 拦截器 TraceInterceptor 在请求入口生成 UUID traceId
  2. MDC.put("traceId", traceId)
  3. 在所有日志中自动携带 %X{traceId}
  4. 响应头返回 X-Trace-Id，前端可展示在错误页面
  5. 异步任务（扫描/AI分析）手动传递 traceId

核心代码:
  public class TraceInterceptor implements HandlerInterceptor {
      @Override
      public boolean preHandle(HttpServletRequest request,
                               HttpServletResponse response,
                               Object handler) {
          String traceId = request.getHeader("X-Trace-Id");
          if (traceId == null || traceId.isEmpty()) {
              traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
          }
          MDC.put("traceId", traceId);
          response.setHeader("X-Trace-Id", traceId);
          return true;
      }

      @Override
      public void afterCompletion(...) {
          MDC.clear();  // 防内存泄漏
      }
  }
```

### 15.3 指标监控 (Micrometer + Actuator)

```yaml
# 暴露 Actuator 指标端点
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  metrics:
    export:
      prometheus:
        enabled: true
    tags:
      application: codeatlas
```

**自定义业务指标**（代码中埋点）:

```java
// 关键业务指标（接入 Micrometer MeterRegistry）
@Component
public class CodeAtlasMetrics {

    private final MeterRegistry registry;

    // 扫描计数器 — 按项目、状态分标签
    private final Counter scanTotal;
    // 扫描耗时直方图
    private final Timer scanDuration;
    // AI 调用计数器 — 按模型、阶段分标签
    private final Counter aiCallTotal;
    // AI Token 消耗仪表盘
    private final AtomicLong aiTokensUsed;

    public void recordScan(String projectName, String status, long durationMs) {
        scanTotal.increment();
        Timer.Sample sample = Timer.start(registry);
        sample.stop(Timer.builder("codeatlas.scan.duration")
            .tag("project", projectName)
            .tag("status", status)
            .register(registry));
    }

    public void recordAiCall(String model, String stage, long tokens) {
        Counter.builder("codeatlas.ai.calls")
            .tag("model", model)
            .tag("stage", stage)
            .register(registry)
            .increment();
        aiTokensUsed.addAndGet(tokens);
    }
}
```

### 15.4 前端监控

```
方案: 自建轻量级前端错误收集（Phase 1），后续可接 Sentry

核心收集点:
  1. JS 运行时错误      window.onerror
  2. Promise 未捕获异常  window.onunhandledrejection
  3. API 请求错误       Axios 拦截器 (status >= 400)
  4. 地图渲染性能       PerformanceObserver (FPS)
  5. 用户操作路径       路由切换面包屑

上报接口: POST /api/v1/telemetry/frontend-error（匿名，不做身份关联）
采样率: 100%（前端错误全量），10%（性能数据）

前端上报核心代码:
  // utils/errorReporter.js
  window.addEventListener('error', (event) => {
      report({
          type: 'js_error',
          message: event.message,
          stack: event.error?.stack?.substring(0, 1000), // 截断防过大
          url: window.location.href,
          timestamp: Date.now()
      });
  });

  // Axios 拦截器
  axios.interceptors.response.use(
      response => response,
      error => {
          report({
              type: 'api_error',
              url: error.config?.url,
              status: error.response?.status,
              traceId: error.response?.headers['x-trace-id'],
              timestamp: Date.now()
          });
          return Promise.reject(error);
      }
  );
```

### 15.5 告警策略

```
告警分级:
  P0 (立即处理):
    - 应用进程 down → 企微/钉钉/邮件
    - MySQL/Neo4j 连不上 → 企微/钉钉/邮件
    - 错误率 >10% 持续 1min → 企微/钉钉

  P1 (15min 内处理):
    - AI API 连续失败 5 次 → 企微/钉钉
    - 扫描任务失败率 >20% → 企微/钉钉
    - 磁盘使用率 >85% → 企微/钉钉

  P2 (1h 内处理):
    - AI Token 日消耗超过预算80% → 邮件
    - 单次扫描耗时超过 30min → 邮件
    - 并发扫描数达到上限 → 邮件

告警通道（按需接入）:
  1. 企业微信/钉钉 Webhook（最轻量，Phase 1）
  2. 邮件（SMTP，Phase 1）
  3. Prometheus AlertManager（Phase 2）
  4. Grafana Dashboard（Phase 2）
```

### 15.6 AI Token 用量审计

```
审计日志格式 (ai-audit.log):
  每行一条 JSON:
  {
    "timestamp": "2026-07-22T10:30:00.123Z",
    "traceId": "a1b2c3d4e5f6",
    "model": "claude-sonnet-4-6",
    "stage": "ARCHITECTURE_STYLE",
    "projectId": 42,
    "userId": 7,
    "promptTokens": 3240,
    "completionTokens": 850,
    "totalTokens": 4090,
    "latencyMs": 4200,
    "cost": 0.06135
  }

月度汇总查询（MySQL VIEW 或定时任务）:
  SELECT
      DATE_FORMAT(timestamp, '%Y-%m') AS month,
      model,
      COUNT(*) AS call_count,
      SUM(totalTokens) AS total_tokens,
      SUM(cost) AS total_cost
  FROM ai_audit_log
  GROUP BY month, model;
```

---

## 16. 异常处理与错误码规范

### 16.1 统一错误码枚举

```java
public enum ErrorCode {

    // 通用错误
    SUCCESS(200, "成功"),
    BAD_REQUEST(400, "请求参数错误"),
    UNAUTHORIZED(401, "未登录或 Token 已过期"),
    FORBIDDEN(403, "无权限访问该资源"),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "资源冲突（如重复创建）"),
    INTERNAL_ERROR(500, "服务器内部错误"),
    SERVICE_UNAVAILABLE(503, "服务暂时不可用"),

    // 业务错误 (10001 - 19999)
    PROJECT_NOT_FOUND(10001, "项目不存在"),
    PROJECT_ACCESS_DENIED(10002, "无权访问该项目"),
    SCAN_ALREADY_RUNNING(10003, "该项目的扫描已在运行中"),
    SCAN_NOT_FOUND(10004, "扫描记录不存在"),
    SOURCE_CLONE_FAILED(10005, "源码仓库克隆失败"),
    SOURCE_UNSUPPORTED_LANG(10006, "不支持该编程语言"),
    PARSE_FAILED(10007, "代码解析失败"),
    GRAPH_BUILD_FAILED(10008, "依赖图构建失败"),
    RULE_NOT_FOUND(10009, "宪法规则不存在"),
    RULE_REFERENCED(10010, "规则被违规记录引用，不可删除"),
    REPORT_GENERATE_FAILED(10011, "报告生成失败"),

    // AI 相关错误 (20001 - 29999)
    AI_API_TIMEOUT(20001, "AI API 调用超时"),
    AI_API_RATE_LIMITED(20002, "AI API 频率限制"),
    AI_API_AUTH_FAILED(20003, "AI API 认证失败"),
    AI_API_INTERNAL_ERROR(20004, "AI API 内部错误"),
    AI_RESPONSE_PARSE_FAILED(20005, "AI 响应解析失败"),
    AI_CONTENT_FILTERED(20006, "AI 输出被内容过滤器拦截"),
    AI_MODEL_NOT_FOUND(20007, "模型不存在或未配置"),
    AI_HALLUCINATION_DETECTED(20008, "检测到 AI 幻觉，输出已被拦截"),
    AI_TOKEN_BUDGET_EXCEEDED(20009, "Token 月度预算已耗尽"),
    AI_CONTEXT_TOO_LARGE(20010, "项目过大，超出模型上下文限制"),

    // 文件相关 (30001 - 39999)
    FILE_TOO_LARGE(30001, "文件大小超过限制"),
    FILE_TYPE_NOT_ALLOWED(30002, "不支持的文件类型"),
    FILE_UPLOAD_FAILED(30003, "文件上传失败"),

    // 用户相关 (40001 - 49999)
    USERNAME_EXISTS(40001, "用户名已存在"),
    EMAIL_EXISTS(40002, "邮箱已注册"),
    PASSWORD_TOO_WEAK(40003, "密码强度不足（至少8位，含大小写+数字）"),
    USER_DISABLED(40004, "账户已被禁用"),
    LOGIN_FAILED(40005, "用户名或密码错误");

    private final int code;
    private final String message;

    // getter / constructor 省略
}
```

### 16.2 全局异常处理器

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 业务异常
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("Business exception: code={}, message={}", e.getCode(), e.getMessage());
        return ResponseEntity.status(e.getHttpStatus())
                .body(ApiResponse.error(e.getCode(), e.getMessage()));
    }

    // AI 异常 — 区分对待，部分可降级
    @ExceptionHandler(AiException.class)
    public ResponseEntity<ApiResponse<Void>> handleAi(AiException e) {
        log.error("AI exception: code={}, model={}, stage={}",
                e.getCode(), e.getModel(), e.getStage(), e);
        // AI 异常返回 503，提示用户稍后重试或检查配置
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(e.getCode(),
                        e.getMessage() + "（AI 服务暂时不可用，系统已自动降级）"));
    }

    // 参数校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<List<FieldError>>> handleValidation(
            MethodArgumentNotValidException e) {
        List<FieldError> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .collect(Collectors.toList());
        return ResponseEntity.badRequest()
                .body(ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), "参数校验失败", errors));
    }

    // 兜底
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnknown(Exception e) {
        log.error("Unhandled exception: ", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), "系统内部错误，请稍后重试"));
    }
}
```

### 16.3 统一响应包装

```java
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;
    private String traceId;      // 从 MDC 获取，前端报错时贴给客服
    private long timestamp;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = 200;
        r.message = "success";
        r.data = data;
        r.traceId = MDC.get("traceId");
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return error(code, message, null);
    }

    public static <T> ApiResponse<T> error(int code, String message, T data) {
        ApiResponse<T> r = new ApiResponse<>();
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = MDC.get("traceId");
        r.timestamp = System.currentTimeMillis();
        return r;
    }
}
```

---

## 17. AI 调用容灾、降级与成本控制

这是整个项目最大的**生产风险点**——AI API 是外部依赖，不可控因素多（网络超时、限流、模型下线、费用超标）。必须有完整的容灾和降级方案。

### 17.1 AI 调用重试策略

```java
@Component
public class AiRetryHandler {

    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 1000;

    /**
     * 带指数退避的重试执行
     */
    public <T> T executeWithRetry(Supplier<T> call, String stage) throws AiException {
        int attempt = 0;
        AiException lastException = null;

        while (attempt < MAX_RETRIES) {
            try {
                return call.get();
            } catch (AiException e) {
                lastException = e;
                attempt++;

                // 不可重试的错误直接抛
                if (e.getCode() == ErrorCode.AI_API_AUTH_FAILED.getCode()
                        || e.getCode() == ErrorCode.AI_TOKEN_BUDGET_EXCEEDED.getCode()
                        || e.getCode() == ErrorCode.AI_MODEL_NOT_FOUND.getCode()) {
                    throw e;
                }

                if (attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << (attempt - 1)); // 1s, 2s, 4s
                    log.warn("AI call retry {}/{} for stage={}, waiting {}ms",
                            attempt, MAX_RETRIES, stage, backoff);
                    try { Thread.sleep(backoff); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw lastException;
    }
}
```

### 17.2 熔断器（简易实现，不引入 Resilience4j 依赖）

```java
@Component
public class AiCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 5;  // 连续失败 5 次熔断
    private static final long TIMEOUT_MS = 60_000;     // 熔断 60 秒后半开
    private static final int HALF_OPEN_MAX = 2;         // 半开状态下最多允许 2 个请求

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private volatile CircuitState state = CircuitState.CLOSED;
    private final AtomicInteger halfOpenCount = new AtomicInteger(0);

    public enum CircuitState { CLOSED, OPEN, HALF_OPEN }

    public boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - lastFailureTime.get() > TIMEOUT_MS) {
                    state = CircuitState.HALF_OPEN;
                    halfOpenCount.set(0);
                    log.warn("Circuit breaker: OPEN → HALF_OPEN");
                    return true;
                }
                return false;
            case HALF_OPEN:
                return halfOpenCount.incrementAndGet() <= HALF_OPEN_MAX;
            default:
                return false;
        }
    }

    public void recordSuccess() {
        failureCount.set(0);
        if (state == CircuitState.HALF_OPEN) {
            state = CircuitState.CLOSED;
            log.info("Circuit breaker: HALF_OPEN → CLOSED (recovered)");
        }
    }

    public void recordFailure() {
        int failures = failureCount.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());
        if (failures >= FAILURE_THRESHOLD && state == CircuitState.CLOSED) {
            state = CircuitState.OPEN;
            log.error("Circuit breaker: CLOSED → OPEN ({} consecutive failures)", failures);
            // 此时可触发告警
        }
    }
}
```

### 17.3 AI 降级策略矩阵

```
┌─────────────────────────┬──────────────────────────────────────────────┐
│ 场景                     │ 降级行为                                      │
├─────────────────────────┼──────────────────────────────────────────────┤
│ AI API 超时/不可用       │ 标记分析为 DEGRADED 状态                        │
│                         │ 继续完成 Stage 1-2（语法解析、依赖图）            │
│                         │ Stage 3-5 跳过，展示基础地图（无 AI 标注）        │
│                         │ 用户可手动重新触发 AI 分析                        │
├─────────────────────────┼──────────────────────────────────────────────┤
│ 单次 AI 响应幻觉         │ HallucinationChecker 拦截                      │
│                         │ 自动重试 1 次（换 lower temperature）            │
│                         │ 仍失败则跳过该条洞察，标注"AI 分析不完整"           │
├─────────────────────────┼──────────────────────────────────────────────┤
│ Token 预算耗尽           │ 拒绝新的 AI 分析请求                             │
│                         │ 已经完成的分析结果正常展示                         │
│                         │ 前端提示"本月 AI 额度已用完，请联系管理员"          │
├─────────────────────────┼──────────────────────────────────────────────┤
│ 模型上下文超限           │ 自动触发"代码分片"策略                            │
│ (项目过大)              │ 按模块/包拆分成多个子任务分批次送 AI                 │
│                         │ 合并结果后展示                                   │
├─────────────────────────┼──────────────────────────────────────────────┤
│ 主模型不可用             │ 自动 fallback 到备选模型                          │
│ (Claude → DeepSeek)     │ 降低期望：备选模型分析深度不如主力                  │
│                         │ 前端标注"切换至备选模型，分析结果仅供参考"            │
└─────────────────────────┴──────────────────────────────────────────────┘
```

### 17.4 多模型 Fallback 链

```java
@Component
public class AiClientFallbackChain {

    private final List<AiClient> clients;  // 按优先级排序注入

    public AiResponse chatWithFallback(AiRequest request) throws AiException {
        AiException lastException = null;

        for (AiClient client : clients) {
            try {
                log.info("Trying AI model: {}", client.getModelIdentifier());
                AiResponse response = client.chat(request);
                // 标记用了哪个模型
                response.setModelUsed(client.getModelIdentifier());
                // 非主力模型打标记
                if (!client.getModelIdentifier().equals(getPrimaryModel())) {
                    response.setFallback(true);
                }
                return response;
            } catch (AiException e) {
                lastException = e;
                log.warn("AI model {} failed: {}", client.getModelIdentifier(), e.getMessage());
                // 继续尝试下一个
            }
        }

        throw lastException != null ? lastException
                : new AiException(ErrorCode.AI_MODEL_NOT_FOUND, "所有 AI 模型均不可用");
    }

    private String getPrimaryModel() {
        return clients.isEmpty() ? "unknown" : clients.get(0).getModelIdentifier();
    }
}
```

### 17.5 Token 预算与成本控制

```java
@Component
public class TokenBudgetManager {

    // 月度预算（可从配置中心动态调整）
    private static final long MONTHLY_TOKEN_BUDGET = 10_000_000; // 1000 万 token/月
    private static final long MONTHLY_COST_BUDGET_CENTS = 50_00; // $50.00/月

    private final RedisTemplate<String, String> redis;

    /**
     * 消费前检查预算
     */
    public boolean tryConsume(String userId, String model, long estimatedTokens) {
        String monthKey = "budget:" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String usedKey = monthKey + ":tokens";

        Long used = redis.opsForValue().increment(usedKey, estimatedTokens);
        if (used != null && used > MONTHLY_TOKEN_BUDGET) {
            redis.opsForValue().decrement(usedKey, estimatedTokens); // 回滚
            log.warn("Token budget exceeded: used={}, budget={}", used, MONTHLY_TOKEN_BUDGET);
            return false;
        }
        return true;
    }

    /**
     * 获取本月使用情况
     */
    public BudgetStatus getBudgetStatus() {
        String monthKey = "budget:" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Long used = Long.parseLong(
                redis.opsForValue().get(monthKey + ":tokens") != null
                        ? redis.opsForValue().get(monthKey + ":tokens") : "0");
        return new BudgetStatus(used, MONTHLY_TOKEN_BUDGET,
                MONTHLY_TOKEN_BUDGET - used > 0 ? MONTHLY_TOKEN_BUDGET - used : 0);
    }
}
```

### 17.6 超大项目分片策略

```
当项目代码量超过 AI 模型单次上下文限制时:

1. 自动检测: 估算 prompt token 数 > maxContextTokens * 0.7 时触发分片
2. 分片策略:
   a. 按 Maven/Gradle 模块拆分
   b. 每个模块独立生成 prompt（模块名 + 依赖 + 类摘要）
   c. 并发送 AI（限制并发数 3，防止 API 限流）
   d. 聚合各模块结果
3. 分片提示:
   前端显示 "项目过大（12万+类），已自动拆分为 8 个分析单元分别处理"
4. 聚合质量:
   拆分后的叙事可能缺乏全局视角，聚合阶段再做一次"全局总结" call
```

---

## 18. CI/CD 流水线与代码质量门禁

### 18.1 GitHub Actions 流水线

```yaml
# .github/workflows/ci.yml
name: CodeAtlas CI

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest

    services:
      mysql:
        image: mysql:5.7
        env:
          MYSQL_ROOT_PASSWORD: codeatlas
          MYSQL_DATABASE: codeatlas_test
        ports:
          - 3306:3306
        options: >-
          --health-cmd="mysqladmin ping"
          --health-interval=10s
          --health-timeout=5s
          --health-retries=3

      redis:
        image: redis:7-alpine
        ports:
          - 6379:6379

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup JDK 8
        uses: actions/setup-java@v4
        with:
          java-version: '8'
          distribution: 'temurin'

      - name: Cache Maven
        uses: actions/cache@v4
        with:
          path: ~/.m2
          key: ${{ runner.os }}-maven-${{ hashFiles('**/pom.xml') }}

      - name: Code Quality Check
        run: mvn checkstyle:check pmd:check

      - name: Unit Tests
        run: mvn test -DexcludeTags=ai-integration

      - name: Integration Tests
        run: mvn verify -P integration-test -DexcludeTags=slow

      - name: Upload Test Report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: test-reports
          path: '**/target/surefire-reports/*.xml'

      - name: JaCoCo Coverage Check
        run: mvn jacoco:check
        # 覆盖率门槛配置在 pom.xml 的 jacoco-maven-plugin 中

  frontend-build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: 'codeatlas-web/package-lock.json'

      - name: Install & Lint
        working-directory: codeatlas-web
        run: |
          npm ci
          npm run lint

      - name: Build
        working-directory: codeatlas-web
        run: npm run build

      - name: Upload Build Artifact
        uses: actions/upload-artifact@v4
        with:
          name: frontend-dist
          path: codeatlas-web/dist/

  docker-build:
    needs: [build-and-test, frontend-build]
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - name: Build & Push Docker Image
        run: |
          docker build -t codeatlas:${{ github.sha }} .
          # docker push ...（需配置 Docker Hub 或 GHCR）
```

### 18.2 代码质量门禁

**Checkstyle 规则** (`checkstyle.xml` — 基于 Sun/Google 规范定制):

```xml
<module name="Checker">
    <module name="TreeWalker">
        <!-- 命名规范 -->
        <module name="ConstantName" />
        <module name="LocalFinalVariableName" />
        <module name="MemberName" />
        <module name="MethodName" />
        <module name="PackageName" />
        <module name="TypeName" />

        <!-- 禁止事项 -->
        <module name="AvoidStarImport" />            <!-- 禁止 import * -->
        <module name="IllegalImport">                <!-- 禁止 util 包 imports -->
            <property name="illegalPkgs" value="sun.*, java.util.logging" />
        </module>
        <module name="RedundantImport" />
        <module name="LineLength">
            <property name="max" value="120" />
        </module>

        <!-- 复杂度 -->
        <module name="CyclomaticComplexity">
            <property name="max" value="15" />
        </module>
        <module name="MethodLength">
            <property name="max" value="80" />
        </module>

        <!-- 安全（自定义规则，需 Checkstyle 扩展） -->
        <module name="RegexpSinglelineJava">
            <property name="format"
                value="System\.out\.print|printStackTrace|\.getMessage\(\)\s*$" />
            <property name="message"
                value="Use SLF4J logger, not System.out/printStackTrace" />
        </module>
    </module>
</module>
```

**Maven 插件配置** (`pom.xml`):

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-checkstyle-plugin</artifactId>
            <version>3.3.1</version>
            <configuration>
                <configLocation>checkstyle.xml</configLocation>
                <failOnViolation>true</failOnViolation>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-pmd-plugin</artifactId>
            <version>3.22.0</version>
            <configuration>
                <rulesets>
                    <ruleset>category/java/bestpractices.xml</ruleset>
                    <ruleset>category/java/security.xml</ruleset>
                </rulesets>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.jacoco</groupId>
            <artifactId>jacoco-maven-plugin</artifactId>
            <version>0.8.12</version>
            <executions>
                <execution>
                    <id>jacoco-check</id>
                    <goals><goal>check</goal></goals>
                    <configuration>
                        <rules>
                            <rule>
                                <element>BUNDLE</element>
                                <limits>
                                    <limit>
                                        <counter>LINE</counter>
                                        <value>COVEREDRATIO</value>
                                        <minimum>0.70</minimum>
                                    </limit>
                                </limits>
                            </rule>
                        </rules>
                    </configuration>
                </execution>
            </executions>
        </plugin>
        <!-- OWASP 依赖漏洞检查 -->
        <plugin>
            <groupId>org.owasp</groupId>
            <artifactId>dependency-check-maven</artifactId>
            <version>9.2.0</version>
            <configuration>
                <failBuildOnCVSS>7</failBuildOnCVSS> <!-- CVSS >= 7 构建失败 -->
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 19. API 文档自动生成

### 19.1 Knife4j (Swagger 增强) 集成

```java
@Configuration
@EnableSwagger2WebMvc
public class SwaggerConfig {

    @Bean
    public Docket apiDocket() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(new ApiInfoBuilder()
                        .title("CodeAtlas API")
                        .description("AI 驱动的代码地图与架构叙事平台")
                        .version("v1.0")
                        .contact(new Contact("Zferrys", "https://github.com/zferrys/codeatlas", ""))
                        .build())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.codeatlas.server.controller"))
                .paths(PathSelectors.any())
                .build()
                .securitySchemes(Collections.singletonList(
                        new ApiKey("JWT", "Authorization", "header")))
                .globalOperationParameters(Collections.singletonList(
                        new ParameterBuilder()
                                .name("Authorization")
                                .description("Bearer <token>")
                                .modelRef(new ModelRef("string"))
                                .parameterType("header")
                                .required(false)
                                .build()));
    }
}
```

**Controller 示例注解**:

```java
@RestController
@RequestMapping("/api/v1/projects")
@Api(tags = "项目管理")
public class ProjectController {

    @PostMapping
    @ApiOperation(value = "创建项目", notes = "支持 Git URL / ZIP 上传 / 本地路径")
    @ApiImplicitParams({
        @ApiImplicitParam(name = "name", value = "项目名称", required = true),
        @ApiImplicitParam(name = "sourceType", value = "源码来源类型", required = true,
                          allowableValues = "GIT_URL,ZIP_UPLOAD,LOCAL_PATH")
    })
    public ApiResponse<ProjectVO> create(@Valid @RequestBody ProjectCreateDTO dto) { ... }

    @GetMapping("/{id}")
    @ApiOperation("获取项目详情")
    public ApiResponse<ProjectVO> getById(@PathVariable @ApiParam("项目ID") Long id) { ... }
}
```

**访问**: 启动后 `http://localhost:8080/doc.html`

---

## 20. 健康检查与优雅关闭

### 20.1 健康检查端点

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,env
      base-path: /actuator
  endpoint:
    health:
      show-details: when-authorized  # 仅管理员可见详情
      show-components: always
      probes:
        enabled: true                 # 启用 K8s 探针
  health:
    db:
      enabled: true                   # MySQL 连接检查
    redis:
      enabled: true
    neo4j:
      enabled: true
```

**自定义健康指示器**:

```java
@Component
public class AiApiHealthIndicator implements HealthIndicator {

    private final List<AiClient> aiClients;

    @Override
    public Health health() {
        Health.Builder builder = Health.up();
        for (AiClient client : aiClients) {
            try {
                if (client.healthCheck()) {
                    builder.withDetail(client.getModelIdentifier(), "UP");
                } else {
                    builder.withDetail(client.getModelIdentifier(), "DOWN").down();
                }
            } catch (Exception e) {
                builder.withDetail(client.getModelIdentifier(), "DOWN: " + e.getMessage()).down();
            }
        }
        return builder.build();
    }
}
```

**端点清单**:

| 路径 | 用途 |
|------|------|
| `/actuator/health` | 综合健康状态（LB 探针） |
| `/actuator/health/liveness` | K8s liveness probe |
| `/actuator/health/readiness` | K8s readiness probe（含 DB/Redis/AI） |
| `/actuator/info` | 应用信息（版本、构建时间） |
| `/actuator/metrics` | 所有指标汇总 |
| `/actuator/prometheus` | Prometheus 拉取格式指标 |

### 20.2 优雅关闭

```yaml
server:
  shutdown: graceful                 # Spring Boot 2.3+ 优雅关闭
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 关闭超时 30 秒

# 自定义关闭逻辑
```

```java
@Component
public class GracefulShutdownHook implements DisposableBean, ApplicationListener<ContextClosedEvent> {

    @Override
    public void destroy() {
        log.info("CodeAtlas shutting down...");
        // 1. 拒绝新请求
        // 2. 等待进行中的扫描任务完成（或超时强制终止）
        // 3. 关闭 Neo4j 连接
        // 4. 关闭 Redis 连接池
        // 5. 刷新日志缓冲区
        log.info("CodeAtlas shutdown complete.");
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        destroy();
    }
}
```

### 20.3 前端静态资源

```nginx
# Nginx 配置（生产环境）
server {
    listen 443 ssl http2;
    server_name codeatlas.example.com;

    ssl_certificate     /etc/nginx/ssl/codeatlas.crt;
    ssl_certificate_key /etc/nginx/ssl/codeatlas.key;

    # 健康检查（LB 探测）
    location /health {
        return 200 'OK';
        add_header Content-Type text/plain;
    }

    # 前端静态资源（长期缓存 + Gzip）
    location / {
        root /var/www/codeatlas-web;
        try_files $uri $uri/ /index.html;
        location ~* \.(js|css|png|jpg|svg|woff2)$ {
            expires 1y;
            add_header Cache-Control "public, immutable";
        }
        gzip_static on;
    }

    # API 反向代理
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_read_timeout 300s;  # AI 分析可能很久
    }

    # WebSocket 升级
    location /ws/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
    }

    # Actuator 不对外暴露（只允许内网）
    location /actuator/ {
        allow 127.0.0.1;
        allow 10.0.0.0/8;
        deny all;
        proxy_pass http://127.0.0.1:8080;
    }
}
```

---

## 21. 性能优化策略

### 21.1 后端性能优化

**数据库层**:

```sql
-- 索引补充（文档 §7 表设计中遗漏的关键索引）
ALTER TABLE `scan` ADD INDEX `idx_project_status` (`project_id`, `status`);
ALTER TABLE `class_summary` ADD INDEX `idx_scan_layer` (`scan_id`, `layer`);
ALTER TABLE `violation` ADD INDEX `idx_project_severity_resolved` (`project_id`, `severity`, `is_resolved`);
ALTER TABLE `insight` ADD INDEX `idx_project_type` (`project_id`, `type`);
ALTER TABLE `audit_log` ADD INDEX `idx_created_at_user` (`created_at`, `user_id`);
```

**连接池调优**:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30       # 生产环境 30
      minimum-idle: 10
      connection-timeout: 5000
      idle-timeout: 600000
      max-lifetime: 1800000

  redis:
    lettuce:
      pool:
        max-active: 20
        max-idle: 10
```

**大项目分析优化**:

```java
// 1. 并行解析 — 多文件并发处理
ForkJoinPool parserPool = new ForkJoinPool(Runtime.getRuntime().availableProcessors());
parserPool.submit(() ->
    sourceFiles.parallelStream()
        .map(parser::parseFile)
        .collect(Collectors.toList())
).get();

// 2. 分批写入 Neo4j — 每 500 条 batch 一次
try (Transaction tx = session.beginTransaction()) {
    for (int i = 0; i < classes.size(); i++) {
        tx.run(cypher, params(classes.get(i)));
        if (i % 500 == 0) {
            tx.commit();
            tx = session.beginTransaction();
        }
    }
    tx.commit();
}

// 3. 地图数据分页返回 — 前端按需加载
@GetMapping("/map-data")
public ApiResponse<MapData> getMapData(
        @RequestParam Long projectId,
        @RequestParam(defaultValue = "SERVICE") String level, // SERVICE/MODULE/PACKAGE/CLASS
        @RequestParam(required = false) String module,        // 可选：按模块过滤
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "200") int size) {
    // 只返回当前缩放级别需要的数据
}
```

### 21.2 前端性能优化

```javascript
// 1. 路由懒加载
const ProjectMapView = () => import('@/views/ProjectMapView.vue');

// 2. 地图渲染虚拟化 — 只渲染视口内节点
const useVirtualNodes = (allNodes, viewport) => {
    return computed(() => {
        const { x, y, width, height, scale } = viewport.value;
        return allNodes.value.filter(node => {
            return node.x > x - 100 / scale
                && node.x < x + width / scale + 100
                && node.y > y - 100 / scale
                && node.y < y + height / scale + 100;
        });
    });
};

// 3. Web Worker 力导向布局
const worker = new Worker('/workers/force-layout.worker.js');
worker.postMessage({ nodes, links });
worker.onmessage = (e) => {
    updateNodePositions(e.data);
};

// 4. Canvas 替代 SVG（大节点量场景）
// 1000 节点以下: SVG（交互性好）
// 1000~5000 节点: Canvas 2D（性能好）
// 5000+ 节点: Canvas 2D + LOD + 虚拟化
```

### 21.3 缓存策略分层

```
请求链路缓存层级:
┌─────────────────────────────────────────────────────────┐
│  Layer 1: 浏览器本地缓存                                  │
│  │ Cache-Control: public, max-age=300 (地图数据 5min)     │
│  │ ETag/If-None-Match → 304 Not Modified                 │
│  │ 场景: 地图数据、AI 叙事（内容大但变化不频繁）            │
├─────────────────────────────────────────────────────────┤
│  Layer 2: Nginx 静态文件缓存                              │
│  │ 静态资源 1 年强缓存（版本 hash 文件名）                  │
│  │ HTML 不缓存（保证更新即时生效）                         │
├─────────────────────────────────────────────────────────┤
│  Layer 3: Redis 热数据缓存                               │
│  │ map:data:{projectId}    TTL=30min                     │
│  │ insight:{projectId}     TTL=30min                     │
│  │ 失效策略: 项目 re-scan 时主动删除相关缓存                │
├─────────────────────────────────────────────────────────┤
│  Layer 4: MySQL/Neo4j — 持久存储                         │
│  │ 全量数据、历史扫描记录                                  │
└─────────────────────────────────────────────────────────┘
```

---

## 22. GitHub 开源运营规范

### 22.1 仓库文件结构

```
codeatlas/
├── .github/
│   ├── workflows/ci.yml              # CI 流水线
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.md
│   │   └── feature_request.md
│   └── PULL_REQUEST_TEMPLATE.md
├── README.md                          # ★ 项目门面
├── README_ZH.md                       # 中文 README
├── CONTRIBUTING.md                    # 贡献指南
├── CHANGELOG.md                       # 版本变更日志
├── LICENSE                            # Apache 2.0
├── CODE_OF_CONDUCT.md                 # 行为准则
├── SECURITY.md                        # 安全漏洞报告流程
├── docs/
│   ├── REQUIREMENTS.md                # 本文档
│   ├── ARCHITECTURE.md                # 架构详解（面向贡献者）
│   ├── QUICKSTART.md                  # 5分钟快速启动
│   ├── EXTENSION.md                   # 扩展开发指南
│   └── SCREENSHOTS/                   # 产品截图（README 引用）
├── codeatlas-common/
├── codeatlas-engine/
├── codeatlas-server/
├── codeatlas-web/
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

### 22.2 README.md 模板

```markdown
<p align="center">
  <img src="docs/logo.png" alt="CodeAtlas" width="200"/>
</p>

<h1 align="center">CodeAtlas</h1>

<p align="center">
  <strong>AI-Powered Code Architecture Visualization & Intelligence Platform</strong>
</p>

<p align="center">
  <a href="README_ZH.md">中文文档</a> |
  <a href="docs/QUICKSTART.md">Quick Start</a> |
  <a href="docs/REQUIREMENTS.md">Full Docs</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-8-orange" alt="Java 8">
  <img src="https://img.shields.io/badge/spring--boot-2.7-brightgreen" alt="Spring Boot 2.7">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
  <img src="https://img.shields.io/github/stars/zferrys/codeatlas" alt="Stars">
</p>

---

## What is CodeAtlas?

Upload your code → AI generates an interactive **3D code topology map**.
Explore your architecture like Google Earth, with an AI tour guide narrating the architectural story.

![Demo Screenshot](docs/SCREENSHOTS/demo.gif)

## Why CodeAtlas?

| Pain Point | CodeAtlas Solution |
|-----------|-------------------|
| New developers struggle to understand the codebase | AI auto-generates a code map + architecture story — understand in 10 minutes |
| Technical debt is invisible | AI detects anti-patterns, highlighted as "decay zones" on the map |
| Change impact is unknown | AI simulates impact ripple effects with animation |
| Architecture docs are always outdated | Auto-updates after every commit |

## Features

- 🔭 **Interactive 3D Code Map** — Force-directed topology graph with multi-level zoom
- 🤖 **AI Architecture Storyteller** — Generates comprehensive architecture documentation
- 📜 **Constitution Rule Engine** — Configurable architecture governance rules (inspired by Claude's Constitutional AI)
- 💥 **Change Impact Simulator** — Visualize ripple effects of code changes
- 🔌 **Pluggable Architecture** — SPI-based extensions for languages, AI models, exporters

## Quick Start

```bash
# 1. Clone
git clone https://github.com/zferrys/codeatlas.git
cd codeatlas

# 2. Start dependencies
docker-compose up -d mysql neo4j redis

# 3. Configure AI API key
export CLAUDE_API_KEY=your-api-key

# 4. Build & Run
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. Open browser
open http://localhost:8080
```

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 8, Spring Boot 2.7, MyBatis, Neo4j, Redis |
| Frontend | Vue 3, D3.js, Three.js, Ant Design Vue |
| AI | Claude API / DeepSeek API (direct HTTP, no framework) |
| Storage | MySQL 5.7 + Neo4j + Redis |
| DevOps | Docker, GitHub Actions, Prometheus |

## Architecture

```
Upload Code → Parse (JavaParser) → Build Dependency Graph (Neo4j)
→ AI Multi-Stage Analysis Pipeline → Generate Map + Story
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for details.

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=zferrys/codeatlas&type=Date)](https://star-history.com/#zferrys/codeatlas&Date)
```

### 22.3 CHANGELOG.md 规范

```markdown
# Changelog

格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### Added
- 3D 拓扑图支持（Three.js 升级中）

## [0.1.0] — 2026-09-01

### Added
- MVP 版本发布
- 项目管理（创建、导入 Git）
- Java 代码解析与依赖图构建
- 2D 力导向图渲染
- AI 架构叙事生成
- 用户注册登录与 JWT 认证

### Known Issues
- 仅支持 Java 项目
- 大项目（10万+类）分析时间较长
```

### 22.4 License 选择

```
推荐: Apache License 2.0

理由:
  1. 允许商用 — 企业可以内部部署使用
  2. 明确的专利授权 — 贡献者授予专利使用权
  3. 要求保留版权声明 — 你的名字永远留在代码里
  4. GitHub 最常用的开源协议之一

备选: MIT（更宽松，不要求声明修改）或 GPLv3（更严格，要求衍生作品也必须开源）
```

---

## 23. 数据库版本迁移

### 23.1 Flyway 集成

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

```yaml
spring:
  flyway:
    enabled: true
    baseline-on-migrate: true    # 已有数据库兼容
    baseline-version: 1
    locations: classpath:db/migration
    table: flyway_schema_history # 迁移历史表名
    validate-on-migrate: true    # 迁移前校验已有脚本未被篡改
```

### 23.2 迁移脚本命名规范

```
db/migration/
├── V1__init_schema.sql              # 初始建表
├── V2__seed_default_rules.sql       # 内置宪法规则数据
├── V3__add_scan_branch_column.sql   # 增量变更示例
├── V4__add_index_scan_status.sql    # 索引优化示例
└── V5__add_ai_audit_table.sql       # AI 审计表
```

### 23.3 注意事项

```
1. 迁移脚本一旦合入主分支，永不可修改 — 只能新增脚本
2. 脚本命名: V{版本号}__{描述}.sql — 版本号顺序执行
3. 所有 DDL 变更必须通过 Flyway，不能在数据库里手动改表
4. 回滚方案: 写逆向迁移脚本放在 db/undo/ 目录（Flyway Pro 功能）
   社区版替代方案: 写逆向 SQL 注释在迁移脚本末尾，出错时手动执行
5. CI 中集成 Flyway 校验:
   mvn flyway:validate -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...
```

---

## 24. 附录

### 24.1 完整环境变量清单

```bash
# 生产环境必须配置的环境变量
export CLAUDE_API_KEY=sk-ant-xxx           # Anthropic Claude API Key
export DEEPSEEK_API_KEY=sk-xxx             # DeepSeek API Key（备选）
export JWT_SECRET=$(openssl rand -base64 64)  # JWT 签名密钥，生产环境必须更换
export MYSQL_PASSWORD=xxx                  # MySQL 密码（开发环境默认 root）
export NEO4J_PASSWORD=xxx                  # Neo4j 密码（开发环境默认 codeatlas）
export REDIS_PASSWORD=xxx                  # Redis 密码（开发环境无密码）

# 可选
export CODEATLAS_AI_MONTHLY_BUDGET=10000000  # 月度 Token 预算
export CODEATLAS_MAX_PROJECT_SIZE=524288000   # 最大项目大小 500MB
export CODEATLAS_MAX_CONCURRENT_SCANS=3       # 最大并发扫描数
```

### 24.2 Dockerfile（多阶段构建）

```dockerfile
# ==================== 前端构建 ====================
FROM node:20-alpine AS frontend-builder
WORKDIR /app
COPY codeatlas-web/package*.json ./
RUN npm ci
COPY codeatlas-web/ ./
RUN npm run build

# ==================== 后端构建 ====================
FROM maven:3.8-openjdk-8 AS backend-builder
WORKDIR /app
COPY pom.xml .
COPY codeatlas-common/pom.xml codeatlas-common/
COPY codeatlas-engine/pom.xml codeatlas-engine/
COPY codeatlas-server/pom.xml codeatlas-server/
RUN mvn dependency:go-offline -B
COPY . .
RUN mvn clean package -DskipTests -pl codeatlas-server -am

# ==================== 运行镜像 ====================
FROM openjdk:8-jre-alpine
RUN apk add --no-cache tini curl

WORKDIR /app
COPY --from=backend-builder /app/codeatlas-server/target/*.jar app.jar
COPY --from=frontend-builder /app/dist /app/static

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
ENTRYPOINT ["tini", "--"]
CMD ["java", "-Xms512m", "-Xmx2g", "-XX:+UseG1GC",
     "-Djava.security.egd=file:/dev/./urandom",
     "-jar", "app.jar"]
```

### 24.3 .gitignore

```gitignore
# Maven
target/
*.jar
*.war

# Node
node_modules/
dist/
.env.local

# IDE
.idea/
*.iml
.vscode/

# OS
.DS_Store
Thumbs.db

# Logs
logs/
*.log

# Sensitive
*.pem
*.key
.env
application-local.yml

# AI 缓存目录
.ai-cache/

# Plugin 外部 jar
plugins/*.jar
!plugins/.gitkeep

# Temp
temp/
tmp/
```

### 24.4 项目初始化 Checklist

开发第一天需要完成的事项：

```
□ 1. 创建 Maven 父 POM（packaging=pom）
□ 2. 创建子模块: common, engine, server
□ 3. 配置 pom.xml 基础依赖（Spring Boot Parent 2.7.x）
□ 4. 创建 Spring Boot 启动类
□ 5. 配置 application.yml（dev profile）
□ 6. 初始化 MySQL 库 (CREATE DATABASE codeatlas)
□ 7. 执行 V1__init_schema.sql（所有建表语句）
□ 8. 启动 Spring Boot，验证无报错
□ 9. 初始化 Vue 3 + Vite 项目（codeatlas-web）
□ 10. 配置 Axios + Pinia + Vue Router
□ 11. 创建 /login 页面 + JWT 登录流程
□ 12. Git init + 首次 commit
```

---

> **文档状态**: ✅ 完整 (v1.1)  
> **变更记录**: v1.1 新增 §15-§23 共 9 个章节，覆盖日志监控、异常处理、AI 容灾降级成本控制、CI/CD、API 文档、健康检查、性能优化、GitHub 运营、数据库迁移  
> **下一步**: 按 §24.4 初始化 Checklist 开始项目骨架搭建  
> **文档维护**: 任何架构变更必须同步更新本文档
