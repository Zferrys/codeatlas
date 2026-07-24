# Changelog

Format based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
versioning follows [Semantic Versioning](https://semver.org/).

## [0.2.0] — Unreleased

### Added
- Resilience4j circuit breaker for AI API calls
- Redis distributed rate limiting with Lua scripts
- SSE real-time scan progress streaming
- File upload with ZIP extraction and auto-scan
- PDF/HTML report export via iText 7
- Global search across projects and classes
- Neo4j dependency graph visualization
- Redis caching layer with type-safe serialization
- Health check indicators (MySQL, Redis, AI API)
- Dark/light theme switching with full component coverage
- Custom `@Timed` Micrometer metrics for AI analysis and scans
- Rate limit hit counter via Micrometer

### Changed
- RuleEngine moved to `codeatlas-engine` module (pure Java, no Spring dependency)
- All list endpoints support server-side pagination
- Batch INSERT for ClassSummary and Violation (replaces N+1 single inserts)
- AI analysis uses `@CircuitBreaker` + `@Retry` + `@Bulkhead` instead of hand-written retry

### Fixed
- SSE `AccessDeniedException` via `DispatcherType.ASYNC` permit in SecurityConfig
- Redis cache deserialization `ClassCastException` (LinkedHashMap → Entity)
- Dark theme coverage for project cards, insights, code map, and auth views
- JavaParser language level upgraded to JAVA_17 for Text Block Literals support

## [0.1.0] — 2026-07-15

### Added
- Project management (create from Git URL, ZIP upload, local path)
- Java code parsing with JavaParser (AST extraction, class summary)
- 2D force-directed code map via G6
- 3D topology map via Three.js (CodeMap3D)
- AI architecture narrative generation (Claude + DeepSeek)
- Constitution rule engine with 6 built-in rules
- JWT authentication with RBAC (ADMIN/ARCHITECT/DEVELOPER/VIEWER)
- Audit logging via AOP
- Flyway database migration (V1 schema, V2 seed rules, V3 admin user)
- Knife4j API documentation
- MDC-based request tracing (X-Trace-Id)
