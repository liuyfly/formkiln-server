# Operations

How to run Formkiln in production: what to deploy, what to back up, how to upgrade and what to
watch. The Docker Compose setup in the
[formkiln repository](https://github.com/liuyfly/formkiln/tree/main/deploy) is the reference
deployment; the same applies to Kubernetes or any other runtime.

## Components

| Component | Image | State |
| --- | --- | --- |
| API server | `ghcr.io/liuyfly/formkiln-server` | Stateless apart from file storage |
| Admin app | `ghcr.io/liuyfly/formkiln-admin` | Stateless |
| Hosted fill-in site | `ghcr.io/liuyfly/formkiln-hosted` | Stateless |
| PostgreSQL 15+ | `postgres:17-alpine` | Forms, submissions, accounts, outboxes, audit log |
| File storage | Volume (`FORMKILN_STORAGE_PATH`) or S3 | Uploaded files |

- Expose only the admin and hosted apps, behind an HTTPS reverse proxy that appends the client
  address to `X-Forwarded-For`. Keep the API port private; both apps reach it over the internal
  network (`FORMKILN_API_URL`).
- Several API server instances can share one database: webhook and email outboxes are claimed with
  `FOR UPDATE SKIP LOCKED` leases, and the hourly clean-up jobs are idempotent. Rate limits are
  counted per instance.
- Required settings: `FORMKILN_ENCRYPTION_KEY`, the database connection and `FORMKILN_ADMIN_URL`.
  See [Configuration](../README.md#2-configuration) for the rest.

## Backup and restore

Three things together make a complete backup. Losing any one of them loses data:

1. **The PostgreSQL database.**
2. **Uploaded files** (the `files` volume, or the S3 bucket).
3. **`FORMKILN_ENCRYPTION_KEY`.** Sensitive answers, TOTP secrets and queued emails are encrypted
   with it. A database restored without the key cannot reveal sensitive fields or sign anyone in
   with two-factor authentication. Keep it in a secrets manager, separately from the backups.

### Database

```sh
# Consistent online backup (custom format, compressed).
docker compose exec -T postgres pg_dump -U formkiln -d formkiln -Fc > formkiln-$(date +%F).dump

# Restore into an empty database (stop the server first).
docker compose stop server
docker compose exec -T postgres dropdb -U formkiln formkiln
docker compose exec -T postgres createdb -U formkiln formkiln
docker compose exec -T postgres pg_restore -U formkiln -d formkiln --no-owner < formkiln-2026-01-31.dump
docker compose start server
```

For point-in-time recovery, use a managed PostgreSQL service or WAL archiving (for example
pgBackRest) instead of nightly dumps.

### Files

```sh
# Filesystem storage: archive the volume.
docker run --rm -v formkiln_files:/data -v "$PWD":/backup alpine tar czf /backup/files-$(date +%F).tgz -C /data .

# Restore.
docker run --rm -v formkiln_files:/data -v "$PWD":/backup alpine tar xzf /backup/files-2026-01-31.tgz -C /data
```

With S3, enable bucket versioning or replication. Take the file backup after the database dump:
a file without a database row is removed by the hourly clean-up, a row without its file cannot be
downloaded.

### Test the restore

Restore into a scratch environment regularly, sign in to the admin app, open a submission with
sensitive fields and reveal them, and download one of its files.

## Upgrades

1. Read the release notes for breaking changes.
2. Back up the database.
3. Pull the new images (`FORMKILN_VERSION=0.2.0 docker compose pull`) and restart
   (`docker compose up -d`).

Database migrations run automatically with Flyway when the server starts; the first instance to
start takes a lock and applies them, the others wait. Migrations only move forward: to roll back,
restore the backup taken in step 2 and start the previous version. Upgrade the server, admin and
hosted images to the same version.

## Monitoring

### Health

| Endpoint | Use |
| --- | --- |
| `GET /actuator/health/liveness` | Liveness probe: the process is running |
| `GET /actuator/health/readiness` | Readiness probe: started and ready for traffic |
| `GET /actuator/health` | Overall status, including the database |
| `GET /actuator/info` | Server version (`build.version`) |

An SMTP outage does not mark the server unhealthy: emails stay queued and are retried.

### Metrics

Set `FORMKILN_METRICS_TOKEN` to expose Prometheus metrics at `GET /actuator/prometheus`
(`Authorization: Bearer <token>`). The endpoint is closed when the token is not set, and the token
grants nothing else.

```yaml
# prometheus.yml
scrape_configs:
  - job_name: formkiln
    metrics_path: /actuator/prometheus
    authorization:
      credentials_file: /etc/prometheus/formkiln-metrics-token
    static_configs:
      - targets: ["server:8080"]
```

| Metric | Labels | Meaning |
| --- | --- | --- |
| `formkiln_submissions_total` | `form`, `result` | Submissions received: `accepted`, `invalid`, `captcha_failed`, `honeypot` |
| `formkiln_validation_errors_total` | `form`, `field`, `code` | Answers rejected on submission, by top-level field and error code (`required`, `format.email`...) |
| `formkiln_webhook_deliveries_total` | `result` | Webhook attempts: `succeeded`, `failed` (will retry), `dead` (gave up) |
| `formkiln_webhook_pending` | | Webhook deliveries waiting to be sent |
| `formkiln_mail_total` | `kind`, `result` | Emails: `sent`, `failed` (will retry), `dead` (gave up) |
| `formkiln_mail_pending` | | Emails waiting to be sent |
| `formkiln_logins_total` | `result` | Sign-ins: `succeeded`, `failed`, `locked`, `mfa_failed` |

Spring Boot also exports the standard JVM, HTTP server (`http_server_requests_seconds`), Hikari
connection pool and process metrics.

Validation errors that pass client-side checks are unusual: a field with a steady stream of them
usually means the form's rules and its instructions disagree, or an outdated embed.

### Alert rules

```yaml
groups:
  - name: formkiln
    rules:
      - alert: FormkilnDown
        expr: up{job="formkiln"} == 0
        for: 2m
      - alert: FormkilnServerErrors
        expr: sum(rate(http_server_requests_seconds_count{job="formkiln",status=~"5.."}[5m])) > 0.1
        for: 10m
      - alert: FormkilnWebhooksDead
        expr: increase(formkiln_webhook_deliveries_total{result="dead"}[1h]) > 0
        annotations:
          summary: A webhook endpoint stopped accepting deliveries; check the form's Webhooks tab in the admin app.
      - alert: FormkilnWebhookBacklog
        expr: formkiln_webhook_pending > 100
        for: 30m
      - alert: FormkilnMailFailing
        expr: increase(formkiln_mail_total{result="dead"}[1h]) > 0 or formkiln_mail_pending > 50
        for: 15m
        annotations:
          summary: Emails are not going out; check the SMTP settings and the server log.
      - alert: FormkilnSignInAttack
        expr: sum(increase(formkiln_logins_total{result=~"failed|locked|mfa_failed"}[15m])) > 50
      - alert: FormkilnCaptchaFailures
        expr: sum by (form) (increase(formkiln_submissions_total{result="captcha_failed"}[1h])) > 100
      - alert: FormkilnDatabasePoolExhausted
        expr: hikaricp_connections_pending{job="formkiln"} > 0
        for: 5m
```

## Background jobs

| Job | Interval | What it does |
| --- | --- | --- |
| Webhook delivery | `FORMKILN_WEBHOOK_POLL_INTERVAL` (5 s) | Sends queued deliveries; retries with exponential back-off, then marks them dead |
| Email delivery | `FORMKILN_MAIL_POLL_INTERVAL` (5 s) | Sends queued notifications; retries, then marks them dead |
| Retention | Hourly | Deletes submissions older than each form's retention period |
| Drafts | Hourly | Deletes expired drafts |
| Files | Hourly | Deletes uploads that were never attached to a submission |

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Server does not start: `FORMKILN_ENCRYPTION_KEY is not set` / `must be Base64` | It must be 32 random bytes, Base64 (`openssl rand -base64 32`) |
| Server does not start: Flyway validation failed | The database was migrated by a newer version. Run that version, or restore the backup |
| Every request is rate-limited | The proxy does not append the client address to `X-Forwarded-For`, so all clients share one IP |
| Admin sign-in loops back to the sign-in page | Serve the admin app over HTTPS, or set `FORMKILN_COOKIE_SECURE=false` for plain-HTTP testing |
| Emails are not delivered | `formkiln_mail_pending` and the server log (`Mail ... attempt ... failed`); SMTP host, port and credentials |
| Webhooks are not received | The form's Webhooks tab in the admin app (delivery log); private and `http://` URLs are refused unless allowed |
| Hosted form not shown in an iframe | Add the embedding site to `FORMKILN_FRAME_ANCESTORS` |
| "Continue later" links point to localhost | Set `FORMKILN_HOSTED_URL` on the server |

Server logs go to standard output. Answers and secrets are never logged; the audit log in the
admin app records sign-ins, member changes, exports, reveals and deletions.
