-- Service tokens: bearer tokens for automation, each bound to one workspace with a role (editor or
-- viewer). Only the SHA-256 of the token is stored; it is shown once when created.
CREATE TABLE service_token (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    name          varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    token_hash    bytea NOT NULL UNIQUE,
    role          varchar(16) NOT NULL CHECK (role IN ('editor', 'viewer')),
    created_by    uuid REFERENCES admin_user (id),
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    expires_at    timestamptz,
    revoked_at    timestamptz
);

CREATE INDEX service_token_workspace ON service_token (workspace_id, created_at DESC);
