# Issue #57 — changed source inventory

- `.github/workflows/ci.yml` — PR H2/PostgreSQL upgrade-path matrix and the required unified gate.
- `.github/workflows/release.yml` — prerelease/tag image validation matrix, dependencies blocking image promotion and GitHub draft release.
- `scripts/ci-detect-changes.py`, `scripts/ci-evaluate-gate.py`, `scripts/check-ci-workflow.py` — change classification and fail-closed gate model.
- `config/upgrade-baseline.json` — previous published release and immutable Git SHA, expected Flyway versions.
- `src/test/resources/upgrade-fixtures/v2.1beta1/` — synthetic historical data seed and frozen prior Flyway H2 V1/V3/V4 schema.
- `src/test/java/info/trizub/clamav/webclient/db/Issue57H2UpgradeFixtureTest.java` — fast H2 migration and encryption preservation test.
- `scripts/Issue57JdbcProbe.java` — Java 21 JDBC seed, schema-history and persisted row assertions.
- `scripts/issue57-upgrade-path.py` — real previous-image to candidate-image Docker upgrade via shared H2/PostgreSQL and preserved config files.
- `scripts/check-issue57-upgrade-path.py`, `scripts/test-issue57-upgrade-path.py` — structural/adversarial security and migration policies.
- `scripts/check-issue49-release.py`, `scripts/check-issue51-container-release.py` — inherited release-policy tests kept compatible with required new dependency.
- `scripts/release-gate.sh`, `README.md`, `RELEASE.md`, `CHANGELOG.md`, `docs/releases/v2.1.0beta2.md` — release runbook and gate integration.
- `docs/ISSUE57_UPGRADE_PATH_GATE.md`, `ISSUE57_VALIDATION_RESULTS.md`, `SOURCE_MANIFEST.sha256` — documented validation and refreshed source integrity.
