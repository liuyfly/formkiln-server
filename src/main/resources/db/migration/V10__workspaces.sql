-- Workspaces: one instance serves several teams, each in its own workspace. Every row holding form
-- data carries the workspace of its form (denormalized, so that row-level security can later be a
-- plain column comparison). Composite foreign keys make it impossible for a row to point at a form
-- of another workspace. Existing data moves into the "default" workspace; members keep their role
-- there, and admins also become instance admins.

CREATE TABLE workspace (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Part of every URL, so it never changes; the name can.
    slug         varchar(48) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9-]{0,46}[a-z0-9]$'),
    name         varchar(120) NOT NULL CHECK (length(btrim(name)) > 0),
    created_at   timestamptz NOT NULL DEFAULT now(),
    archived_at  timestamptz
);

INSERT INTO workspace (slug, name) VALUES ('default', 'Default');

CREATE TABLE workspace_member (
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    user_id       uuid NOT NULL REFERENCES admin_user (id),
    role          varchar(16) NOT NULL CHECK (role IN ('admin', 'editor', 'viewer')),
    added_at      timestamptz NOT NULL DEFAULT now(),
    added_by      uuid REFERENCES admin_user (id),
    PRIMARY KEY (workspace_id, user_id)
);

CREATE INDEX workspace_member_user ON workspace_member (user_id);

ALTER TABLE admin_user ADD COLUMN instance_admin boolean NOT NULL DEFAULT false;

INSERT INTO workspace_member (workspace_id, user_id, role, added_at)
    SELECT w.id, u.id, u.role, u.created_at FROM admin_user u, workspace w WHERE w.slug = 'default';
UPDATE admin_user SET instance_admin = true WHERE role = 'admin';

-- The workspace an invitation adds its account to (NULL: an instance admin invitation).
ALTER TABLE admin_invitation ADD COLUMN workspace_id uuid REFERENCES workspace (id);
UPDATE admin_invitation SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default');

-- Forms and folders.
ALTER TABLE form ADD COLUMN workspace_id uuid REFERENCES workspace (id);
UPDATE form SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default');
ALTER TABLE form ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form ADD CONSTRAINT form_id_workspace UNIQUE (id, workspace_id);
-- Keys are unique within a workspace, so teams name forms without coordinating.
ALTER TABLE form DROP CONSTRAINT form_key_key;
ALTER TABLE form ADD CONSTRAINT form_workspace_key UNIQUE (workspace_id, key);

ALTER TABLE form_folder ADD COLUMN workspace_id uuid REFERENCES workspace (id);
UPDATE form_folder SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default');
ALTER TABLE form_folder ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form_folder ADD CONSTRAINT form_folder_id_workspace UNIQUE (id, workspace_id);
DROP INDEX form_folder_name;
CREATE UNIQUE INDEX form_folder_name ON form_folder (workspace_id, lower(name));

-- A form's folder is in the form's workspace.
ALTER TABLE form DROP CONSTRAINT form_folder_id_fkey;
ALTER TABLE form ADD CONSTRAINT form_folder_ws
    FOREIGN KEY (folder_id, workspace_id) REFERENCES form_folder (id, workspace_id);
CREATE INDEX form_workspace_created ON form (workspace_id, created_at);

-- Tables that belong to a form: copy the form's workspace, then tie the pair to the form.
ALTER TABLE form_version ADD COLUMN workspace_id uuid;
UPDATE form_version t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE form_version ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form_version ADD CONSTRAINT form_version_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);
ALTER TABLE form_version ADD CONSTRAINT form_version_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE form_draft ADD COLUMN workspace_id uuid;
UPDATE form_draft t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE form_draft ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form_draft ADD CONSTRAINT form_draft_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);

ALTER TABLE file_object ADD COLUMN workspace_id uuid;
UPDATE file_object t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE file_object ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE file_object ADD CONSTRAINT file_object_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);

ALTER TABLE webhook_endpoint ADD COLUMN workspace_id uuid;
UPDATE webhook_endpoint t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE webhook_endpoint ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE webhook_endpoint ADD CONSTRAINT webhook_endpoint_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);
ALTER TABLE webhook_endpoint ADD CONSTRAINT webhook_endpoint_id_workspace UNIQUE (id, workspace_id);

ALTER TABLE form_working_copy ADD COLUMN workspace_id uuid;
UPDATE form_working_copy t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE form_working_copy ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form_working_copy ADD CONSTRAINT form_working_copy_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);

ALTER TABLE form_notification_settings ADD COLUMN workspace_id uuid;
UPDATE form_notification_settings t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
ALTER TABLE form_notification_settings ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE form_notification_settings ADD CONSTRAINT form_notification_settings_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);

-- form_id is optional on mail (MATCH SIMPLE skips the check when it is NULL).
ALTER TABLE mail_outbox ADD COLUMN workspace_id uuid;
UPDATE mail_outbox t SET workspace_id = f.workspace_id FROM form f WHERE f.id = t.form_id;
UPDATE mail_outbox SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default') WHERE workspace_id IS NULL;
ALTER TABLE mail_outbox ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE mail_outbox ADD CONSTRAINT mail_outbox_workspace FOREIGN KEY (workspace_id) REFERENCES workspace (id);
ALTER TABLE mail_outbox ADD CONSTRAINT mail_outbox_form_ws
    FOREIGN KEY (form_id, workspace_id) REFERENCES form (id, workspace_id);

-- Submissions follow their version; deliveries follow their endpoint.
ALTER TABLE submission ADD COLUMN workspace_id uuid;
UPDATE submission t SET workspace_id = v.workspace_id FROM form_version v WHERE v.id = t.form_version_id;
ALTER TABLE submission ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE submission ADD CONSTRAINT submission_version_ws
    FOREIGN KEY (form_version_id, workspace_id) REFERENCES form_version (id, workspace_id);

ALTER TABLE webhook_delivery ADD COLUMN workspace_id uuid;
UPDATE webhook_delivery t SET workspace_id = e.workspace_id FROM webhook_endpoint e WHERE e.id = t.endpoint_id;
ALTER TABLE webhook_delivery ALTER COLUMN workspace_id SET NOT NULL;
ALTER TABLE webhook_delivery ADD CONSTRAINT webhook_delivery_endpoint_ws
    FOREIGN KEY (endpoint_id, workspace_id) REFERENCES webhook_endpoint (id, workspace_id);

-- Audit entries about a workspace's data carry it; sign-ins and account events stay instance-wide.
ALTER TABLE audit_log ADD COLUMN workspace_id uuid REFERENCES workspace (id);
UPDATE audit_log SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default')
    WHERE action LIKE ANY (ARRAY['form.%', 'folder.%', 'submission%', 'webhook.%', 'retention.%',
                                 'notifications.%', 'file.%', 'erasure.%', 'invitation.%',
                                 'member.roleChanged']);
CREATE INDEX audit_log_workspace_at ON audit_log (workspace_id, at DESC);
