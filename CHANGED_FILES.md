# Cumulative changes — issues #13–#24

This complete source retains fixes #13–#23 and removes scan target existence disclosures (#24). The table compares this edition with `clamav-gui-issues13-23-fixed.zip`. Application version remains 2.1.0; no schema migration, setting or dependency is added.

| File | Change |
|---|---|
| `CHANGED_FILES.md` | Updated |
| `README.md` | Updated |
| `SECURITY_ISSUE_24.md` | Added |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `SOURCE_MANIFEST.sha256` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/PHASE1_API_SECURITY.md` | Updated |
| `docs/SCAN_PATH_PRIVACY.md` | Added |
| `docs/TOKEN_PATH_SCOPES.md` | Updated |
| `docs/security/path-privacy-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/ApiV1Access.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ApiCredentialService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/util/PathPolicy.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/security/PathPrivacyIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/util/PathPolicyTest.java` | Updated |
| `tools/verify-path-privacy.py` | Added |

## Previous cumulative edition

# Cumulative changes — issues #13–#23

This complete source retains fixes #13–#22 and adds strict bounded limiter state and stable scan identities (#23). The table compares this edition with `clamav-gui-issues13-22-fixed.zip`. Application version remains 2.1.0; no schema migration or new dependency is added.

| File | Change |
|---|---|
| `CHANGED_FILES.md` | Updated |
| `README.md` | Updated |
| `SECURITY_ISSUE_23.md` | Added |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `SOURCE_MANIFEST.sha256` | Updated |
| `VALIDATION.md` | Updated |
| `docs/BASIC_AUTH_LOCKOUT.md` | Updated |
| `docs/BOUNDED_LIMITERS.md` | Added |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_CENTER.md` | Updated |
| `docs/CONFIGURATION_FIELD_AUDIT.md` | Updated |
| `docs/PHASE1_API_SECURITY.md` | Updated |
| `docs/security/bounded-limiters-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/identity/LoginAttemptService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/security/BoundedExpiringStore.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/ScanRateLimitFilter.java` | Updated |
| `src/main/resources/application.properties` | Updated |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationConsumerWiringTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/identity/LoginAttemptServiceTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/security/BoundedExpiringStoreTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/RateLimitSecurityIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/ScanRateLimitFilterTest.java` | Added |
| `tools/verify-bounded-limiters.py` | Added |

## Previous cumulative edition

# Cumulative changes — issues #13–#22

This complete source retains fixes #13–#21 and adds account/session revocation (#22). The table compares this edition with `clamav-gui-issues13-21-fixed.zip`. Application version remains 2.1.0; no database migration is added.

| File | Change |
|---|---|
| `CHANGED_FILES.md` | Updated |
| `README.md` | Updated |
| `SECURITY_ISSUE_22.md` | Added |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `SOURCE_MANIFEST.sha256` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_CENTER.md` | Updated |
| `docs/CONFIGURATION_FIELD_AUDIT.md` | Updated |
| `docs/SESSION_REVOCATION.md` | Added |
| `docs/security/session-revocation-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/config/NativeClamdtopWebSocketConfig.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/config/SessionSecurityConfig.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/identity/IdentityLdapConfiguration.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/security/LocalAccountChanged.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/LocalUserDetails.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionAccessService.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionRejectionHandler.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionRevocationService.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionRevoked.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionValidationFilter.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/SessionWebSocketGuard.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/DbUserDetailsService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/UserService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/web/UserSessionController.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/web/WebUiController.java` | Updated |
| `src/main/resources/application.properties` | Updated |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/main/resources/static/js/configuration-center.js` | Updated |
| `src/main/resources/templates/fragments/admin-users.html` | Updated |
| `src/main/resources/templates/login.html` | Updated |
| `src/main/resources/templates/pages/users.html` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationAllFieldsTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationCoreTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/identity/LdapSessionRegistrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/SessionAccessServiceTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/SessionLifecycleHttpIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/SessionRevocationIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/SessionWebSocketGuardTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/service/UserServiceTest.java` | Updated |
| `tools/verify-session-revocation.py` | Added |
| `tools/verify-session-ui.py` | Added |

