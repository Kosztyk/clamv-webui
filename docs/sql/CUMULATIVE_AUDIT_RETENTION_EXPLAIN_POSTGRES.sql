-- Read-only diagnostics. Run on a representative PostgreSQL database AFTER Flyway V7.
-- Indexes expected: idx_scan_jobs_retention(status,finished_at,id)
--                   idx_audit_retention(at,id)
-- Replace the cutoff/cursor values with times matching your real job history.
-- For an accurate production plan, ANALYZE the tables during a maintenance window.
-- EXPLAIN ANALYZE executes the SELECTs; they do NOT delete or change rows.

EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, finished_at, stored_path, quarantine_path
FROM scan_jobs
WHERE status='FINISHED' AND finished_at < (CURRENT_TIMESTAMP - INTERVAL '30 days')
ORDER BY finished_at ASC, id ASC LIMIT 500;

EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, finished_at, stored_path, quarantine_path
FROM scan_jobs
WHERE status='FINISHED'
  AND finished_at < (CURRENT_TIMESTAMP - INTERVAL '30 days')
  AND (finished_at > TIMESTAMPTZ '2026-01-01 00:00:00+00'
    OR (finished_at = TIMESTAMPTZ '2026-01-01 00:00:00+00'
      AND id > 'example-cursor'))
ORDER BY finished_at ASC, id ASC LIMIT 500;

EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, at FROM audit_events
WHERE at < (CURRENT_TIMESTAMP - INTERVAL '30 days')
ORDER BY at ASC, id ASC LIMIT 500;

EXPLAIN (ANALYZE, BUFFERS, VERBOSE)
SELECT id, at FROM audit_events
WHERE at < (CURRENT_TIMESTAMP - INTERVAL '30 days')
  AND (at > TIMESTAMPTZ '2026-01-01 00:00:00+00'
    OR (at = TIMESTAMPTZ '2026-01-01 00:00:00+00' AND id > 12345))
ORDER BY at ASC, id ASC LIMIT 500;

-- Expected: B-tree Index Scan (or Bitmap/Index Scan, depending on table size)
-- on the V7 retention indexes. A sequential scan on a tiny table can be normal.
-- Examine buffers, loops, scanned vs returned rows, and sorting cost.
