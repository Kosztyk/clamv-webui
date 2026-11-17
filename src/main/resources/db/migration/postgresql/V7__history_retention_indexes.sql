-- Issue #35 cumulative audit: age-first retention batch scans.
-- The finished status filter and timestamp range are contiguous in this index.
CREATE INDEX idx_scan_jobs_retention ON scan_jobs(status, finished_at, id);
-- Audit records use a timestamp+id keyset cursor; avoids ID-ordered history scans.
CREATE INDEX idx_audit_retention ON audit_events(at, id);