## Previous cumulative edition

# Cumulative changes — issues #13–#21

This complete source retains fixes #13–#20 and adds token-root job privacy and canonical path authorization (#21). The table compares this edition with `clamav-gui-issues13-20-fixed.zip`.

| File | Change |
|---|---|
| `README.md` | Updated |
| `SECURITY_ISSUE_21.md` | Added |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/MCP.md` | Updated |
| `docs/PHASE1_API_SECURITY.md` | Updated |
| `docs/TOKEN_PATH_SCOPES.md` | Added |
| `docs/security/token-path-scopes-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/api/v1/ApiV1Controller.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/api/v1/ScopedJobPages.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/ApiV1Access.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/security/TokenJobScope.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/ApiCredentialService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/util/PathPolicy.java` | Updated |
| `src/main/resources/openapi/clamav-v1.yaml` | Updated |
| `src/main/resources/templates/pages/api-tokens.html` | Updated |
| `src/test/java/info/trizub/clamav/webclient/api/v1/ApiV1PathScopeIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/service/DaemonPolicyScanBoundaryTest.java` | Updated |
| `tools/verify-token-path-scopes.py` | Added |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Tokens with nonempty roots see only in-root PATH/WATCH jobs and their own API uploads, within endpoint grants. Lists, details, progress and linked audit use the same policy. Escaped SQL candidate filters and final Java path/ownership checks prevent wildcard/collation overmatches from leaking records or page totals. Restricted queries traverse metadata in bounded 500-row cursor batches; there is no hard coverage cap.

Issuance stores canonical existing directory roots inside canonical global roots. Legacy unresolved/unavailable alias grants fail closed and should be reissued. Path submission passes its canonical authorization result into job creation. Workers reject queued PATH/WATCH targets resolving elsewhere before a daemon command. Empty-root credentials retain endpoint-wide history reads while denying path submission; session/Basic role behavior remains unchanged.

No schema migration or Configuration Center field is added. Keep database, conf/data and keys; rebuild/recreate the application with existing settings. Review legacy alias grants and noncanonical queued paths. See `docs/TOKEN_PATH_SCOPES.md` for ownership/audit semantics, history behavior, paging cost and filesystem limits. Earlier private native-scanner migration remains applicable to upgrades from before #18.

Validation: 751 Java tests passed, five existing AF_UNIX cases skipped, zero failures/errors; 25 packaged authorization HTTP checks and all 36 Configuration Center checks passed. H2 case-insensitive equality, 1,201 visible jobs, 501 audit events, canonical aliases and deterministic authorization/queue redirects were verified. The current HTTP fixture uses synthetic daemon verdicts. Prior native-engine, Docker/deployment and live external SQL reports retain their respective scope; these were not rerun for #21.

No GitHub repository write or release publication was performed. Current evidence is `docs/security/token-path-scopes-validation.json`. Earlier change lists and validation records below retain their original edition scope.

---

# Cumulative changes — issues #13–#20

This complete source retains fixes #13–#19 and adds bounded ClamD TCP/Unix network work and a scheduler pool (#20). The table compares this edition with `clamav-gui-issues13-19-fixed.zip`.

| File | Change |
|---|---|
| `BUG_ISSUE_20.md` | Added |
| `README.md` | Updated |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/CLAMD_TIMEOUTS.md` | Added |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/security/clamd-timeouts-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/api/v1/ApiV1Controller.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/config/ClamdClientConfiguration.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/config/ClamdClientProperties.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/metrics/ScanMetrics.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ClamavClientProvider.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ClamdAddressResolver.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/ClamdTopService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/NioClamdConnection.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/SocketClamdClient.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/TcpClamdClient.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/UnixClamdClient.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/web/WebUiController.java` | Updated |
| `src/main/resources/application.properties` | Updated |
| `src/test/java/info/trizub/clamav/webclient/api/v1/ApiV1CliSupportIntegrationTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/api/v1/ClamdProbeTimeoutIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/config/ClamdSchedulingTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationConsumerWiringTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/metrics/ScanMetricsTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/TcpClamdClientTest.java` | Added |
| `tools/verify-clamd-timeouts.py` | Added |
| `tools/verify-configuration-center.py` | Updated |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Health snapshots share a five-second PING/VERSION/STATS deadline, including bounded DNS and three-second connect work. TCP and Unix sockets use shared nonblocking framing, read/write deadlines and a 4 MiB reply cap. INSTREAM/MULTISCAN have a separate total scan budget (default 24h); stalled upload writes are bounded. Native upload scans use this client. Windows path conversion is retained. Timeout scan metrics use the existing bounded TIMEOUT label.

An explicit scheduler defaults to four threads and accepts 4–64, including with virtual threads enabled. Actual packaged HTTP checks proved configuration polling updated an endpoint while the metrics probe waited on a silent peer; independent scheduled task tests and HTTP error/closure checks passed. The complete Java suite passed 729 cases with five existing AF_UNIX skips. Both real ClamAV native scenarios and all 36 Configuration Center HTTP checks passed.

No schema migration or Configuration Center field is added. Timeout/pool overrides are startup deployment settings. Keep database, conf/data and encryption keys; rebuild/recreate the application with existing settings. Upgrades from before #18 still need its native-scanner UID/private-upload migration. See `docs/CLAMD_TIMEOUTS.md` and `docs/UPLOAD_RETENTION.md`.

No GitHub repository write or release publication was performed. Docker image/network execution was not run locally; unchanged Compose/helper results retain their prior scope. Current evidence is `docs/security/clamd-timeouts-validation.json`. Earlier change lists and validation records below retain their original edition scope.

---

# Cumulative changes — issues #13–#19

This complete source retains fixes #13–#18 and adds bounded upload/quarantine filenames, visible quarantine failures and short upload metadata transactions (#19). The table compares this edition with `clamav-gui-issues13-18-fixed.zip`.

| File | Change |
|---|---|
| `BUG_ISSUE_19.md` | Added |
| `README.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/MCP.md` | Updated |
| `docs/UPLOAD_FILENAMES.md` | Added |
| `docs/UPLOAD_RETENTION.md` | Updated |
| `docs/security/upload-filenames-validation.json` | Added |
| `scripts/smoke-eicar.sh` | Updated |
| `scripts/test-eicar-native.py` | Updated |
| `src/main/java/info/trizub/clamav/webclient/api/v1/ApiV1Dtos.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/UploadJobCommitter.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/UploadStorageService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/util/UploadNames.java` | Added |
| `src/main/resources/application.properties` | Updated |
| `src/main/resources/openapi/clamav-v1.yaml` | Updated |
| `src/test/java/info/trizub/clamav/webclient/api/v1/ApiV1CliSupportIntegrationTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/UploadBatchPersistenceTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/UploadStorageTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/WatcherPersistenceTest.java` | Updated |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Submitted filenames remain readable bounded job labels. Stored upload components are ASCII and at most 153 bytes; quarantine uses 36-byte ID names. Quarantine failures are persisted and visible in job details and both job APIs as `FAILED` / `QUARANTINE_FAILED`, preserving the positive `VIRUS_FOUND` verdict and masking private paths.

Copying and SHA-256 hashing share one pass outside database transactions. A separate Spring-managed transaction saves and flushes the complete batch and enqueues after commit. Open EntityManager in View defaults to false, and an ambient caller transaction is rejected before any copy. Failed-copy/rollback cleanup, in-flight leases, post-commit queue recovery and retention remain protected.

No schema migration or Configuration Center field is added. Retain database, conf/data and encryption keys. Rebuild/recreate the application using existing deployment settings. If upgrading from before #18, also follow its scanner UID and private-upload migration instructions. See `docs/UPLOAD_FILENAMES.md` and `docs/UPLOAD_RETENTION.md`.

Validation: 703 Java tests passed, 5 existing skipped, zero failures/errors; a one-connection pool stayed available during three concurrent blocked copies. Real ClamAV normal/forced-quarantine-failure runs passed 400-character labels, malware/clean verdicts, cleanup and Linux isolation. All 36 packaged Configuration Center checks passed. Docker image/network execution was not run locally; unchanged Compose/helper evidence retains its prior scope. Current evidence is `docs/security/upload-filenames-validation.json`.

No GitHub repository write or release publication was performed by this archive delivery. Earlier change lists and reports below retain their original edition scope; the upload transaction wording above supersedes #18's surrounding-transaction implementation.

---

# Cumulative changes — issues #13–#18

This complete source retains security fixes #13–#17 and adds upload cleanup/retention (#18). The table compares this edition with `clamav-gui-issues13-17-fixed.zip`.

| File | Change |
|---|---|
| `.github/workflows/phase0-ci.yml` | Updated |
| `BUG_ISSUE_18.md` | Added |
| `Dockerfile.clamd` | Added |
| `README.md` | Updated |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `build-and-start.sh` | Updated |
| `docker-compose.yml` | Updated |
| `docs/CLAMD_ISOLATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_CENTER.md` | Updated |
| `docs/CONFIGURATION_FIELD_AUDIT.md` | Updated |
| `docs/CONFIGURATION_REFERENCE.md` | Updated |
| `docs/UPLOAD_RETENTION.md` | Added |
| `docs/security/upload-retention-validation.json` | Added |
| `examples/compose/shared-paths.override.yaml` | Updated |
| `scripts/smoke-eicar.sh` | Updated |
| `scripts/test-clamd-compose-isolation.py` | Updated |
| `scripts/test-eicar-native.py` | Updated |
| `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/repo/AuditEventRepository.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/repo/ScanJobRepository.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/SettingsService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/StorageRetentionService.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/UploadStorageService.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/util/PrivateFiles.java` | Added |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/UploadBatchPersistenceTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/service/UploadStorageTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/service/WatcherPersistenceTest.java` | Updated |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

