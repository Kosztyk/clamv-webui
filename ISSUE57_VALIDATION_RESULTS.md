# Issue #57 — validation results (offline source package)

## Scope and predecessor provenance

- Latest **published** GitHub release inspected: `v2.1beta1` (2026-11-12), annotated tag resolving to immutable commit `d06ea6a8ea4d2fd6c89fea1c4be7f55707c77eb3`.
- The prior release H2 Flyway history includes SQL migrations V1, V3 and V4; Java V2 is present. The candidate contains further migrations through V8. The frozen H2 V1/V3/V4 fixture content has been cross-checked against the actual GitHub release tag by blob SHA.
- The release workflow requires **both** `upgrade-path` matrix vendors (H2, PostgreSQL) before **`image-publish`** (GHCR tag promotion) and **`draft`** (GitHub release creation). The PR workflow includes this matrix in the single required `gate`.

## Executed successfully in this environment

- `python3 scripts/check-issue57-upgrade-path.py`: PASS; verifies pinned historical tag, matrix vendors, preserved auth/DB/config checks and no release/CI bypass.
- `python3 scripts/test-issue57-upgrade-path.py`: PASS; detects deletion/tampering of users, roles, API token, scan job, endpoints, audit, LDAP/OIDC AES-GCM secrets, missing fixture fields, moved Git tag, omitted migration jobs and disabled upgrade checks.
- Java 21 standalone JDBC probe `scripts/Issue57JdbcProbe.java`: compiled with `javac`.
- Python source compilation and Bash script syntax checks: PASS.
- `python3 scripts/check-ci-workflow.py`, `python3 scripts/test-ci-gate.py`: PASS.
- `python3 scripts/check-issue49-release.py`, `python3 scripts/test-issue49-policy.py`: PASS.
- `python3 scripts/check-issue51-container-release.py`, `python3 scripts/test-issue51-policy.py`: PASS.
- Independent issue #36–#46 source checks: PASS, twelve checks.
- Core issue #48–#57 version, CI, security, Java quality, Go quality, OpenAPI and release policy checks: PASS.
- `python3 scripts/check-issue56-release-docs.py` and `python3 scripts/test-issue56-release-docs.py`: PASS, including eight privacy/documentation tests.
- `python3 tools/audit-source-package.py .`: PASS; no embedded private infrastructure addresses or prohibited deployment secrets.

## Not executed / not certified locally

- The combined `bash scripts/release-gate.sh --static-only` exceeded the local execution limit. Do **not** interpret this as a pass; its principal constituent checks were executed separately.
- The full Maven 21/JUnit H2 fixture, Docker/Testcontainers PostgreSQL migration and complete old-image→candidate-image HTTPS Basic/bearer login and persistence tests require Maven and Docker, neither available in this environment.
- The live GitHub Actions `gate`, release tag run and GHCR publishing also remain unverified; do not claim acceptance criteria complete until those runs are green.
- The local upgrade builds the *candidate source tree* as a Docker image **before** GHCR image publishing. The separately blocked issue #51 pipeline subsequently builds, signs, scans and smoke-tests the actual pushed digest. This does not constitute a single identical image digest exercised by the migration step; an additional digest-upgrade check can be added if byte-identical artifact assurance is required.

## Safety controls

- All H2/PostgreSQL fixtures are disposable and use synthetic data and encryption keys; no production database or home network IP address is required.
- Production upgrade must back up **both** the database and the complete `conf/` volume (including `auth-settings.key`) before any upgrade; do not turn on `SPRING_FLYWAY_BASELINE_ON_MIGRATE` for normal already-migrated databases.
- On any unexpected migration/health failure, the release gate must fail and no new versioned GHCR tag or GitHub draft may be published.
