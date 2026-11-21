# Issue #57 — tested previous-release database upgrades

## Baseline and invariant

The upgrade job selects the newest published earlier-version release for the candidate. For `2.1.0-beta2` it selected `v2.1beta1` at commit `d06ea6a8ea4d2fd6c89fea1c4be7f55707c77eb3`. This release also supplies the frozen fixture identified in `config/upgrade-baseline.json`. Its Flyway migrations end at V4 and the candidate adds V5–V12.

No production credentials, addresses, database dumps or SQL exports are included in the repository. The versioned `src/test/resources/upgrade-fixtures/v2.1beta1/seed.sql.template` creates only synthetic historical records, with runtime-injected random API bearer-token hashes and properly authenticated AES-GCM ciphertext for LDAP/OIDC; the paired frozen V1/V3/V4 H2 SQL files are predecessor-era schema fixtures. Plaintext secrets and actual populated databases remain in a temporary CI runner directory and are never uploaded as artifacts.

## PR gate: fail-closed upgrade-path matrix

`.github/workflows/ci.yml` selects `upgrade-path` for Java, Flyway migrations, application configuration, release/CI scripts and `pom.xml` changes. The `gate` evaluator includes `upgrade-path`, and does **not** treat failed, cancelled or missing selected jobs as successful. Two jobs run independently: `upgrade-h2` and `upgrade-postgresql`. Documentation-only PRs skip the costly container upgrade but still run the static issue #57 policy/negative tests.

For each vendor, the CI runner fetches all Git tags, checks the selected predecessor tag resolves to the immutable SHA found by the resolver, creates an isolated worktree, and builds its original Dockerfile as the previous image. The candidate is built from the current Git checkout; both applications run as the same non-root UID on the *same* mounted `data/` and `conf/` directories. For PostgreSQL, both applications reuse a separate disposable PostgreSQL 17.6 container; for H2, they reopen the **same physical H2 file database**.

1. Boot the genuine predecessor and validate its HTTPS login page and successful migrations.
2. Shut it down. Seed its schema with a local user and two roles, API-token SHA-256 digest, endpoint, completed scan job and audit event, plus AES-GCM encrypted LDAP/OIDC provider settings. Preserve the generated authentication key, integrations config, Configuration Center settings and TLS keystore.
3. Restart the predecessor. Exercise Basic authentication, bearer token identity, endpoint lookup, historical job lookup and readiness. Fail if predecessor fixtures cannot actually be used by its own API.
4. Stop the predecessor cleanly and snapshot historical rows and its full Flyway history.
5. Boot the candidate on the identical persistent stores. Require successful startup, authenticated HTTP API smoke, and every expected Flyway migration applied successfully. The candidate’s expected versions come from its migration files.
6. Stop the candidate and snapshot records; assert exact historical record equality and that the unchanged original encryption key successfully decrypts **both** LDAP and OIDC ciphertext values. Verify persisted key/configuration hashes are unchanged.
7. Upload a small privacy-safe JSON evidence artifact (no token, password, ciphertext or internal IP). Remove temporary containers, network and predecessor worktree even after failure.

A deleted scan job, changed token hash, missing role, lost key, failed Flyway history, broken API login or changed provider secret fails the job. For application exceptions, bounded redacted container logs are uploaded. The runner must provide Docker, Java 21, Maven and Python 3 with `cryptography`.

## Fast fixture feedback without Docker

`src/test/java/info/trizub/clamav/webclient/db/Issue57H2UpgradeFixtureTest.java` loads the frozen predecessor H2 V1/V3/V4 schema and synthetic historical rows into a disposable H2 in-memory database, applies the current Flyway migrations, and verifies preserved records, AES-GCM authenticity, Flyway validation and migration idempotency on restart. Its `baselineOnMigrate(true)` is **only** for this synthetic no-history database. The real container upgrade performs **no baseline-on-migrate** and validates the actual previous release's schema history; no production Flyway state is modified automatically.

## Release gate

The independent `upgrade-path` matrix in `.github/workflows/release.yml` is mandatory for the candidate. `image-publish` and `draft` both depend on it, before GHCR tag promotion or release drafting. No manual fallback or continue-on-error bypass is enabled. Run `python3 scripts/check-issue57-upgrade-path.py && python3 scripts/test-issue57-upgrade-path.py` to verify static wiring; run `python3 scripts/issue57-upgrade-path.py --vendor h2` and `--vendor postgresql` after preparing the Maven JDBC drivers and a Git checkout with all tags to exercise the real upgrade locally. The release process in root `RELEASE.md` requires these two jobs to pass.

## Maintaining the synthetic fixture

When the published predecessor’s schema requires an updated synthetic fixture, record its actual tag and commit SHA in `config/upgrade-baseline.json`. Snapshot the predecessor schema and update the versioned fixture directory and tests. An incompatible fixture or missing Git history fails the gate. See [RELEASE.md](../RELEASE.md) for rollback and controlled legacy database onboarding.

## Validation limits

The audit ran the published predecessor and candidate JARs against persistent H2 and PostgreSQL 17.11 databases. Both passed the authenticated HTTP checks and upgraded from Flyway V4 to V12 while keeping the seeded records and configuration. The two-image Docker verification remains a release gate.
