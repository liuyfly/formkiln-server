-- Runtime platform: access modes, drafts, files, webhooks (Phase 2, step 4).

ALTER TABLE form
    ADD COLUMN access_mode varchar(16) NOT NULL DEFAULT 'public' CHECK (access_mode IN ('public', 'token'));

-- The embed-token subject that submitted (token-mode forms only).
ALTER TABLE submission ADD COLUMN subject varchar(256);

CREATE TABLE form_draft (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id     uuid NOT NULL REFERENCES form (id),
    token_hash  bytea NOT NULL UNIQUE,
    -- AES-256-GCM encrypted JSON answers (drafts may hold sensitive values).
    data        bytea NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now(),
    expires_at  timestamptz NOT NULL
);

CREATE INDEX form_draft_expires ON form_draft (expires_at);

CREATE TABLE file_object (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id        uuid NOT NULL REFERENCES form (id),
    field_key      varchar(64) NOT NULL,
    name           varchar(255) NOT NULL,
    size           bigint NOT NULL CHECK (size >= 0),
    content_type   varchar(255) NOT NULL,
    storage_key    varchar(512) NOT NULL UNIQUE,
    created_at     timestamptz NOT NULL DEFAULT now(),
    -- NULL until a submission references the file; unreferenced files are removed after a day.
    submission_id  uuid REFERENCES submission (id)
);

CREATE INDEX file_object_unattached ON file_object (created_at) WHERE submission_id IS NULL;

CREATE TABLE webhook_endpoint (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id     uuid NOT NULL REFERENCES form (id),
    url         varchar(2048) NOT NULL,
    -- AES-256-GCM encrypted signing secret.
    secret      bytea NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    deleted_at  timestamptz
);

CREATE TABLE webhook_delivery (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint_id      uuid NOT NULL REFERENCES webhook_endpoint (id),
    submission_id    uuid NOT NULL REFERENCES submission (id),
    event            varchar(64) NOT NULL,
    -- AES-256-GCM encrypted JSON payload (contains submission data, including sensitive values).
    payload          bytea NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'succeeded', 'dead')),
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_status      integer,
    last_error       varchar(512),
    created_at       timestamptz NOT NULL DEFAULT now(),
    delivered_at     timestamptz
);

CREATE INDEX webhook_delivery_due ON webhook_delivery (next_attempt_at) WHERE status = 'pending';
CREATE INDEX webhook_delivery_endpoint ON webhook_delivery (endpoint_id, created_at DESC);
