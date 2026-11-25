# Changelog

All notable changes to formkiln-server. Versions follow [semver](https://semver.org); before 1.0,
minor versions may contain breaking changes, which are always listed here. Database migrations run
automatically on start and only move forward: back up before upgrading (see
[docs/operations.md](docs/operations.md#upgrades)).

The server, admin and hosted images share version numbers: run the same version of all three.

## 0.1.0

First release.

### Forms

- Publish [Form Schema v1](https://github.com/liuyfly/formkiln/tree/main/packages/spec) documents
  as immutable versions; definitions are validated with the same rules as `@formkiln/core`
  (shared conformance suite, `@formkiln/spec` 0.4.0).
- Working copies for the builder: autosave with optimistic concurrency, compatibility check
  against the published version, publish and roll back, create from a template or a copy.
- Expression evaluator with the same semantics as the TypeScript engine.

### Submissions

- Validated again on the server at the time of receipt; outcomes and redirects.
- Sensitive fields encrypted at rest (AES-GCM), masked unless revealed by an editor (audited).
- Drafts with "continue later" links, file uploads (filesystem or S3), embed tokens for
  signed links, Cloudflare Turnstile and a honeypot for public forms, per-IP rate limits.
- Webhooks with an outbox, HMAC signatures, retries and a delivery log.
- Email notifications to the team and confirmation emails to respondents.
- CSV export, PDF per submission and a PDF receipt for the respondent.
- Retention periods, deleting submissions and data-subject erasure requests with a dry run.

### Accounts and security

- Invitation-only admin accounts: password (Argon2id) plus mandatory TOTP, recovery codes,
  password reset, session management, lockout after repeated failures. Each authenticator code
  is accepted once.
- Roles (admin, editor, viewer), a service token for automation, and an audit log.

### Operations

- Docker image `ghcr.io/liuyfly/formkiln-server`; health probes; Prometheus metrics behind
  `FORMKILN_METRICS_TOKEN`; version in `/actuator/info`.
- [Operations guide](docs/operations.md): backup and restore, upgrades, alert rules,
  troubleshooting.
