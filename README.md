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
| `FORMKILN_ENCRYPTION_KEY` | _(required)_ | 32 random bytes, Base64 (`openssl rand -base64 32`). Encrypts secrets at rest (TOTP). Keep it safe: losing it locks out every member. |
| `FORMKILN_SERVICE_TOKEN` | _(empty)_ | Bearer token for automation on the management API (acts as an editor). Disabled when empty. |
| `FORMKILN_BOOTSTRAP_ADMIN_EMAIL` | _(empty)_ | Invited as the first admin when there are no members yet |
| `FORMKILN_ADMIN_URL` | `http://localhost:3200` | Base URL of the admin app, used in invitation and reset links |
| `FORMKILN_SMTP_HOST` / `FORMKILN_SMTP_PORT` | `localhost` / `1025` | SMTP server (Mailpit in development). For authenticated SMTP set `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`. |
| `FORMKILN_MAIL_FROM` | `Formkiln <no-reply@localhost>` | Sender of account emails |
| `FORMKILN_COOKIE_SECURE` | `true` | `Secure` flag of the session cookie (browsers accept it on `http://localhost`) |
| `FORMKILN_AUTH_RATE_LIMIT` | `20` | Requests per minute and client IP to the public authentication endpoints |

Environment variables always take precedence over `.env`. Behind a reverse proxy, set
`SERVER_FORWARD_HEADERS_STRATEGY=native` so rate limits and the audit log see the client IP.

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

### Management (signed-in member, or `Authorization: Bearer $FORMKILN_SERVICE_TOKEN`)

Reading requires the `viewer` role; publishing requires `editor`. The service token acts as an editor.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/manage/forms` | Forms with their latest version and submission count |
| `POST` | `/v1/manage/forms/{key}/versions` | Publish the form document in the body as a new immutable version. It is validated against the JSON Schema (`code: "schema"`) and the static rules of Form Schema §10. The document's `key` must equal `{key}` (`keyMismatch`). |
| `GET` | `/v1/manage/forms/{key}/versions` | Published versions, newest first |
| `GET` | `/v1/manage/forms/{key}/submissions?limit=50&before=<submittedAt>` | Submissions, newest first, paginated by submission time |

### Admin accounts

Members are invited by email. Each member has a password and an authenticator app (TOTP).
There is no self-registration.

- **Roles**: `admin` ⊃ `editor` ⊃ `viewer`. There is always at least one active admin.
- **Sessions**: the session is a cookie `formkiln_session` (`HttpOnly`, `SameSite=Strict`, path `/v1`). It
  expires after 30 minutes idle or 12 hours. Disabling a member, resetting or changing a password, or resetting
  an authenticator ends their other sessions.
- **CSRF**: every unsafe `/v1/admin/**` request, and every unsafe request authenticated by the cookie, must
  send `X-Requested-With: formkiln`.
- **Lockout**: 5 wrong passwords lock the account for 15 minutes, doubling up to 24 hours. Failures always
  return the same `401 loginFailed`.
- **Storage**: passwords use Argon2id; TOTP secrets are AES-256-GCM encrypted. Invitation, session, challenge,
  reset tokens and recovery codes are stored only as SHA-256 hashes.

| Method | Path | Who | Description |
| --- | --- | --- | --- |
| `POST` | `/v1/admin/invitations/inspect` | link holder | `{ token }`. Returns `{ email, role, expiresAt, totp: { secret, otpauthUri } }` |
| `POST` | `/v1/admin/invitations/accept` | link holder | `{ token, password, code }`. Creates the account and signs in. Returns `{ user, recoveryCodes }` (shown once) |
| `POST` | `/v1/admin/auth/login` | anyone | `{ email, password }` → `{ challenge, enrollTotp? }` (`enrollTotp` after an authenticator reset) |
| `POST` | `/v1/admin/auth/mfa` | anyone | `{ challenge, code }` or `{ challenge, recoveryCode }`. Sets the session cookie, returns `{ user, recoveryCodes? }` |
| `POST` | `/v1/admin/auth/password/forgot` | anyone | `{ email }` → `202`, whether or not the email exists |
| `POST` | `/v1/admin/auth/password/reset` | link holder | `{ token, password }` |
| `GET` | `/v1/admin/auth/me` | member | The signed-in member |
| `POST` | `/v1/admin/auth/logout` | member | Ends the session |
| `POST` | `/v1/admin/me/password` | member | `{ currentPassword, newPassword }` |
| `POST` | `/v1/admin/me/recovery-codes` | member | `{ code }` → new recovery codes |
| `GET` / `DELETE` | `/v1/admin/me/sessions[/{id}]` | member | List or end own sessions |
| `GET` / `PATCH` | `/v1/admin/members[/{id}]` | admin | List members; change `role` or `status` (`active`, `disabled`) |
| `POST` | `/v1/admin/members/{id}/reset-totp` | admin | The member enrolls a new authenticator at next login |
| `GET` / `POST` | `/v1/admin/invitations` | admin | List open invitations; invite `{ email, role }` |
| `POST` / `DELETE` | `/v1/admin/invitations/{id}[/resend]` | admin | Resend (new link) or revoke |
| `GET` | `/v1/admin/audit?limit=100&before=<at>` | admin | Audit log, newest first |

The public endpoints are rate-limited per IP. Problem details carry a stable `code` (`loginFailed`,
`invalidToken`, `weakPassword`, `invalidCode`, `lastAdmin`, …).

## Contributing

Please read [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
To report a security issue, see [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE)
