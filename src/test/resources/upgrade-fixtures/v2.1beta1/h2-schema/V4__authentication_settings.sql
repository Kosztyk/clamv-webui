-- Phase 3.1: independently managed provider settings (no change to app_users / API credentials).
-- Only AES-GCM ciphertext is stored in encrypted_secret; never persist a cleartext bind/client secret.
CREATE TABLE auth_provider_settings (
    provider VARCHAR(16) NOT NULL PRIMARY KEY,
    settings_json CLOB NOT NULL,
    encrypted_secret VARCHAR(2048),
    updated_at TIMESTAMP(6) NOT NULL,
    updated_by VARCHAR(255) NOT NULL
);
