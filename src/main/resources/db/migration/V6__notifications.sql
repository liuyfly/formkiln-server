-- Email notifications: per-form settings and an outbox of messages sent by a background job.

CREATE TABLE form_notification_settings (
    form_id                   uuid PRIMARY KEY REFERENCES form (id),
    -- Team notification on every submission.
    team_recipients           jsonb NOT NULL DEFAULT '[]',
    team_include_answers      boolean NOT NULL DEFAULT false,
    -- Confirmation to the person who submitted, at the address in an email field.
    confirmation_enabled      boolean NOT NULL DEFAULT false,
    confirmation_email_field  varchar(64),
    confirmation_subject      text,
    confirmation_body         text,
    -- "Email me a link to continue later" on hosted forms.
    resume_enabled            boolean NOT NULL DEFAULT false,
    updated_at                timestamptz NOT NULL DEFAULT now()
);

-- Messages are encrypted at rest ({to, subject, body}); recipient_hint is a masked address for the log.
CREATE TABLE mail_outbox (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    form_id          uuid REFERENCES form (id),
    kind             varchar(16) NOT NULL CHECK (kind IN ('team', 'confirmation', 'resume')),
    submission_id    uuid,
    draft_id         uuid,
    recipient_hint   varchar(254) NOT NULL,
    payload          bytea NOT NULL,
    status           varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'dead')),
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       varchar(512),
    created_at       timestamptz NOT NULL DEFAULT now(),
    sent_at          timestamptz
);

CREATE INDEX mail_outbox_due ON mail_outbox (next_attempt_at) WHERE status = 'pending';
CREATE INDEX mail_outbox_form ON mail_outbox (form_id, created_at DESC);
CREATE INDEX mail_outbox_draft ON mail_outbox (draft_id, created_at) WHERE draft_id IS NOT NULL;
