-- Roles live on workspace memberships only. An invitation adds its account to one workspace with a
-- role; the bootstrap invitation also makes the account an instance admin.
ALTER TABLE admin_invitation ADD COLUMN instance_admin boolean NOT NULL DEFAULT false;
UPDATE admin_invitation SET instance_admin = true WHERE role = 'admin';
UPDATE admin_invitation SET workspace_id = (SELECT id FROM workspace WHERE slug = 'default') WHERE workspace_id IS NULL;
ALTER TABLE admin_invitation ALTER COLUMN workspace_id SET NOT NULL;

-- One open invitation per email and workspace (an account can be invited to several workspaces).
DROP INDEX admin_invitation_open_email;
CREATE UNIQUE INDEX admin_invitation_open_email ON admin_invitation (workspace_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

ALTER TABLE admin_user DROP COLUMN role;
