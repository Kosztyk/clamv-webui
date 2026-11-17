-- A watcher fingerprint belongs to a particular committed WATCH scan job.
-- Legacy rows have no job identity and are revalidated once after upgrade.
-- Failed scan verdicts set a bounded retry deadline transactionally.
ALTER TABLE processed_files ADD COLUMN last_scan_job_id VARCHAR(64);
ALTER TABLE processed_files ADD COLUMN retry_failures INTEGER NOT NULL DEFAULT 0;
ALTER TABLE processed_files ADD COLUMN retry_after DATETIME(6);
CREATE INDEX idx_processed_last_scan_job ON processed_files (last_scan_job_id);
