-- Row-level security: a second line of defence under the application's workspace checks.
--
-- Requests that act in a workspace run as the role formkiln_app (SET ROLE on connection checkout)
-- with formkiln.workspace_id set to the request's workspace. The policies below then hide every row
-- of another workspace and refuse to write one, even from a query that forgot its workspace
-- condition. Without the setting a query sees no rows at all (fail closed).
--
-- The table owner (migrations, background jobs, sign-in, instance administration) is not subject
-- to the policies: RLS is enabled, not forced. The role itself and its grants are set up when the
-- server starts (see RowLevelSecurity), because creating a role needs a privilege that some
-- installations only give to their DBA.

ALTER TABLE form ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE form_folder ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form_folder
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE form_version ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form_version
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE submission ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON submission
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE form_draft ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form_draft
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE file_object ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON file_object
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE webhook_endpoint ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON webhook_endpoint
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE webhook_delivery ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON webhook_delivery
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE form_working_copy ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form_working_copy
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE form_notification_settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON form_notification_settings
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE mail_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON mail_outbox
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE workspace_member ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON workspace_member
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE admin_invitation ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON admin_invitation
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE service_token ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON service_token
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY workspace_isolation ON audit_log
    USING (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid)
    WITH CHECK (workspace_id = NULLIF(current_setting('formkiln.workspace_id', true), '')::uuid);
