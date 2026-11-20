#!/usr/bin/env python3
"""Offline cross-component invariants for the November 2026 incident fixes.

Complements (never replaces) JUnit/real Flyway integration tests.
"""
from pathlib import Path
import re
root = Path(__file__).resolve().parents[1]
java = root / 'src/main/java/info/trizub/clamav/webclient'

def source(name): return (java / name).read_text()

watcher = source('service/WatcherService.java')
worker = source('service/ScanExecutionService.java')
quarantine = source('service/QuarantineService.java')
coordinator = source('service/QuarantineArtifactCoordinator.java')
finalizer = source('service/ScanJobFinalizationService.java')
repo = source('repo/ProcessedFileRepository.java')
policy = source('service/DaemonScanPolicy.java')

assert 'findByPathInAndEndpointId' in watcher and 'findByPathInAndEndpointId' in repo
assert 'nextEligibleMs' in watcher and 'completed.size() == targets.size()' not in watcher
assert 'getEndpointId() == null' in watcher  # lazy legacy marker cleanup
assert 'stageDetections' in worker and 'stageDetections' in finalizer
assert 'positiveVerdict' in worker and 'if (positiveVerdict)' in worker
assert 'JournalCommitFailure' in worker and 'JournalCommitFailure' in coordinator
assert 'for (var entry : found.entrySet())' in coordinator and 'ledger.forJob(jobId)' in coordinator
assert 'repairOutstandingArtifacts()' in coordinator and 'outstandingAfter' in coordinator
assert 'scanTargetWasDirectory' in finalizer and 'scanTargetWasDirectory' in source('model/ScanJob.java')
assert 'findByPath(String path)' not in repo and 'findByPathIn(Collection<String> paths)' not in repo
assert 'ledger.reserve(artifact)' in coordinator and 'attemptSourceWithArtifactId' in coordinator
# Regression guards for quarantine identity and aggregate warning reconciliation.
assert 'ledger.reserveBatch(prepared)' in coordinator
assert 'QuarantineFileIdentity.capture(candidate)' in coordinator
assert 'hasUnresolvedDetections(job)' in coordinator
assert 'new QuarantineService.Result(null, true)' in coordinator
assert re.search(r'attemptSourceWithArtifactId\(\s*source,\s*scanTarget,\s*allowedRoots,\s*directory,', coordinator)
identity = source('service/QuarantineFileIdentity.java')
assert 'MessageDigest.getInstance("SHA-256")' in identity and 'LinkOption.NOFOLLOW_LINKS' in identity
assert 'QuarantineFileIdentity.matches(file, expectedIdentity)' in quarantine
assert 'QuarantineFileIdentity.matches(moved, expectedIdentity)' in quarantine
assert re.search(r'prepareSources\(\s*jobId,\s*found,\s*scanTarget', worker)
assert re.search(r'executePreparedSource\(\s*jobId,\s*artifact', worker)
assert 'quarantineRequested' in source('model/ScanJob.java')
assert re.search(r'finalizer\.finishFound\(\s*jobId', coordinator) and 'recoverInterruptedQuarantine' in coordinator
assert 'quarantineRecovery.recoverInterruptedQuarantine()' in source('service/ScanJobService.java')
assert 'TransactionSynchronizationManager.registerSynchronization' in finalizer
assert 'failureRecorded' in quarantine and 'message.split(' in quarantine
assert 'MAX_REPORTED_DETECTION_PATHS' in policy and 'MAX_SIGNATURES_PER_PATH' in policy
limiter = source('service/DetectionReportLimiter.java')
assert 'MAX_UTF8_BYTES' in limiter and 'writeValueAsBytes' in limiter
assert 'DetectionReportLimiter.limit(result, resultMapper)' in worker
retention = source('service/StorageRetentionService.java')
assert 'quarantineArtifacts.findByJobIdOrderByIdAsc(row.id())' in retention
assert 'allArtifactsAbsent' in retention
assert 'getScanTargetWasDirectory()' in coordinator and 'scanTargetWasDirectory' in source('model/ScanJob.java')
assert 'synchronized (dispatchLock)' in worker and re.search(r'synchronized \(dispatchLock\)\s*\{\s*if \(stopping\) return;\s*claimed = markRunning',worker)
assert 'lifecyclePhaseTimeout.toSeconds() - 5' in worker
pg_legacy_repair = source('db/PostgresLegacyProcessedPathCompatibility.java')
assert 'Event.BEFORE_MIGRATE' in pg_legacy_repair
assert '"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())' in pg_legacy_repair
assert "c.conname = 'idx_processed_path'" in pg_legacy_repair
assert "c.contype = 'u'" in pg_legacy_repair
assert '"UNIQUE (path)".equals(legacy.definition())' in pg_legacy_repair
assert 'DROP CONSTRAINT' in pg_legacy_repair and 'CREATE UNIQUE INDEX' in pg_legacy_repair
fixture = (root / 'src/test/java/info/trizub/clamav/webclient/db/Phase2MigrationFixture.java').read_text()
assert 'ADD CONSTRAINT idx_processed_path UNIQUE (path)' in fixture
assert '.callbacks(new PostgresLegacyProcessedPathCompatibility())' in fixture

for vendor in ('h2','postgresql','mysql','mariadb'):
    migration = root / f'src/main/resources/db/migration/{vendor}/V9__watcher_endpoint_and_quarantine_journal.sql'
    sql = migration.read_text()
    assert 'ADD COLUMN endpoint_id' in sql and 'CREATE TABLE quarantine_artifacts' in sql
    assert 'ON DELETE CASCADE' in sql and 'CREATE UNIQUE INDEX idx_processed_endpoint' in sql
    assert 'DELETE FROM processed_files' not in sql  # avoid unbounded migration-time delete
    v10 = root / f'src/main/resources/db/migration/{vendor}/V10__scan_target_scope.sql'
    assert 'ADD COLUMN scan_target_was_directory' in v10.read_text()
    v11 = root / f'src/main/resources/db/migration/{vendor}/V11__quarantine_content_evidence.sql'
    v11sql = v11.read_text()
    for name in ('source_sha256', 'source_size_bytes', 'failure_code', 'quarantine_requested'):
        assert 'ADD COLUMN ' + name in v11sql
    v12 = root / f'src/main/resources/db/migration/{vendor}/V12__watch_retry_ownership.sql'
    v12sql = v12.read_text()
    for name in ('last_scan_job_id', 'retry_failures', 'retry_after'):
        assert 'ADD COLUMN ' + name in v12sql
for test in ('WatcherIncrementalTest.java','WatcherPersistenceTest.java','QuarantineArtifactCoordinatorTest.java',
             'ScanExecutionNativeFallbackBoundaryTest.java',
             'QuarantineRecoveryCompletenessTest.java', 'QuarantineEvidenceRegressionTest.java', 'DetectionReportLimiterTest.java',
             'QuarantineRetentionSafetyTest.java'):
    assert (root / 'src/test/java/info/trizub/clamav/webclient/service' / test).is_file()
assert (root / 'src/test/java/info/trizub/clamav/webclient/db/ScanScopeMigrationTest.java').is_file()
assert (root / 'docs/QUARANTINE_RECOVERY_V10.md').is_file()
assert 'combinedPartialScanAndQuarantineWarningStillReturnsQuarantineFailure' in (
    root / 'src/test/java/info/trizub/clamav/webclient/api/v1/ApiV1DtoTest.java').read_text()
print('PASS: ten November 2026 audit regression invariants and 4 Flyway migration families')