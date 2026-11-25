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
| `FORMKILN_ENCRYPTION_KEY` | _(required)_ | 32 random bytes, Base64 (`openssl rand -base64 32`). Encrypts secrets at rest (TOTP, sensitive answers, drafts, webhook secrets and payloads) and signs embed tokens. Keep it safe: losing it locks out every member and makes encrypted data unreadable. |
| `FORMKILN_SERVICE_TOKEN` | _(empty)_ | Bearer token for automation on the management API (acts as an editor). Disabled when empty. |
| `FORMKILN_BOOTSTRAP_ADMIN_EMAIL` | _(empty)_ | Invited as the first admin when there are no members yet |
| `FORMKILN_ADMIN_URL` | `http://localhost:3200` | Base URL of the admin app, used in invitation and reset links |
| `FORMKILN_SMTP_HOST` / `FORMKILN_SMTP_PORT` | `localhost` / `1025` | SMTP server (Mailpit in development). For authenticated SMTP set `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` and `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true`. |
| `FORMKILN_MAIL_FROM` | `Formkiln <no-reply@localhost>` | Sender of account emails |
| `FORMKILN_COOKIE_SECURE` | `true` | `Secure` flag of the session cookie (browsers accept it on `http://localhost`) |
| `FORMKILN_AUTH_RATE_LIMIT` | `20` | Requests per minute and client IP to the public authentication endpoints |
| `FORMKILN_RUNTIME_RATE_LIMIT` | `60` | Requests per minute and client IP to runtime writes (submissions, drafts, uploads) |
| `FORMKILN_MAX_FILE_SIZE` | `10MB` | Largest upload; a field's `maxFileSize` can only lower it |
| `FORMKILN_STORAGE` | `filesystem` | `filesystem` or `s3` (see [File storage](#file-storage)) |
| `FORMKILN_TURNSTILE_SITE_KEY` / `FORMKILN_TURNSTILE_SECRET` | _(empty)_ | Cloudflare Turnstile for public forms. Disabled when empty. |

Environment variables always take precedence over `.env`. Behind a reverse proxy, the server
trusts `X-Forwarded-For` from private-network proxies (`server.forward-headers-strategy: native`).

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

Images are published to `ghcr.io/liuyfly/formkiln-server`: `edge` follows `main`, and `v*` tags publish `1.2.3`, `1.2` and `latest`. To run the whole platform (server, admin, hosted), see the [self-hosting guide](https://github.com/liuyfly/formkiln/blob/main/deploy/README.md).

## HTTP API

Errors use [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem details. Validation problems (422) carry an
`errors` array.

### Runtime (public, or with an embed token)

A form's access mode is `public` (default) or `token`. Token forms require
`Authorization: Bearer <embed token>` on every runtime request (`401 embedTokenRequired` otherwise).
Writes are rate-limited per client IP (`FORMKILN_RUNTIME_RATE_LIMIT`, default 60/min).

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/forms/{key}` | Latest published version: `{ key, version, publishedAt, accessMode, captcha, definition }`. `captcha` is `{ provider, siteKey }` when human verification is required, otherwise `null`. |
| `POST` | `/v1/forms/{key}/submissions` | Body `{ "version"?, "answers", "locale"?, "draftToken"?, "captchaToken"?, "honeypot"? }`. The server re-validates the answers against that version (default: latest) at the time of receipt. Returns `201 { id, version, outcome, submittedAt }`, or `422` with `[{ path, code, message?, params? }]`. Only the submission data (active, non-null values) is stored; `sensitive` fields are encrypted at rest. A non-empty `honeypot` is silently dropped. Public forms need a valid `captchaToken` when Turnstile is configured (`400 captchaFailed`). The draft named by `draftToken` is deleted. |
| `POST` | `/v1/forms/{key}/drafts` | `{ answers }` → `201 { token, savedAt, expiresAt }`. Drafts are encrypted, expire after 30 days and hold at most 256 KB. |
| `GET` / `PUT` / `DELETE` | `/v1/forms/{key}/drafts` | Read, replace (`{ answers }`) or delete the draft named by the `Formkiln-Draft: <token>` header |
| `POST` | `/v1/forms/{key}/files` | Multipart `field`, `file` → `201 { id, name, size, contentType }`: the reference to put in the file field's answer. Checked against the field's `accept` / `fileTypes` (`415`) and `maxFileSize` and `FORMKILN_MAX_FILE_SIZE` (`413`). Files not claimed by a submission within a day are removed; a file can be claimed once. |

### Management (signed-in member, or `Authorization: Bearer $FORMKILN_SERVICE_TOKEN`)

Viewers can read; editors (and the service token) can also write.

| Method | Path | Description |
| --- | --- | --- |
| `GET` | `/v1/manage/forms` | Forms with their latest version (`null` until published), access mode, submission count and `unpublishedChanges` |
| `POST` | `/v1/manage/forms/{key}/versions` | Publish the form document in the body as a new immutable version. It is validated against the JSON Schema (`code: "schema"`) and the static rules of Form Schema §10. The document's `key` must equal `{key}` (`keyMismatch`). |
| `GET` | `/v1/manage/forms/{key}/versions/{version}` | One version with its definition: `{ key, version, publishedAt, definition }` |
| `GET` | `/v1/manage/forms/{key}/versions` | Published versions, newest first |
| `GET` | `/v1/manage/forms/{key}/submissions?limit=50&before=<submittedAt>&beforeId=<id>&reveal=false` | Submissions, newest first. For the next page pass the `submittedAt` and `id` of the last row (`beforeId` keeps rows that share a time from being skipped). Sensitive values are masked (`••••`); `reveal=true` (editors only) shows them and is audited. |
| `GET` | `/v1/manage/forms/{key}/submissions/export?reveal=false` | All submissions as CSV (UTF-8 with BOM), newest first: `id, version, outcome, submittedAt, subject`, then the answer keys of the latest version and keys only older versions have. File fields list file names; repeaters and composite values are JSON. Text that a spreadsheet would run as a formula is prefixed with `'`. Sensitive values are masked unless `reveal=true` (editors only). Audited. |
| `GET` | `/v1/manage/forms/{key}/files/{id}` | Download a submitted file (audited) |
| `PUT` | `/v1/manage/forms/{key}/access` | `{ mode: "public" \| "token" }` |
| `POST` | `/v1/manage/embed-tokens` | `{ formKey, subject?, ttlSeconds? }` → `{ token, expiresAt }`. Call it from your backend and pass the token to the embedding page. Default lifetime 15 minutes, at most 1 hour. `subject` (for example your user id) is stored with the submission. |
| `GET` / `POST` | `/v1/manage/forms/{key}/webhooks` | List webhooks; create one with `{ url }` (https only). The signing secret is returned once. |
| `DELETE` | `/v1/manage/forms/{key}/webhooks/{id}` | Delete a webhook (pending deliveries are dropped) |
| `GET` | `/v1/manage/forms/{key}/webhooks/{id}/deliveries` | Recent deliveries with status, attempts and last error |
| `POST` | `/v1/manage/forms/{key}/webhook-deliveries/{id}/redeliver` | Queue a delivery again |

Management changes are written to the audit log with the member (or `service`) and client IP:
`form.published`, `form.access_changed`, `embed_token.issued` (by members only; backends issue one
per end user), `webhook.created`, `webhook.deleted`, `webhook.redelivered`, as well as
`submissions.revealed`, `submissions.exported` and `file.downloaded`.

### Working copies (builder)

A form can be created and edited before it is published. Each form has at most one working copy:
the document being edited. Saves are checked with optimistic concurrency: the client sends the
`revision` it edited, and a save over any other revision is rejected with `409 revisionConflict`
(with `currentRevision`, `updatedBy`, `updatedAt`). Revisions never repeat. A working copy may be
invalid while it is edited; its `problems` are returned with every read and save.

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/v1/manage/forms` | `{ key, title, document?, source? }` → `201`: an unpublished form. Its working copy is `document` (a template or a copy of another form, with the new key and, if given, title) or a one-page skeleton. `source` (for example `template:contact`) is recorded in the audit log. `409 formExists`, `400 invalidKey`. Not served at runtime until published. |
| `GET` | `/v1/manage/forms/{key}/working-copy` | `{ key, revision, baseVersion, latestVersion, document, updatedAt, updatedBy, problems }`. Without a saved copy, the latest published definition with `revision: 0`. |
| `PUT` | `/v1/manage/forms/{key}/working-copy` | `{ revision, document }` (`revision: 0` for the first save) → the saved copy with its new revision. Documents up to 1 MB. |
| `DELETE` | `/v1/manage/forms/{key}/working-copy` | Discard the copy; editing starts again from the published version (`409 neverPublished` for unpublished forms). |
| `GET` | `/v1/manage/forms/{key}/working-copy/check` | `{ problems, changes }`. `changes` compare a valid copy with the published version: `fieldRemoved`, `typeChanged` (`from`, `to`) and `optionsRemoved` (`values`), by answer path (`key` or `repeater.child`). |
| `POST` | `/v1/manage/forms/{key}/working-copy/publish` | `{ revision, acknowledgeChanges }` → `201 { key, version, publishedAt }` and the copy is removed. `422` with `errors` when invalid; `409 changesNotAcknowledged` with `changes` until they are acknowledged. |

Audited: `form.created`, `form.published` (with `source: "workingCopy"`) and `working_copy.discarded`.

### Webhooks

Each submission is queued (in the same transaction) for every webhook of its form and sent as
`POST` with a JSON body:

```json
{ "event": "submission.created",
  "form": { "key": "contact", "version": 3 },
  "submission": { "id": "...", "outcome": "done", "submittedAt": "...", "subject": null, "data": { } } }
```

Headers: `Formkiln-Event`, `Formkiln-Delivery` (unique id, use it to deduplicate) and
`Formkiln-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "<t>.<raw body>" with the secret>`.
Verify the signature and reject old timestamps. Any `2xx` is success; otherwise the delivery is
retried with exponential backoff (30 s doubling, 8 attempts). Redirects are not followed, and
private or loopback destinations are refused (for development:
`FORMKILN_WEBHOOKS_ALLOW_HTTP=true`, `FORMKILN_WEBHOOKS_ALLOW_PRIVATE_NETWORKS=true`).

### File storage

Uploaded files are stored under `FORMKILN_STORAGE_PATH` (default `./data/files`) or, with
`FORMKILN_STORAGE=s3`, in an S3-compatible bucket (`FORMKILN_S3_BUCKET`, `FORMKILN_S3_REGION`,
`FORMKILN_S3_ENDPOINT` and `FORMKILN_S3_PATH_STYLE` for MinIO or R2, and optionally
`FORMKILN_S3_ACCESS_KEY` / `FORMKILN_S3_SECRET_KEY`; otherwise the default AWS credentials chain).

### Client IP

Per-IP limits and Turnstile use the client address. Behind a reverse proxy, the server trusts
`X-Forwarded-For` only from internal (private network) proxies.

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
