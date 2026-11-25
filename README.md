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
| `FORMKILN_SERVICE_TOKEN` | _(empty)_ | Bearer token for the management API. The management API is disabled when empty. |

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

## HTTP API

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details. Validation problems (422) carry an
`errors` array.

### Runtime (public)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/forms/{key}` | Latest published version: `{ key, version, publishedAt, definition }` |
| `POST` | `/v1/forms/{key}/submissions` | Body `{ "version"?, "answers", "locale"? }`. The server re-validates the answers against that version (default: latest) at the time of receipt. Returns `201 { id, version, outcome, submittedAt }`, or `422` with `[{ path, code, message?, params? }]`. Only the submission data (active, non-null values) is stored. |

### Management (`Authorization: Bearer $FORMKILN_SERVICE_TOKEN`)

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/manage/forms` | Forms with their latest version and submission count |
| `POST` | `/v1/manage/forms/{key}/versions` | Publish the form document in the body as a new immutable version. It is validated against the JSON Schema (`code: "schema"`) and the static rules of Form Schema §10. The document's `key` must equal `{key}` (`keyMismatch`). |
| `GET` | `/v1/manage/forms/{key}/versions` | Published versions, newest first |
| `GET` | `/v1/manage/forms/{key}/submissions?limit=50&before=<submittedAt>` | Submissions, newest first, paginated by submission time |

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
To report a security issue, see [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
