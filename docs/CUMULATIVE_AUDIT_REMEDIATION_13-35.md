# v2.1.0beta2 cumulative post-audit fixes (#13–#35)

This update applies to the latest full-source archive with issues #25–#35 already implemented. All earlier security controls are retained. No GitHub change or release was made. **This document describes source changes, not a claim that Maven/Testcontainers/ClamAV ran successfully in the packaging environment.**

## #29 / #33: unchanged config refresh could abort directory scans

`ClamdConfigurationObserver.refresh()` used to replace the `DaemonConfiguration` record on *every* poll, including identical clamd.conf content. `DaemonScanPolicy.verifyPolicyUnchanged` compared object identity (`!=`), treating a harmless refresh as a security-policy change and interrupting long directory scans. The observer now reuses its prior immutable effective policy object when the endpoint, limits, and raw exclusion expressions are unchanged; `DaemonConfiguration.samePolicyAs` excludes the observation timestamp and compiled `Pattern` object identities. The scan policy guard compares values rather than Java reference identity. Real changes to limits, exclusion regexes, or endpoint connections still invalidate the scan. Current allowed-root validation is unchanged and still performed during traversal.

The actual observer and scan-policy classes are compiled using `javac --release 17` and exercised by `scripts/check-audit-policy-offline.py` without Spring/ClamAV dependencies. The regression invokes a no-op configuration refresh *during* the real path traversal and checks that one directory MULTISCAN is used; a genuine exclusion change must still interrupt the traversal.

## #33: prevent transactional no-ops on unchanged configuration polls

`EndpointService.pollConfiguration()` now remembers the last committed discovered endpoint candidate. After refreshing mounted config files **outside** a database transaction, an identical candidate skips default-endpoint synchronization, so there is no transactional proxy heartbeat. A candidate change causes a short `TransactionTemplate` transaction; the marker updates only after successful completion. Explicit primary endpoint edits/removals and toggling an override invalidate the marker, forcing re-synchronization when needed. The dynamic scheduler from issue #33 remains unchanged.

Regression tests in `Issue33ConfigurationPollingTest` assert that three identical refreshes open just one short transaction, while a changed candidate opens another.

## #30: REST monitor uploads and multipart ceiling headroom

The pre-multipart admission filter now includes `POST /rest-monitor/upload` in addition to `/scan/upload`, legacy API and API v1 upload routes. The REST monitor receives the same stable JSON `413 upload_too_large` body as the other JSON API routes, without reading multipart form fields. The filter's global `Content-Length` ceiling applies to the external REST monitor; **ClamAV endpoint-specific limits must not be applied to external REST submissions**. Existing post-parse Tomcat limits remain necessary for chunked uploads or requests lacking `Content-Length`.

Default `spring.servlet.multipart.max-file-size` is now **2047MB**, below the **2048MB** request maximum; this leaves 1MiB for boundaries/form fields in a single-file upload. The Configuration Center now rejects new multipart-limit combinations with less than 64KiB of headroom. Multi-file uploads must still fit the aggregate request limit. Existing explicit environment variables and saved GUI values retain precedence: inspect and correct any legacy 2048MB/2048MB overrides during upgrade.

## #28: API v1 jobs now use scalar DTO projections

The `/api/v1/jobs` list now queries `ScanJobSummary` directly, rather than full `ScanJob` objects that load the `found_viruses_json` and `error_message` TEXT columns. Filtered endpoint/status/verdict lists use a paged scalar JPQL projection and scalar count query, including jobs with no endpoint. The token path-scope listing still performs candidate selection in bounded batches, applies final case-sensitive Java path/actor/transport checks and returns only scalar projected rows for selected IDs. It retains stable count/pagination semantics. The individual `/api/v1/jobs/{id}` detail route still loads full records and exposes permitted sanitized diagnostic fields. A subsequent compatibility patch restores sanitized `signatures`, recorded quarantine status and specific safe `error.code` values in list responses. The lightweight page query is unchanged. A second, bounded query reads only `(id, quarantine_path)` for selected page items; a third query fetches TEXT diagnostics **only for the infected/failed items on that page**, after path-scoped authorization. Detail URLs still provide complete records. `ScanJobSummary` adds `submittedTransport` solely to preserve upload ownership checks.

