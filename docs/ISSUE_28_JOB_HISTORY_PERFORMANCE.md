# Issue #28 — Bounded job history, safe startup recovery, pagination

**Source baseline:** ClamAV Web UI v2.1.0beta2 including fixes #25, #26 and #27. These fixes and the earlier #13–#24 security controls remain in place. No DB rows are deleted or rewritten by the history optimization.

## Changes in this build

1. `ScanJobSummary` is a scalar JPQL constructor projection. Dashboard, scan history, `/jobs`, `/jobs/table`, and the legacy `/api/jobs` use it instead of hydrated `ScanJob` entities. SQL returns basic metadata, a nullable endpoint ID/name, and timing/size scalars. It **does not select** `found_viruses_json`, `error_message`, `stored_path` or `quarantine_path`. Endpoint objects are not materialized for these views.
2. The `ScanJob.endpoint` association is `LAZY`; `ScanJobRepository.findById()` explicitly fetches the endpoint for full job detail, execution, and authorization. `/api/v1/jobs` (full DTO API with token path-scope filtering) keeps its existing rich response; it converts rows while a read-only transaction is active. Those API v1 requests still load rich records when necessary for their published contract.
3. `/jobs` and `/jobs/table` are paginated with `page` (zero-based) and `size` (1–100, UI choices 25/50/100). Sorting is stable: newest `submitted_at DESC`, then `id DESC`. The live-refresh fragment follows the selected page/size, retaining the previous authorization and no-cache behavior. Dashboard and scan previews show 25 entries. The legacy `/api/jobs` remains a 200-item response but queries only metadata; use `/api/jobs/{id}` to obtain signatures, specific error detail, and quarantine status. The list's signatures will be empty and error status generic by design.
4. Startup recovery no longer invokes `findAll()` or per-job `save()`. A single indexed update converts `RUNNING` rows to `QUEUED` and the dispatcher enqueues only the oldest 100 queued job IDs at a time; the existing periodic reconciler continues in bounded batches. Executor CAS claiming and in-memory deduplication remain unchanged. This preserves the single-instance recovery model; this is **not** a distributed scan scheduler.
5. The expensive global `SettingsService.snapshot()` call was removed from the shared MVC model interceptor. The active settings page redirects to Configuration Center and does not need a full settings copy.
6. Adds Flyway `V5__scan_jobs_history_indexes.sql` for H2, PostgreSQL, MySQL and MariaDB: indexed `(status, submitted_at, id)` recovery and `(submitted_at DESC, id DESC)` latest-job paging. Flyway must apply the migration on startup. Plan a maintenance window for a very large existing job table because index creation can temporarily lock/write-amplify the table depending on the database engine.

## Upgrade and compatibility

**No environment variables or Docker Compose changes required.** Preserve existing PostgreSQL/MariaDB/MySQL/H2 database and authentication configuration. Make a backup before upgrading. Flyway applies the new index migration automatically to a normal existing Flyway-managed database. Do not switch on Flyway baseline flags unless the existing deployment specifically requires baselining.

The legacy `GET /api/jobs` continues returning a list of the existing `Job` DTO fields, but its list-only `signatures`, detailed error and quarantine status are intentionally omitted/simplified. Request `/api/jobs/{id}` for complete per-job information. `/api/v1/jobs` continues its existing scoped API contract. Native filename scanning and upload cleanup are untouched.

## Validation

New tests:

- `Issue28JobHistoryTest`: Spring Data/H2 query results, pagination, JOINed endpoint metadata, and SQL-inspector exclusion of `TEXT` columns; one bulk state reset; bounded ID-only query.
- `Issue28StartupRecoveryTest`: asserts only a bounded ID query and a single bulk reset (no `findAll()`/per-job `save()`), including recovery poll.
- `JobsLiveViewTest`: adds pagination/live fragment assertions while retaining authorization, escaping and context-path checks.

Run with Maven and JDK 17 or a supported newer JDK:

```bash
mvn -B -Dtest=Issue28JobHistoryTest,Issue28StartupRecoveryTest,JobsLiveViewTest test
mvn -B verify
```

For local SQL inspection, add `logging.level.org.hibernate.SQL=DEBUG`, request `/dashboard` and `/jobs?page=0&size=25`, and verify SELECTs do not include `found_viruses_json` and `error_message`. **Individual `/jobs/{id}` detail requests must still fetch those fields**.

For the 1,000,000-row acceptance criterion, populate a **staging-only** database, not production. Compare JVM heap and cold-start elapsed time before/after; inspect SQL/EXPLAIN plans with the new indexes. The startup SQL must not enumerate finished rows. An actual million-row benchmark and full Maven run have **not** been executed in the artifact creation environment; do not claim production performance until they have passed.
