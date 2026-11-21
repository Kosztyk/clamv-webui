-- File identity is pinned before a quarantine operation is reserved.
-- Existing V9/V10 journal rows remain nullable: never move a source during
-- recovery if an old PREPARED row has no recorded identity evidence.
ALTER TABLE quarantine_artifacts ADD COLUMN source_sha256 VARCHAR(64);
ALTER TABLE quarantine_artifacts ADD COLUMN source_size_bytes BIGINT;
ALTER TABLE quarantine_artifacts ADD COLUMN failure_code VARCHAR(32);
ALTER TABLE scan_jobs ADD COLUMN quarantine_requested BOOLEAN;
