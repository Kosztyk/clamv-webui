-- Issue #34: lookup indexes not covered by the existing V1 or V5 migrations.
-- V5 already adds idx_scan_jobs_status_submitted_id, which supports the
-- status/submitted_at queue query: do NOT add a duplicate here.
-- Existing tables, constraints and rows are not rewritten.

-- Endpoint and verdict equality predicates, followed by newest-first job lists.
CREATE INDEX idx_scan_jobs_endpoint_at_id ON scan_jobs(endpoint_id, submitted_at DESC, id DESC);
CREATE INDEX idx_scan_jobs_verdict_at_id ON scan_jobs(verdict, submitted_at DESC, id DESC);

-- Auditing: scoped endpoint filters and username lookups ordered newest-first.
CREATE INDEX idx_audit_endpoint_at_id ON audit_events(endpoint_id, at DESC, id DESC);
CREATE INDEX idx_audit_username_at_id ON audit_events(username, at DESC, id DESC);

-- PostgreSQL/H2 do not automatically index the referencing side of an FK.
CREATE INDEX idx_app_user_roles_user ON app_user_roles(user_id);
