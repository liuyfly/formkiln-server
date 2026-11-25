-- Forms, immutable published versions and submissions (Phase 2, step 1).

CREATE TABLE form (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    key         varchar(64) NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE form_version (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id         uuid NOT NULL REFERENCES form (id),
    version         integer NOT NULL CHECK (version > 0),
    schema_version  integer NOT NULL,
    definition      jsonb NOT NULL,
    published_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (form_id, version)
);

CREATE TABLE submission (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_version_id  uuid NOT NULL REFERENCES form_version (id),
    data             jsonb NOT NULL,
    outcome          varchar(64),
    submitted_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX submission_form_version_submitted_at ON submission (form_version_id, submitted_at DESC);
