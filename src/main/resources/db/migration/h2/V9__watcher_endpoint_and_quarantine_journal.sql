-- Endpoint-aware watcher markers: legacy rows have no trustworthy endpoint owner.
-- A one-time rescan is intentional. Legacy null-owner rows are lazily pruned
-- by the watcher instead of deleting millions of rows inside a migration.
ALTER TABLE processed_files ADD COLUMN endpoint_id BIGINT;
DROP INDEX idx_processed_path;
CREATE UNIQUE INDEX idx_processed_endpoint_path ON processed_files (endpoint_id, path);

CREATE TABLE quarantine_artifacts (
    id VARCHAR(32) PRIMARY KEY,
    job_id VARCHAR(64) NOT NULL,
    original_path VARCHAR(4096) NOT NULL,
    destination_path VARCHAR(4096) NOT NULL,
    signatures_json TEXT,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_quarantine_artifact_job FOREIGN KEY (job_id) REFERENCES scan_jobs(id) ON DELETE CASCADE
);
CREATE INDEX idx_quarantine_artifacts_job ON quarantine_artifacts (job_id, status);
CREATE INDEX idx_quarantine_artifacts_status ON quarantine_artifacts (status, id);
