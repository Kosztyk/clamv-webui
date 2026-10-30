-- Phase 3: source/issuer/subject profile mapping. No password fields; local users stay separate.
-- Safe for populated Phase 2 schemas; additive only.
CREATE TABLE external_identities (
    principal_name VARCHAR(64) PRIMARY KEY,
    source VARCHAR(8) NOT NULL,
    issuer VARCHAR(512) NOT NULL,
    subject VARCHAR(512) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL
);
CREATE INDEX idx_external_identity_source ON external_identities(source);
