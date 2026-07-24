# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in CodeAtlas, please **do not** open a public issue. Instead, report it privately:

1. Email: [maintainer contact]
2. Include detailed steps to reproduce
3. We aim to acknowledge within 48 hours and provide a fix within 7 days

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 0.2.x   | ✅ Active support  |
| 0.1.x   | ❌ End of life     |

## Security Practices

### Authentication
- Passwords hashed with BCrypt (never stored in plain text)
- JWT-based stateless authentication with configurable expiration
- Rate limiting on login endpoints (10 req/min)

### Data Protection
- All SQL queries use parameterized statements (MyBatis `#{}`) — no string concatenation
- File uploads validated via type whitelist and size limits
- Sensitive configuration via environment variables, never in code

### API Security
- CORS origins configurable via `CORS_ORIGINS` environment variable
- CSRF protection not required (stateless JWT API, no cookies)
- RBAC enforcement at method level via `@PreAuthorize`

### Dependency Management
- OWASP Dependency Check plugin in CI pipeline
- CVSS ≥ 7 vulnerabilities block the build
- Regular dependency updates

### Production Hardening
- Graceful shutdown with in-flight request completion
- Health check endpoints restricted to internal networks via Nginx
- JWT tokens masked in log output