New upload storage tracks files through copy, transaction completion and rollback. Both browser/API submission overloads are transactional; full/partial batch files are removed on failure or confirmed rollback. Streams close before completion cleanup. Clean uploaded copies are removed by default; infected copies are quarantined or deleted. Owner-only POSIX storage is enforced. A scheduled, database-aware retention task protects active/in-progress work, expires managed orphan/completed uploads and offers separate opt-in job/audit history pruning. Existing PATH/WATCH sources are outside upload deletion.

Four live GUI/deployment settings are added. The bundled native scanner is derived from the same pinned official ClamAV image with its account mapped to UID/GID 10001 so private uploads remain readable through the existing narrow read-only mount. The standalone INSTREAM scanner remains the official image directly. Compose/CI/smoke checks cover the adapted recipes and clean/infected completion behavior.

No schema migration is required. Retain the database, conf/data and keys. For the bundled native setup, rebuild/recreate BOTH services using `docker compose up -d --build --force-recreate clamav-server clamav-web-client`. For a separately managed scanner, match the upload owner UID or use INSTREAM. See `docs/UPLOAD_RETENTION.md` for settings, default deletion policy, quarantine/history safeguards and filesystem limits.

No GitHub repository write or release publication was performed by this archive delivery. Current executed validation is in `docs/security/upload-retention-validation.json`; earlier lists and reports below retain their original edition scope.