Existing scoped-token end-to-end tests and the `Issue28JobHistoryTest` SQL inspector test cover the contract and avoidance of TEXT columns.

## Retention-query optimization: V7 per database

The previous retention sweeps read records ordered by primary-key ID with `finished_at < cutoff` / `at < cutoff` filters. This is inefficient when old records are sparse among recent records and loads job TEXT columns unnecessarily. New V7 migrations, **without rewriting V1–V6**, add:

| Index | Columns | Purpose |
| --- | --- | --- |
| `idx_scan_jobs_retention` | `(status, finished_at, id)` | Complete-job aging and stable keyset cursor |
| `idx_audit_retention` | `(at, id)` | Audit aging and stable keyset cursor |

`StorageRetentionService` now uses timestamp-then-ID keyset paging in batches of 500, with separate initial and continuing queries to avoid nullable timestamp predicates. Job sweeps return only `(id, finished_at, stored_path, quarantine_path)` metadata and preserve entries with extant uploads/quarantine paths; audits return `(id, at)` only. This avoids the earlier full-entity TEXT-column fetch during sweeping. The `UploadBatchPersistenceTest` now includes 610 rows with ID order deliberately inverted against timestamps and multiple rows sharing timestamps to detect missed/deleted records.

Real PostgreSQL optimizer choices must still be checked with `docs/sql/CUMULATIVE_AUDIT_RETENTION_EXPLAIN_POSTGRES.sql`. A SQLite DDL smoke test **is not** PostgreSQL `EXPLAIN`, and no production-scale query plan or runtime is claimed.

## Regression validation

Without external dependencies:

```bash
python3 scripts/check-audit-policy-offline.py
python3 scripts/check-audit-endpoint-offline.py
python3 scripts/check-audit-api-v1-offline.py
python3 scripts/check-audit-retention-indexes.py
python3 scripts/check-safe-scanning-source.py
python3 scripts/check-phase1-static.py
python3 scripts/check-phase2-static.py
python3 scripts/phase4-static-check.py
python3 scripts/check-i18n.py
node scripts/test-configuration-observer.js
node scripts/test-issue32-browser.cjs
```

On a Java 17+ build machine with Maven and Docker/Testcontainers installed and available:

```bash
mvn -B -Dtest=ClamdConfigurationObserverTest,DaemonScanPolicyTest,Issue29DirectoryScanTest,Issue33ConfigurationPollingTest,UploadAdmissionFilterTest,Issue28JobHistoryTest,ApiV1PathScopeIntegrationTest,ApiV1ContractIntegrationTest,ApiV1DtoTest,UploadBatchPersistenceTest,Phase2H2MigrationTest,CumulativeAuditRetentionIndexesTest test
mvn -B verify
mvn -B -Pdb-integration verify
```

On a host with a real `clamd` binary and a packaged server JAR:

```bash
python3 scripts/test-eicar-native.py --clamd /usr/sbin/clamd --jar target/<built-app>.jar
```

Or run `scripts/run-cumulative-audit-regressions.sh` to execute these checks with prerequisite validation. Back up the production database before rollout, particularly for large tables where V7 index creation can lock writes. Do not use Flyway `baseline-on-migrate` just because V7 was added: it is for intentionally baselining an existing, unversioned database only.

## Verification limits

The packaging environment has Java 21 and Node/Python but **no Maven, no local Maven dependency cache, no Docker/Podman, no PostgreSQL, MySQL, MariaDB, or `clamd` binaries, and no resolvable Maven Central DNS**. Therefore only isolated Java 17 and static/source/browser checks run here. H2/three vendor Testcontainers migrations, full Spring integration, real ClamAV scans, and PostgreSQL `EXPLAIN ANALYZE` remain mandatory deployment-side checks. Their completion has not been asserted.
