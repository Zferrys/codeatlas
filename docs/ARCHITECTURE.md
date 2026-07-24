# Architecture

CodeAtlas is a multi-module Maven project with a Vue 3 frontend.

## High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                        codeatlas-web                             │
│                     (Vue 3 + Vite + Ant DV)                       │
│   ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│   │ Dashboard │ │CodeMap 2D│ │CodeMap 3D│ │ ProjectOverview   │   │
│   │           │ │ (G6 v4)  │ │(Three.js)│ │ (charts + tables) │   │
│   └──────────┘ └──────────┘ └──────────┘ └──────────────────┘   │
└───────────────────────────┬──────────────────────────────────────┘
                            │ REST /api/v1 (JWT Bearer)
                            │ SSE (scan progress events)
┌───────────────────────────▼──────────────────────────────────────┐
│                       codeatlas-server                            │
│                    (Spring Boot 3.3.5 / JDK 17)                   │
│  ┌────────────┐ ┌─────────────┐ ┌───────────┐ ┌───────────────┐ │
│  │ Controller │ │  Service    │ │ Security  │ │  Config       │ │
│  │ (REST)    │ │  (business) │ │ (JWT+RBAC)│ │  (Cache, etc) │ │
│  └────────────┘ └──────┬──────┘ └───────────┘ └───────────────┘ │
│                        │                                         │
│  ┌─────────────────────┼─────────────────────────────────────┐   │
│  │              MyBatis Mappers                              │   │
│  └─────────────────────┼─────────────────────────────────────┘   │
└────────────────────────┼─────────────────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
     ┌─────────┐  ┌──────────┐  ┌──────────┐
     │  MySQL  │  │  Neo4j   │  │  Redis   │
     │ (5.7)   │  │ (5.x)    │  │ (7.x)    │
     │metadata │  │graph DB  │  │ cache    │
     └─────────┘  └──────────┘  └──────────┘
```

## Module Structure

```
codeatlas/
├── codeatlas-common/        # Shared DTOs, error codes, exceptions
├── codeatlas-engine/        # Core analysis engine
│   ├── ai/                  # AI clients (Claude, DeepSeek) + fallback chain
│   ├── git/                 # JGit clone, repo size estimation
│   ├── parser/              # Java source parser (class summaries)
│   └── rule/                # Architecture rule engine (constitutional AI)
├── codeatlas-server/        # Spring Boot application
│   ├── config/              # Beans, security, async, cache, scheduling
│   ├── controller/          # REST endpoints
│   ├── service/             # Business logic + Neo4j graph service
│   ├── mapper/              # MyBatis SQL mappings
│   ├── entity/              # Database entities
│   ├── dto/                 # Request/Response DTOs
│   ├── security/            # JWT, RBAC, rate limit
│   └── event/               # SSE scan progress events
└── codeatlas-web/           # Vue 3 SPA
    ├── src/
    │   ├── components/      # Reusable components (map, common)
    │   ├── views/           # Page views
    │   ├── router/          # Vue Router
    │   ├── stores/          # Pinia stores
    │   ├── api/             # Axios client
    │   └── locales/         # i18n (zh-CN, en)
    └── public/
```

## Request Flow — Scan Pipeline

```
1. User triggers scan (Git URL or ZIP upload)
2. ScanServiceImpl clones repo → workspace/scans/{projectId}-{scanId}/
3. JavaParserService analyzes files → List<ClassSummaryResult>
4. ClassSummaryResult → INSERT INTO class_summary (batch 100)
5. ClassSummaryResult → Neo4jGraphService.importGraph()
   a. DELETE existing nodes for projectId
   b. UNWIND nodes → MERGE (:Class)
   c. UNWIND edges → MERGE (:Class)-[:DEPENDS_ON]->(:Class)
6. RuleEngine checks architecture rules → INSERT INTO violation
7. AiAnalysisService.triggerAsync() → AI architecture story
8. Results: map page reads from Neo4j, insights from MySQL
```

## Layer Design

```
Controller (thin)
  │  Param validation, routing, HTTP concerns
  ▼
Service (business logic)
  │  Orchestration, transactions, caching
  ▼
Mapper (data access)
  │  MyBatis XML or annotation SQL
  ▼
Entity (domain model)
     Plain POJOs mapping to DB tables
```

- No cross-layer calls (Controller → Mapper is forbidden)
- Constructor injection (no `@Autowired` on fields)
- `@Transactional` only on Service layer

## Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| Neo4j via raw Driver (not Spring Data) | Full control over Cypher, no hidden N+1 queries |
| MyBatis (not JPA) | Fine-grained SQL control, easy optimization |
| G6 v4 + Three.js dual renderer | G6 for 2D overview, Three.js for immersive 3D |
| AiClientFallbackChain | Claude primary, DeepSeek secondary — zero-config failover |
| SSE for scan progress | Real-time without WebSocket complexity |