---

# Cumulative changes — security issues #13–#17

This complete source retains issues #13–#16 and fixes watcher coverage and failed-submission handling (#17). This table compares the edition with `clamav-gui-issues13-16-fixed.zip`.

| File | Change |
|---|---|
| `README.md` | Updated |
| `SECURITY_ISSUE_17.md` | Added |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_FIELD_AUDIT.md` | Updated |
| `docs/CONFIGURATION_REFERENCE.md` | Updated |
| `docs/WATCHER_RELIABILITY.md` | Added |
| `docs/security/watcher-reliability-validation.json` | Added |
| `src/main/java/info/trizub/clamav/webclient/model/ProcessedFile.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/repo/ProcessedFileRepository.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/service/WatchFileSubmissionService.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/service/WatcherService.java` | Updated |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/WatcherConfigurationTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/service/WatcherCoverageTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/service/WatcherPersistenceTest.java` | Added |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Polls traverse every eligible regular file without count/depth limits, do not overlap and log path-specific failures and directory totals. A separate transactional submission service commits WATCH jobs and replacement fingerprints together; previous fingerprint objects remain unchanged on failure. Fingerprints load in one escaped directory-prefix query, and traversal roots resolve once per poll. A global hourly pruning task visits all marker rows in primary-key batches and removes confirmed absent paths.

