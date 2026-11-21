# Issue #53 — Changes against issue #52 full-source baseline

- `pom.xml` — Maven Enforcer rules and JaCoCo coverage instrumentation/check in ordinary builds; `ci-quality` profile adds pinned Spotless + Google Java Format, SpotBugs + FindSecBugs, PMD and dependency convergence.
- `.github/workflows/ci.yml` — JDK 21/25 test matrix, JaCoCo uploads, `java-quality` job with trusted base comparison, final `gate` dependency, static regression checks.
- `.github/workflows/release.yml` — rerun Java quality and Spotless against tag commit, preserve JaCoCo/static reports, skip coverage-only threshold on narrow vendor DB migration tests.
- `scripts/ci-evaluate-gate.py`, `scripts/check-ci-workflow.py`, `scripts/ci-detect-changes.py` — wire the quality job to the required gate and correct change scope.
- `scripts/release-gate.sh` — include static quality-contract and adversarial checks.
- `scripts/issue53-prepare-baseline.py` — inject identical scanner profile into detached historical worktree.
- `scripts/issue53-compare-quality.py` — fail closed on new per-rule static findings and missing/malformed reports.
- `scripts/check-issue53-java-quality.py`, `scripts/test-issue53-java-quality.py` — quality-policy and negative tests.
- `scripts/check-issue43-legacy-api.py` — update stale literal command check following CI `clean verify` matrix conversion, preserving all its original assertions.
- `README.md`, `docs/ISSUE53_JAVA_QUALITY_GATES.md`, `docs/releases/v2.1.0beta2.md` — behavior, limitations, usage and upgrade details.
- `ISSUE53_VALIDATION_RESULTS.md`, `SOURCE_MANIFEST.sha256` — generated reports and source integrity.

No application runtime paths, database migrations, configuration keys, or runtime Docker configuration were changed.
