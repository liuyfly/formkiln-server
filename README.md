# Formkiln Server

Backend for [Formkiln](https://github.com/liuyfly/formkiln), an open-source, self-hostable form platform.
It stores form definitions and versions, drafts and submissions, re-validates every submission on the server
and delivers results through webhooks.

> **Status: early development (pre-alpha).** Not ready for production use.

## Tech stack

Java 21 · Spring Boot 4 (Spring MVC) · PostgreSQL · Flyway · Gradle · Testcontainers

## Getting started

Requirements: JDK 21, Docker (for integration tests), and a PostgreSQL 15+ database.

### 1. Database

Use an existing local PostgreSQL, or start one with Docker Compose (exposed on host port **5433**):

```bash
docker compose up -d postgres mailpit
```

### 2. Configuration

```bash
cp .env.example .env   # git-ignored; adjust the connection settings
```

| Variable | Default | Description |
| --- | --- | --- |
| `FORMKILN_DB_URL` | `jdbc:postgresql://localhost:5432/formkiln` | JDBC URL |
| `FORMKILN_DB_USERNAME` | `formkiln` | Database user |
| `FORMKILN_DB_PASSWORD` | `formkiln` | Database password |
| `FORMKILN_PORT` | `8080` | HTTP port |

Environment variables always take precedence over `.env`.

### 3. Run

```bash
./gradlew bootRun
# Health check: http://localhost:8080/actuator/health
```

### Tests

```bash
./gradlew build   # unit and integration tests; Testcontainers starts a throwaway PostgreSQL
```

The expression engine is tested against the conformance cases of the pinned
[`@formkiln/spec`](https://www.npmjs.com/package/@formkiln/spec) release, downloaded and verified during the build.

### Docker image

```bash
docker compose --profile app up --build
```

Release images are published to `ghcr.io/liuyfly/formkiln-server` when a `v*` tag is pushed.

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
To report a security issue, see [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
