# Changelog

All notable changes to formkiln-server. Versions follow [semver](https://semver.org); before 1.0,
minor versions may contain breaking changes, which are always listed here. Database migrations run
automatically on start and only move forward: back up before upgrading (see
[docs/operations.md](docs/operations.md#upgrades)).

The server, admin and hosted images share version numbers: run the same version of all three.

## 0.1.0 (2026-11-27)

First release.

### Forms

- Publish [Form Schema v1](https://github.com/liuyfly/formkiln/tree/main/packages/spec) documents
  as immutable versions, including multi-column layouts (`width`) and stable element ids.
  Definitions are validated with the same rules as `@formkiln/core` (shared conformance suite;
  the server build takes the specification from a pinned commit of the formkiln repository).
- Renaming a field keeps its history: CSV export columns, compatibility checks and the
  confirmation-email field follow the element id.
- Working copies for the builder: autosave with optimistic concurrency, compatibility check
  against the published version, publish and roll back, create from a template or a copy.
- Expression evaluator with the same semantics as the TypeScript engine.
- Folders (one level) and archiving: an archived form is hidden and no longer served, with all
  its versions and submissions kept until it is restored.

### Submissions

- Validated again on the server at the time of receipt; outcomes and redirects.
- Sensitive fields encrypted at rest (AES-GCM), masked unless revealed by an editor (audited).
- Drafts with "continue later" links, file uploads (filesystem or S3), embed tokens for
  signed links, Cloudflare Turnstile and a honeypot for public forms, per-IP rate limits.
- Webhooks with an outbox, HMAC signatures, retries and a delivery log.
- Email notifications to the team and confirmation emails to respondents.
- CSV export, PDF per submission and a PDF receipt for the respondent.
- Retention periods, deleting submissions and data-subject erasure requests with a dry run.

### Workspaces

- Teams work in separate workspaces: forms, folders, submissions, members and audit logs are kept
  apart, and URLs name the workspace (`/v1/w/{workspace}/forms/{key}`,
  `/v1/manage/workspaces/{workspace}/...`). Form keys are unique per workspace.
- Roles per workspace; outsiders get 404. Instance admins manage workspaces and accounts without
  seeing form data, except through an audited one-hour read-only window opened with a reason.
  Archived workspaces are read-only and stop serving their forms.
- PostgreSQL row-level security keeps workspaces apart under the server's checks
  (`FORMKILN_RLS`, on by default; role `formkiln_app`, see the operations guide).

### Accounts and security

- Invitation-only admin accounts: password (Argon2id) plus mandatory TOTP, recovery codes,
  password reset, session management, lockout after repeated failures. Each authenticator code
  is accepted once.
- Roles (admin, editor, viewer) per workspace, and an audit log.
- Service tokens per workspace (editor or viewer, optional expiry, revocable), shown once and stored
  hashed; the audit log names the token. `FORMKILN_SERVICE_TOKEN` acts in the `default` workspace.

### Operations

- Docker image `ghcr.io/liuyfly/formkiln-server`; health probes; Prometheus metrics behind
  `FORMKILN_METRICS_TOKEN`; version in `/actuator/info`.
- [Operations guide](docs/operations.md): backup and restore, upgrades, alert rules,
  troubleshooting.
