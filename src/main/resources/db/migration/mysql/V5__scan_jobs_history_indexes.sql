-- Issue #28: indexed queue recovery and deterministic, bounded history pages.
-- No data rewrite. Works on existing Flyway-managed installations.
CREATE INDEX idx_scan_jobs_status_submitted_id ON scan_jobs(status, submitted_at, id);
CREATE INDEX idx_scan_jobs_recent_id ON scan_jobs(submitted_at DESC, id DESC);
