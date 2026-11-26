-- Break-glass access: an instance admin who is not a member opens a workspace read-only for a
-- limited time, with a reason. Every read during the window is audited in the workspace's log.
CREATE TABLE workspace_access (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  uuid NOT NULL REFERENCES workspace (id),
    user_id       uuid NOT NULL REFERENCES admin_user (id),
    reason        varchar(500) NOT NULL CHECK (length(btrim(reason)) >= 10),
    started_at    timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL
);

CREATE INDEX workspace_access_user ON workspace_access (user_id, workspace_id, expires_at DESC);