Twelve new tests plus the three retained scheduling tests cover 2500 durable jobs with no duplicate second-poll submissions, creation/marker-write rollback and retry, post-commit queue recovery, deep traversal, failure continuation/logging, symlinks, concurrency, prefix escaping and multi-batch cleanup. The current validation record distinguishes real database transactions from mocked worker enqueueing.

No database schema migration is required. Existing watch registrations and fingerprints remain. Rebuild/recreate using the existing data, database, configuration and keys. `docs/WATCHER_RELIABILITY.md` explains clearing only the fingerprint cache if a full resubmission of potentially affected legacy files is needed. No GitHub repository write or release publication was performed by this archive delivery.

The earlier change lists and validation records below describe their own editions. Current verification is in `docs/security/watcher-reliability-validation.json`.

---

# Cumulative changes — security issues #13–#16

This complete source retains issues #13–#15 and fixes stored LDAP/OIDC credential redirection (#16). The table compares this edition with `clamav-gui-issues13-15-fixed.zip`.

| File | Change |
|---|---|
| `README.md` | Updated |
| `SECURITY_ISSUE_16.md` | Added |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_CENTER.md` | Updated |
| `docs/CONFIGURATION_REFERENCE.md` | Updated |
| `docs/PHASE31_AUTH_MANAGEMENT.md` | Updated |
| `docs/PROVIDER_SECRET_BINDING.md` | Added |
| `docs/security/provider-secret-binding-validation.json` | Added |
| `scripts/test-provider-secret-runtime.py` | Added |
| `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationEngine.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/identity/AuthenticationSettingsService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/identity/ProviderSecretGuard.java` | Added |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationAllFieldsTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationProviderSecretTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationProviderSecretUiTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/identity/AuthenticationSettingsSecretBindingTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/identity/LdapSettingsCompatibilityTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/identity/OidcSettingsCompatibilityTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/identity/Phase31AuthenticationUiTest.java` | Updated |
| `CHANGED_FILES.md` | Updated cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Shared review, test and apply validation requires credential re-entry when LDAP URL, StartTLS, base DN, manager DN or referrals, or OIDC issuer/client ID changes. Blank credentials still reuse the saved value when protected fields are unchanged. The check also covers disabled providers, file-backed credentials, recovery and legacy service callers. Newly saved legacy SQL ciphertext is provider-bound; old ciphertext remains readable and upgrades on save.

The 29 new Java regressions cover rejection, unchanged destinations, explicit re-entry, no outbound call, encrypted legacy compatibility and provider-row swaps. Packaged HTTP checks exercise recovery and authenticated administration against local connection sentinels, then verify restart persistence. Existing compatibility tests now retain their original destination when relying on a blank credential; the all-fields test explicitly supplies credentials for protected-field changes.

