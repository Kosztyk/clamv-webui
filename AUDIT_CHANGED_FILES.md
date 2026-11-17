# Comprehensive source review — changed files

- `scripts/issue53-prepare-baseline.py`: replace pre-existing baseline CI quality profile instead of failing.
- `scripts/test-issue53-java-quality.py`: post-merge baseline and release Spotless negative checks.
- `scripts/ci-detect-changes.py`: select all jobs for CI/release/control-plane edits.
- `scripts/test-ci-gate.py`: regression for full test matrix on control-plane changes.
- `.github/workflows/release.yml`: prevent self-referential Spotless ratchet (`HEAD^` instead of `HEAD`).
- `scripts/check-issue53-java-quality.py`: require nontrivial release formatter baseline.
- `pom.xml`: correct SCM link to maintained repository.
- `scripts/test-full-source-audit-regressions.py`: new cross-issue regression suite.
- `.github/workflows/ci.yml`: run audit regressions in always-reporting static job.
- `scripts/release-gate.sh`: run audit regressions in static and full release gates.
- `README.md`: link to the source audit.
- `docs/COMPREHENSIVE_SOURCE_REVIEW_2026-11-14.md`: severity-ranked technical findings and limitations.
- `AUDIT_CHANGED_FILES.md` and `AUDIT_VALIDATION_RESULTS.md`: audit inventory and validation report.
- `SOURCE_MANIFEST.sha256`: recomputed complete-source manifest.

No application feature, persistent schema, Docker image, authentication behavior,
API contract, or scan scheduling behavior has been changed in this safety-focused patch.
