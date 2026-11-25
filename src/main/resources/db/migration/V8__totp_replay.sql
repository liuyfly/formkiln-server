-- The last TOTP time step accepted for each member: a code is accepted once (RFC 6238, 5.2).
ALTER TABLE admin_user ADD COLUMN totp_last_step bigint;
