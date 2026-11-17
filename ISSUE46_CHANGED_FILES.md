# Issue #46 changed files

- `src/main/java/info/trizub/clamav/webclient/service/ScanJobService.java` — remove dead methods/overloads; preserve atomic upload commit.
- `src/main/java/info/trizub/clamav/webclient/service/UploadJobCommitter.java` — make the single short metadata transaction explicit/readable.
- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java` — thread IDs, WARN logging, readable executor cleanup.
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java` — WARN-level diagnostics.
- `src/main/java/info/trizub/clamav/webclient/web/WebUiController.java` — direct four-argument API calls with browser transport.
- `src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java` — readability and expected shutdown logging.
- `src/main/java/info/trizub/clamav/webclient/identity/{DynamicLdapAuthenticationProvider,LdapDirectoryIdentity,LdapDirectoryIdentityPolicy,LazyOidcClientRegistrationRepository,AuthenticationSettingsService}.java` — limited readability cleanup.
- `src/test/java/info/trizub/clamav/webclient/service/{UploadBatchPersistenceTest,DisabledEngineScanTest,DaemonPolicyScanBoundaryTest,Issue28StartupRecoveryTest}.java` — shared four-argument paths and rollback tests.
- `src/test/java/info/trizub/clamav/webclient/{configuration/ConfigurationConsumerWiringTest,security/RateLimitSecurityIntegrationTest}.java` — updated mocks and test constructors.
- `scripts/check-issue44-ldap-identity.py` — accept equivalent whitespace in legacy-hash source assertion.
- `scripts/check-issue46-service-layer.py` — source guard.
- `.github/workflows/issue46-service-layer.yml` — Maven/Spring acceptance tests in CI.
- `README.md`, `docs/releases/v2.1.0beta2.md`, `docs/maintenance/ISSUE46_SERVICE_LAYER_CLEANUP.md` — feature and test documentation.
- `SOURCE_MANIFEST.sha256` — regenerated for the complete source archive.
