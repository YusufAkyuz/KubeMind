-- OIDC-provisioned users have no local password to hash, and are tagged so a
-- future "this account is managed by SSO" UI hint doesn't need another migration.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ADD COLUMN identity_provider VARCHAR(32) NOT NULL DEFAULT 'local';
