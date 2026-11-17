# Issue #48 — Changed files

Baseline: `clamav-webui-v2.1.0beta2-issue46-service-layer-cleanup-full-source.zip`.
This is a **full-source replacement ZIP**, not a patch. No application
endpoints, persisted configuration, Flyway SQL, Docker Compose settings or
user-facing Configuration Center fields were changed.

## Added
- `.github/workflows/ci.yml` — single always-reporting PR/main CI with
  change selection, bounded jobs, artifacts, and final required gate.
- `scripts/ci-detect-changes.py` — fail-closed diff-based job selection.
- `scripts/ci-evaluate-gate.py` — status evaluator distinguishing intentional
  skipped jobs from unexpected skips, cancellations, and failures.
- `scripts/check-ci-workflow.py`, `scripts/test-ci-gate.py` — structural and
  adversarial tests, including a temporary real Git repository.
- `scripts/ci-browser-requirements.txt` — pinned Playwright runtime.
- `scripts/check-static-inventory.py`, `scripts/check-db-inventory.py`,
  `scripts/check-monitoring-inventory.py` — descriptive inventory entry points.
- `docs/ISSUE48_CI_REQUIRED_GATE.md` and this change/validation report.

## Replaced / removed
- Removed eight+ issue-specific PR workflows superseded by `ci.yml`:
  issues #37, #38, #41, #42, #43, #44, #45, #46.
- Kept `.github/workflows/beta-prerelease.yml` as tag-only, adding a timeout
  and Maven cache.
- Retained the old phase-numbered static script names as working wrappers.
- Updated the issue #43 and #45 offline contracts and #45 adversarial CI
  checker to target the consolidated workflow.
- `src/test/java/info/trizub/clamav/webclient/db/Phase2DatabaseIT.java`
  accepts a selected vendor for a three-leg Testcontainers matrix; the
  normal `-Pdb-integration` runs all three, and CI fails instead of silently
  skipping when Docker is unavailable.
- `scripts/test-native-clamdtop-browser.py` records Playwright traces in CI,
  including failure cases.
- `scripts/check-i18n.py`, `scripts/release-gate.sh`,
  `scripts/clamav-release-gate.sh` ensure static-only checks do not depend on
  live browser navigation.
- Updated README, CLI documentation, scanner isolation/deployment and supply
  chain guides, beta release notes, and the checksum manifest.
