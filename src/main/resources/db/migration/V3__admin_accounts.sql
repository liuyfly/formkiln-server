-- Admin accounts: invitation-only, email + password (Argon2id) + mandatory TOTP, server-side sessions.
-- Every token (invitation, session, challenge, reset) is stored only as a SHA-256 hash.

CREATE TABLE admin_user (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email                varchar(254) NOT NULL UNIQUE CHECK (email = lower(email)),
    password_hash        text NOT NULL,
    -- AES-256-GCM encrypted TOTP secret; NULL after an administrator reset (re-enrolled at next login).
    totp_secret          bytea,
    role                 varchar(16) NOT NULL CHECK (role IN ('admin', 'editor', 'viewer')),
    status               varchar(16) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled')),
    failed_logins        integer NOT NULL DEFAULT 0,
    locked_until         timestamptz,
    last_login_at        timestamptz,
    password_changed_at  timestamptz NOT NULL DEFAULT now(),
    created_at           timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE admin_invitation (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email        varchar(254) NOT NULL CHECK (email = lower(email)),
    role         varchar(16) NOT NULL CHECK (role IN ('admin', 'editor', 'viewer')),
    token_hash   bytea NOT NULL UNIQUE,
    -- Encrypted TOTP secret offered during enrollment, so the QR code is stable across reloads.
    totp_secret  bytea,
    invited_by   uuid REFERENCES admin_user (id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    expires_at   timestamptz NOT NULL,
    accepted_at  timestamptz,
    revoked_at   timestamptz
);

-- At most one open invitation per email.
CREATE UNIQUE INDEX admin_invitation_open_email ON admin_invitation (email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE TABLE admin_recovery_code (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES admin_user (id),
    code_hash  bytea NOT NULL,
    used_at    timestamptz
);

CREATE INDEX admin_recovery_code_user ON admin_recovery_code (user_id);

CREATE TABLE admin_session (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash    bytea NOT NULL UNIQUE,
    user_id       uuid NOT NULL REFERENCES admin_user (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_seen_at  timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    revoked_at    timestamptz,
    ip            varchar(64),
    user_agent    varchar(256)
);

CREATE INDEX admin_session_user ON admin_session (user_id);

-- Second step of a login: the password was correct, a TOTP or recovery code is still required.
CREATE TABLE admin_login_challenge (
    token_hash   bytea PRIMARY KEY,
    user_id      uuid NOT NULL REFERENCES admin_user (id),
    -- Set when the user must enroll TOTP again (after an administrator reset).
    totp_secret  bytea,
    attempts     integer NOT NULL DEFAULT 0,
    expires_at   timestamptz NOT NULL
);

CREATE TABLE admin_password_reset (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES admin_user (id),
    token_hash  bytea NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL,
    used_at     timestamptz
);

CREATE TABLE audit_log (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    at          timestamptz NOT NULL DEFAULT now(),
    actor_type  varchar(16) NOT NULL CHECK (actor_type IN ('user', 'service', 'system', 'anonymous')),
    actor_id    uuid,
    action      varchar(64) NOT NULL,
    target      varchar(254),
    ip          varchar(64),
    details     jsonb
);

CREATE INDEX audit_log_at ON audit_log (at DESC);
