# Contributing to CodeAtlas

Thanks for your interest in contributing!

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/YOUR_USERNAME/codeatlas.git`
3. Create a branch: `git checkout -b feat/your-feature`

## Development Setup

See [README.md](README.md#quick-start) for full setup instructions.

## Code Style

### Java
- Follow the project's coding conventions (see `.claude/rules/` for details)
- No wildcard imports (`import java.util.*`)
- Use SLF4J for logging (never `System.out.println`)
- Constructor injection preferred over `@Autowired`
- SQL parameters via `#{}` in MyBatis (never `${}`)

### Vue/JavaScript
- Use `<script setup>` syntax for Vue components
- Follow the existing component structure patterns
- CSS variables for theme colors (`var(--color-*)`)

## Pull Request Process

1. Ensure `mvn clean compile` passes
2. Ensure `mvn test` passes
3. Ensure `npm run build` passes in `codeatlas-web/`
4. Add a clear PR description explaining what and why
5. Link any related issues

## Commit Messages

Follow [conventional commits](https://www.conventionalcommits.org/):
- `feat:` — new feature
- `fix:` — bug fix
- `refactor:` — code restructuring
- `docs:` — documentation
- `chore:` — build/config changes

## Running Tests

```bash
# Backend
mvn test

# Frontend
cd codeatlas-web && npm run build
```

## Reporting Issues

Use the GitHub issue tracker. Include:
- Steps to reproduce
- Expected vs actual behavior
- CodeAtlas version and environment details
