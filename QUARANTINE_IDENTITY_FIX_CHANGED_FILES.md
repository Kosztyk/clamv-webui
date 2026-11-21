# ClamAV Web UI — Quarantine identity + mixed-warning fix changed-file inventory

Baseline: `clamav-webui-v2.1.0beta2-six-recovery-fixes-full-source.zip`

Modified files (17):

- `AUDIT10_UPGRADE_NOTES.md`
- `CHANGELOG.md`
- `docs/QUARANTINE_RECOVERY_V10.md`
- `docs/releases/v2.1.0beta2.md`
- `scripts/check-issue44-ldap-identity.py`
- `scripts/test-2026-cumulative-regressions.py`
- `src/main/java/info/trizub/clamav/webclient/model/QuarantineArtifact.java`
- `src/main/java/info/trizub/clamav/webclient/model/ScanJob.java`
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineArtifactCoordinator.java`
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineArtifactLedger.java`
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineService.java`
- `src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java`
- `src/main/java/info/trizub/clamav/webclient/service/ScanJobFinalizationService.java`
- `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationConsumerWiringTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/QuarantineArtifactCoordinatorTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/QuarantineRecoveryCompletenessTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/ScanJobFinalizationTransactionTest.java`

New files before audit reports (8):

- `docs/QUARANTINE_IDENTITY_V11.md`
- `src/main/java/info/trizub/clamav/webclient/service/QuarantineFileIdentity.java`
- `src/main/resources/db/migration/h2/V11__quarantine_content_evidence.sql`
- `src/main/resources/db/migration/mariadb/V11__quarantine_content_evidence.sql`
- `src/main/resources/db/migration/mysql/V11__quarantine_content_evidence.sql`
- `src/main/resources/db/migration/postgresql/V11__quarantine_content_evidence.sql`
- `src/test/java/info/trizub/clamav/webclient/db/QuarantineIdentityMigrationTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/QuarantineEvidenceRegressionTest.java`

Removed files: none. Two new audit documents and a regenerated `SOURCE_MANIFEST.sha256` are added in the final package.
