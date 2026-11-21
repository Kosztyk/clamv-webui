# Issue #49 — changed files

Baseline: full-source issue #48 archive (unified required CI gate).

- Added `.github/workflows/release.yml`: verified tag trigger + manual dry run;
  main gate provenance; full Maven/Docker/browser/Trivy release gate;
  PostgreSQL/MySQL/MariaDB Testcontainers; six CLI builds; checksummed assets;
  draft creation and protected release publication.
- Removed `.github/workflows/beta-prerelease.yml`: bypass publisher superseded.
- Added `scripts/release-verify-tag.py`, `scripts/release-assets.py`,
  `scripts/check-issue49-release.py`, `scripts/test-issue49-release.py`
  and `scripts/test-issue49-policy.py`.
- Updated `.github/workflows/ci.yml` to run issue #49 checks in the always
  reporting static job.
- Updated `scripts/release-gate.sh` to run issue #49 policy tests and avoid
  hardcoding a beta-only Maven version. `CLAMAV_REQUIRE_BROWSER=1` enforces
  mandatory Chromium-based identity tests during release runs.
- Updated `scripts/check-issue45-supply-chain.py` and
  `scripts/test-issue45-security-gate.py` to cover `release.yml` rather than
  the removed prerelease publisher.
- Updated `docs/security/ISSUE45_SUPPLY_CHAIN.md`,
  `docs/ISSUE48_CI_REQUIRED_GATE.md`, and
  `docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md`.
- Updated `README.md` and the current beta release notes with release and
  protected-environment usage details.
- Updated `ISSUE49_VALIDATION_RESULTS.md` and `SOURCE_MANIFEST.sha256`.

Application Java, database migrations, and Configuration Center remain
unchanged; the full issue #36–#48 application source is preserved.
