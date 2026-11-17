# ClamAV Web UI v2.1.0beta2 — modified files (relative to v2.1beta1 source archive)

- `src/main/java/info/trizub/clamav/webclient/service/AuditService.java` — schema-bound values, defensive best-effort logging, all audit entry points.
- `src/main/java/info/trizub/clamav/webclient/service/AuditEventWriter.java` — new, isolated audit transaction and immediate persistence flush.
- `src/test/java/info/trizub/clamav/webclient/service/AuditServiceTest.java` — expanded regression tests for 10 KiB User-Agent, all column limits, surrogate boundaries, and audit errors.
- `src/test/java/info/trizub/clamav/webclient/web/UiSmokeTest.java` — MockMvc 10 KiB header endpoint creation + persisted audit assertion.
- `src/main/resources/application.properties`, `src/main/java/info/trizub/clamav/webclient/web/UiModelAdvice.java`, `pom.xml` — v2.1.0beta2 UI/build identity.
- `src/main/resources/templates/fragments/shell.html`, `src/main/resources/templates/login.html`, `src/main/resources/templates/error.html`, `src/main/resources/configuration-center/setup.html` — clean version footers, no marketing tagline.
- `README.md`, `docs/releases/v2.1.0beta2.md`, `docs/ISSUE_25_AUDIT_FIX.md`, `BETA2_CHANGED_FILES.md` — documentation.
- `.github/workflows/beta-prerelease.yml`, `docs/BETA_DEPLOYMENT_EXAMPLES.md`, `docker-compose.yml` — update local beta2 tag/version examples. **No GitHub action has been triggered.**

Unchanged source files are identical to the v2.1beta1 input archive, including fixes for issues #13–#24.

## Additional local changes for issue #26

- `src/main/java/info/trizub/clamav/webclient/service/UserService.java` — lowercase/regex username checks, case-insensitive uniqueness, 72-byte BCrypt limit and duplicate insert handling.
- `src/main/java/info/trizub/clamav/webclient/repo/AppUserRepository.java` — case-insensitive username lookup.
- `src/main/java/info/trizub/clamav/webclient/service/EndpointInputValidator.java` (new) — reusable TCP endpoint validation.
- `src/main/java/info/trizub/clamav/webclient/service/EndpointService.java` — enforce additional-endpoint constraints at service boundary, preserving native-socket default-engine support.
- `src/main/java/info/trizub/clamav/webclient/web/WebUiController.java` — show form validation errors via flash messages.
- `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java`, `src/main/resources/configuration-center/catalog.json` — settings bounds and custom storage-directory checks.
- `src/main/java/info/trizub/clamav/webclient/service/SettingsService.java` — bound legacy/deployment numeric values, warn on corruption.
- `src/main/resources/templates/pages/users.html`, `src/main/resources/templates/pages/endpoints.html` — HTML limits and clarified labels.
- `src/test/java/info/trizub/clamav/webclient/service/Issue26UserValidationTest.java`, `Issue26EndpointValidationTest.java`, `configuration/Issue26SettingsValidationTest.java`, `web/Issue26AdminFormsTest.java`, and `configuration/ConfigurationAllFieldsTest.java` — regression coverage.
- `README.md`, `docs/releases/v2.1.0beta2.md`, `docs/ISSUE_26_INPUT_VALIDATION.md`, `BETA2_CHANGED_FILES.md` — documentation.

Issue #25 audit classes and tests have not been altered; neither has the implementation of security issues #13–#24.

## Additional local issue #28 changes

- `ScanJobSummary.java` (new): lightweight list projection.
- `ScanJob.java`, `ScanJobRepository.java`, `ScanJobService.java`: LAZY endpoint with explicit fetch for details, query projections, indexed bulk requeue and bounded ID reconciliation, page and preview services.
- `WebUiController.java`, `ApiController.java`, `ApiV1Controller.java`, `ApiV1Dtos.java`: paginated history UI, lightweight legacy API summaries, preserved full-detail authorization.
- `pages/jobs.html`, `fragments/tables.html`, `fragments/jobs.html`: page navigation, live-refresh page awareness, endpoint-name projection.
- `db/migration/{h2,mysql,mariadb,postgresql}/V5__scan_jobs_history_indexes.sql`: new indexes.
- `Issue28JobHistoryTest.java`, `ScanJobSqlInspector.java`, `Issue28StartupRecoveryTest.java`, `JobsLiveViewTest.java`: query and recovery regressions.
- `README.md`, `docs/releases/v2.1.0beta2.md`, `docs/ISSUE_28_JOB_HISTORY_PERFORMANCE.md`: documentation and compatibility notes.

