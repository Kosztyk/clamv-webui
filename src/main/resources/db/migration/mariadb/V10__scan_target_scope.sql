-- Persist file-vs-directory scope before moving any source file to quarantine.
-- NULL is intentional for legacy staged scans whose original target type is unknown;
-- their recovery must fail closed rather than infer a potentially wider scope.
ALTER TABLE scan_jobs ADD COLUMN scan_target_was_directory BIT;
