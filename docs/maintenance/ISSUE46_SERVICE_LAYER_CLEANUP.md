# Issue #46 — service-layer maintenance and transaction parity

Issue: https://github.com/Kosztyk/clamv-webui/issues/46

## What changed

- Removed obsolete `ScanJobService` terminal-state methods (`finishOk`, `finishFound`, `finishError`, `markRunning`, `setQuarantinePath`). `ScanExecutionService` remains the single owner of terminal scan state and its existing FINISHED guard.
- Removed `ScanJobService` three-argument overloads. Browser `/scan/upload` and `/scan/path` now call the four-argument service methods with transport `BROWSER`, as the legacy and v1 APIs already do. Browser path requests consequently enter Spring through the annotated transactional method, not through `this` self-invocation.
- Preserved the established two-phase upload transaction design. Multipart copying/hashing happens **without** a database transaction. All prepared metadata records are flushed as **one** `@Transactional(REQUIRES_NEW)` batch in `UploadJobCommitter`, which both UI and API routes use; the associated upload artifacts are deleted on transaction rollback. Enqueue happens after commit only.
- Switched executor worker naming from `Thread.getId()` to Java 21 `Thread.threadId()`.
- Added warning-level failure logging for quarantine operations, infected source quarantines, and notification delivery. The notification warning intentionally omits raw exception messages that may embed webhook endpoints or credentials; server-side job IDs and exception types are retained.
- Scoped readability cleanup of complex identity-provider and native ClamdTop code. A project-wide formatter is **not** applied to unrelated Java files, to avoid a very large maintenance diff with accidental functional churn.

## Acceptance-test design

`UploadBatchPersistenceTest.secondDatabaseInsertFailureRollsBackEntireBatchForUiAndApi` runs against real H2 tables. It creates a `CHECK` constraint rejecting only the second file (`reject-second.txt`) so the first row succeeds before the batch fails. It then asserts for `BROWSER`, `API_BASIC_OR_SESSION`, and `API_TOKEN` that:

1. Neither database row was committed.
2. Neither copied file remains in the private upload storage.
3. No scan was sent to the executor before committing.

`pathCreationUsesTheProxiedTransactionForBothRoutes` verifies that the path policy is executed inside a Spring-managed transaction for both browser and API sessions.

The previous `threeCopiesLeaveASingleConnectionPoolAvailableForOtherQueries` regression remains intact to prevent reintroducing a long transaction around multipart streams.

## Validation

```bash
python3 scripts/check-issue46-service-layer.py
mvn -B -ntp '-Dtest=UploadBatchPersistenceTest,DaemonPolicyScanBoundaryTest,DisabledEngineScanTest,ConfigurationConsumerWiringTest,RateLimitSecurityIntegrationTest' test
mvn -B -ntp verify
```

This maintenance change introduces no database migration, Configuration Center setting, environment variable, or API schema change. The upload and path submission routes retain their established request/response contracts.