No database schema migration or forced credential rotation is required. Retain existing database/configuration and their keys. See `docs/PROVIDER_SECRET_BINDING.md` for file-backed sources and rollback compatibility. No GitHub repository write or release publication was performed by this archive delivery.

The earlier change lists and validation records below describe their own editions; current verification is in `docs/security/provider-secret-binding-validation.json`.

---

# Cumulative changes — security issues #13, #14 and #15

This full source retains issues #13/#14 and adds bundled scanner isolation (#15). This table compares the edition with clamav-gui-issues13-14-fixed.zip.

| File | Change |
|---|---|
| `.env.example` | Updated |
| `.github/ci/compose-smoke.override.yaml` | Added |
| `.github/workflows/phase0-ci.yml` | Added |
| `README.md` | Updated |
| `SECURITY_ISSUE_15.md` | Added |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `build-and-start.sh` | Updated |
| `docker-compose.yml` | Updated |
| `docs/CLAMD_ISOLATION.md` | Added |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/SECURITY_AND_DEPLOYMENT.md` | Updated |
| `docs/security/clamd-image-pin.json` | Added |
| `docs/security/clamd-isolation-validation.json` | Added |
| `examples/compose/clamd-loopback.override.yaml` | Added |
| `examples/compose/shared-paths.override.yaml` | Updated |
| `examples/compose/standalone-h2.yaml` | Updated |
| `scripts/smoke-eicar.sh` | Updated |
| `scripts/test-clamd-compose-isolation.py` | Added |
| `scripts/test-eicar-native.py` | Added |
| `CHANGED_FILES.md` | Updated: cumulative/incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Issue #15 removes default scanner host publication and broad data mounts, sets an explicit shared upload path, pins the scanner image by version/digest and retains optional blank REST settings. It updates the helper to prepare uploads before daemon creation, fixes the EICAR smoke's current signatures DTO field and automatic web-port resolution, and adds rendered-Compose checks, Docker CI runtime isolation checks and real native/isolated-filesystem EICAR checks.

Migration: remove old daemon port/broad data mount entries from their actual Compose source, prepare data/uploads, and recreate the affected services. A new narrow override does not remove an older parent mount during Compose merging. Keep Web UI database credentials, conf/data contents and keys; no DB migration is needed. See docs/CLAMD_ISOLATION.md.

The earlier file lists and their scoped verification records follow. Earlier claims about unchanged lockout code or executed test counts describe their respective issue editions, not new issue #15 execution. No GitHub repository write or release publication was performed by this archive delivery.

---

# Cumulative changes — issues #13 and #14

This full source archive retains the issue #13 forwarded-header protection and adds issue #14 password lockout. The following table compares this edition with clamav-gui-issue13-fixed.zip.

| File | Change |
|---|---|
| `README.md` | Updated |
| `SECURITY_ISSUE_14.md` | Added |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `docs/BASIC_AUTH_LOCKOUT.md` | Added |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/PHASE3_ENTERPRISE_IDENTITY.md` | Updated |
| `docs/SECURITY_AND_DEPLOYMENT.md` | Updated |
| `docs/security/basic-auth-lockout-validation.json` | Added |
| `scripts/test-basic-lockout-runtime.py` | Added |
| `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/identity/LoginAttemptService.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/security/BasicLoginLockoutFilter.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/identity/LoginAttemptServiceTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/security/BasicAuthenticationLockoutIntegrationTest.java` | Added |
| `CHANGED_FILES.md` | Updated: cumulative and incremental file lists |
| `SOURCE_MANIFEST.sha256` | Regenerated for the complete source archive |

Issue #14: HTTP Basic failure/success event accounting, a pre-authentication lockout check with 429/Retry-After, a shared local Basic/form account budget across IPs, independent LDAP budget, expiry/reset protection, real-server and packaged-JAR regressions, and updated operator documentation. The existing five-failure threshold is retained; the 15-minute lockout now starts when the threshold is reached. No database migration or deployment flag was added.

