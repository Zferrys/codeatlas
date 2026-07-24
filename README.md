<p align="center">
  <h1 align="center">CodeAtlas</h1>
  <p align="center">
    <strong>AI-Powered Code Architecture Visualization & Intelligence Platform</strong>
  </p>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/spring--boot-3.3-brightgreen" alt="Spring Boot 3.3">
  <img src="https://img.shields.io/badge/vue-3.x-green" alt="Vue 3">
  <img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License">
</p>

---

## What is CodeAtlas?

Upload your code → AI generates an interactive **3D code topology map**. Explore your architecture like Google Earth, with an AI tour guide narrating the architectural story.

## Why CodeAtlas?

| Pain Point | CodeAtlas Solution |
|-----------|-------------------|
| New developers struggle to understand the codebase | AI auto-generates a code map + architecture story — understand in 10 minutes |
| Technical debt is invisible | AI detects anti-patterns, highlighted as "decay zones" on the map |
| Change impact is unknown | AI simulates impact ripple effects with animation |
| Architecture docs are always outdated | Auto-updates after every scan |

## Features

- **Interactive 2D/3D Code Map** — Force-directed topology graph with zoom and pan
- **AI Architecture Storyteller** — Generates comprehensive architecture documentation via Claude/DeepSeek
- **Constitution Rule Engine** — Configurable architecture governance rules with violation detection
- **SSE Real-time Progress** — Live scan progress streaming
- **RBAC Access Control** — Role-based permissions (ADMIN/ARCHITECT/DEVELOPER/VIEWER)
- **Report Export** — PDF and HTML report generation
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
git clone https://github.com/zferrys/codeatlas.git
cd codeatlas

# 2. Start dependencies
docker-compose up -d mysql redis

# 3. Configure AI API keys
export DEEPSEEK_API_KEY=your-deepseek-key
export CLAUDE_API_KEY=your-claude-key

# 4. Build & Run backend
mvn clean package -DskipTests
java -jar codeatlas-server/target/codeatlas-server.jar

# 5. Start frontend
cd codeatlas-web
npm install
npm run dev

# 6. Open browser
open http://localhost:5173
```

Default admin account: `admin` / `admin123`

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Java 17, Spring Boot 3.3, MyBatis, Neo4j, Redis |
| Frontend | Vue 3, Ant Design Vue 4, G6, Three.js |
| AI | Claude API / DeepSeek API with Resilience4j circuit breaker |
| Storage | MySQL 5.7 + Neo4j + Redis |
| DevOps | Docker, GitHub Actions |

## Architecture

```
Upload Code → Parse (JavaParser) → Build Dependency Graph (Neo4j)
→ AI Multi-Stage Analysis Pipeline → Generate Map + Architecture Story
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