Earlier security fixes #13–#27 remain. No GitHub changes were made.

## Additional local issue #29 changes

- `PathPolicy.java`: per-scan resolved-root caching, retaining no-symlink escapes.
- `DaemonScanPolicy.java`: fast safe directory MULTISCAN, bounded shared scan pool, exclusion pruning, per-file failures.
- `ScanExecutionService.java`: partial failure warning and explicit failure/virus verdict policy.
- `DaemonScanPolicyTest.java`, new `Issue29DirectoryScanTest.java`, new `Issue29PartialJobVerdictTest.java`: regression coverage.
- `README.md`, `docs/releases/v2.1.0beta2.md`, `docs/ISSUE_29_DIRECTORY_SCAN_PERFORMANCE.md`: documentation.

All earlier #25–#28 fixes retained. This is a local source archive only.


## Issue #30 — Upload I/O and early 413 admission (local beta2 source only)

- `ScanJobService.java`: move-capable `MultipartFile.transferTo(File)` for standard servlet uploads; secure streaming-copy + in-flight SHA-256 retained for custom upload implementations. The moved file is restricted to 0600 and revalidated against the daemon policy.
- `UploadStorageService.java`: reserved upload destination managed by the existing in-flight/transaction rollback tracking; parent remains 0700.
- `UploadAdmissionFilter.java`, `UploadRequestLimits.java`, `SecurityConfig.java`: global Content-Length 413 plus session-authenticated endpoint-size hint handling **before CSRF multipart parsing**. Does not weaken CSRF, authentication or per-file enforcement.
- `upload-admission.js`, `pages/scan.html`, `fragments/scan.html`, `fragments/shell.html`: the browser submits non-authoritative endpoint and file-count hints in the URL query; the actual endpointId field remains in the multipart form and is validated as before.
- `Dockerfile`, `docker-entrypoint.sh`, `application.properties`: private same-filesystem multipart spool at `/app/data/tmp/multipart` in the Docker image; no new scanner mounts or published ports. Standalone Java leaves the OS temp location unchanged unless configured.
- `HashUtils.java`: Java HexFormat replaces per-byte String.format.
- Tests: `UploadRequestLimitsTest.java`, `UploadAdmissionFilterTest.java`, additional servlet multipart regression in `UploadBatchPersistenceTest.java`.
- Documentation: `docs/ISSUE_30_UPLOAD_PERFORMANCE.md` and README announcement.

**Validation:** source static checks, Java 17 compile for new filter and pure limit arithmetic, simple SHA-256 smoke test, JavaScript syntax and shell syntax checks passed. Full Maven regression, live multipart-413 and real Tomcat move benchmarks **not executed** in this runtime. Earlier security fixes #13–#29 were compared with the original full-source ZIP; no previous files were removed.

## Issue #31 — shared ClamD snapshot cache (local beta2 source only)

- `ClamdTopService.java`: 2-second bounded, per-endpoint shared snapshot cache with single-flight refresh, short failure caching, and no redundant PING.
- `ApiV1Controller.java`: use the true cached observation timestamp in `checkedAt` rather than the HTTP response time. Endpoint authorization and metrics redaction preserved.
- `static/js/clamdtop.js`: hidden-tab pause, visible-tab refresh, and duplicate-request suppression. Native interactive PTY untouched.
- `application.properties`: optional `CLAMAV_CLAMDTOP_CACHE_TTL_MS` (1000–3000ms).
- `Issue31ClamdSnapshotCacheTest.java`: concurrent 12-viewer single-flight test, TTL, daemon failure and endpoint settings change regressions.
- `docs/ISSUE_31_CLAMDTOP_POLLING.md`, `README.md`: documentation.

Issues #13–#30 are unchanged other than the above metrics API timestamp correction. Full Maven and
Docker integration tests remain required before production deployment.