The issue #13 file list and its original scope are retained below. Its statement about no lockout implementation change describes issue #13 only; issue #14 intentionally updates that implementation.

---

# Changed files — issue #13

Compared with the uploaded clamav-gui.zip. Full source is included. No database schema, login lockout algorithm, scan limiter algorithm or audit persistence implementation was changed; these now receive transport information protected by the selected forwarding policy.

| File | Change |
|---|---|
| `README.md` | Updated |
| `SECURITY_ISSUE_13.md` | Added |
| `SERVER_DEPLOYMENT.md` | Updated |
| `SERVER_PACKAGE_VALIDATION.json` | Updated |
| `VALIDATION.md` | Updated |
| `build-and-start.sh` | Updated |
| `docker-compose.yml` | Updated |
| `docs/COMPLETE_USER_GUIDE.md` | Updated |
| `docs/CONFIGURATION_FIELD_AUDIT.md` | Updated |
| `docs/CONFIGURATION_REFERENCE.md` | Updated |
| `docs/PHASE0_BUILD_FIX_2.md` | Updated |
| `docs/REVERSE_PROXY_TLS.md` | Updated |
| `docs/SECURITY_AND_DEPLOYMENT.md` | Updated |
| `docs/UPDATE_AND_VERIFY.md` | Updated |
| `docs/UPDATE_NOTES_2026_10_31.md` | Updated |
| `docs/security/forwarded-header-trust-validation.json` | Added |
| `examples/compose/.env.example` | Updated |
| `examples/compose/remote-clamd-postgres.yaml` | Updated |
| `examples/compose/reverse-proxy.override.yaml` | Updated |
| `examples/compose/standalone-h2.yaml` | Updated |
| `scripts/test-clamctl-integration.py` | Updated |
| `scripts/test-forwarded-header-runtime.py` | Added |
| `scripts/test-server-deployment-helper.py` | Updated |
| `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationBootstrap.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java` | Updated |
| `src/main/java/info/trizub/clamav/webclient/configuration/RecoveryProxyHeaders.java` | Added |
| `src/main/java/info/trizub/clamav/webclient/security/ApiTokenAuthenticationFilter.java` | Updated |
| `src/main/resources/application.properties` | Updated |
| `src/main/resources/configuration-center/catalog.json` | Updated |
| `src/test/java/info/trizub/clamav/webclient/configuration/RecoveryProxyHeadersTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/ApiTokenDirectTlsIntegrationTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/security/ApiTokenNativeWithoutAllowlistIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/ApiTokenProxyTlsIntegrationTest.java` | Updated |
| `src/test/java/info/trizub/clamav/webclient/security/ApiTokenUntrustedProxyIntegrationTest.java` | Added |
| `src/test/java/info/trizub/clamav/webclient/security/ProxyBearerTestSupport.java` | Updated |
| `tools/reconcile_current_source.py` | Updated |
| `tools/verify-configuration-center.py` | Updated |
| `CHANGED_FILES.md` | Added: this file list |
| `SOURCE_MANIFEST.sha256` | Regenerated: integrity manifest for this package |

Application changes: safe defaults in the packaged properties and Configuration Center; explicit native-mode proxy allowlists; recovery listener trust checks; corrected TLS diagnostics; direct and opt-in proxy Compose examples and helper; real embedded-server and packaged recovery regressions; consistent operator guidance.

Packaging: the uploaded installation's PostgreSQL credentials, local endpoints and private truststore options were replaced with environment placeholders in the reusable root Compose. Existing deployment values and conf/data volumes must be retained. Generated target/data, runtime conf files, credentials/keys/certificates, logs, caches and obsolete JARs from the upload are excluded; the example conf file remains. These exclusions do not remove files from a deployed installation.

The source manifest covers every packaged file except itself. No GitHub repository write or release publication was performed as part of this archive fix.
