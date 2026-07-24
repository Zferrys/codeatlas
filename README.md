<p align="center">
  <h1 align="center">CodeAtlas</h1>
  <p align="center">
    <strong>AI-Powered Code Architecture Visualization & Intelligence Platform</strong>
  </p>
</p>

<p align="center">
  <a href="README_ZH.md">中文文档</a> |
  <a href="docs/REQUIREMENTS.md">Full Docs</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/spring--boot-3.3-brightgreen" alt="Spring Boot 3.3">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
</p>

---

## What is CodeAtlas?

Upload your code (Git URL or ZIP) and CodeAtlas parses the source, builds a dependency graph, and generates an interactive **3D code topology map** powered by AI. Explore your architecture like a map, with an AI tour guide narrating the architectural story.

## Why CodeAtlas?

| Pain Point | Solution |
|-----------|----------|
| New developers struggle to understand the codebase | AI generates a code map + architecture story — understand in 10 minutes |
| Technical debt is invisible | AI detects anti-patterns, highlighted as "decay zones" on the map |
| Change impact is unknown | AI simulates impact ripple effects with animation |
| Architecture docs are always outdated | Auto-updates after every scan |

## Features

- **Interactive 3D Code Map** — Three.js force-directed topology graph with CSS2D labels, heatmap, and edge highlighting
- **AI Architecture Storyteller** — Multi-model pipeline (Claude → DeepSeek fallback) with hallucination detection
- **Constitution Rule Engine** — Configurable architecture governance rules with violation detection
- **Change Impact Simulator** — BFS-based ripple effect analysis with AI-powered insights
- **SSE Real-time Progress** — Live scan progress streaming with clone heartbeat
- **RBAC Access Control** — 4-tier permissions (ADMIN/ARCHITECT/DEVELOPER/VIEWER)
- **i18n Support** — Chinese and English language switching
- **Dark/Light Theme** — Full theme support across all views

## Quick Start

### Prerequisites

- Java 17+
- Node.js 20+
- MySQL 5.7+
- Redis 7+
- Maven 3.8+

### Setup

```bash
# 1. Clone
git clone https://github.com/<your-org>/codeatlas.git
cd codeatlas

# 2. Start dependencies
docker-compose up -d mysql redis

# 3. Configure environment variables
export DEEPSEEK_API_KEY=<your-deepseek-api-key>
export ANTHROPIC_API_KEY=<your-claude-api-key>
export MYSQL_PASSWORD=<your-db-password>
export NEO4J_PASSWORD=<your-neo4j-password>
export CODEATLAS_JWT_SECRET=$(openssl rand -base64 64)

# 4. Build & Run backend
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. Start frontend
cd codeatlas-web
npm install
npm run dev

# 6. Open browser
# Backend API: http://localhost:8080
# Frontend: http://localhost:5173
```

> Change the default admin password immediately after first login.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.3, MyBatis 3, Neo4j, Redis |
| Frontend | Vue 3, Three.js, G6, Ant Design Vue |
| AI | Claude API / DeepSeek API with fallback chain & hallucination detection |
| Storage | MySQL + Neo4j (graph) + Redis (cache/rate-limit/budget) |
| DevOps | Docker, GitHub Actions, Prometheus + Grafana |

## Architecture Flow

```
Upload Code → Parse (JavaParser) → Build Dependency Graph (Neo4j)
→ AI Multi-Stage Analysis Pipeline → Generate 3D Map + Architecture Story
→ Constitution Rule Check → Impact Simulation
```

## Project Structure

```
codeatlas/
├── codeatlas-common/     # Shared DTOs, error codes, exceptions
├── codeatlas-engine/     # JavaParser, RuleEngine, GitService, AI clients
├── codeatlas-server/     # Spring Boot REST API
├── codeatlas-web/        # Vue 3 frontend
├── docs/                 # Documentation
│   └── REQUIREMENTS.md   # Full requirements specification
├── docker-compose.yml
├── Dockerfile
└── pom.xml
```

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Apache 2.0 — see [LICENSE](LICENSE) for details.
