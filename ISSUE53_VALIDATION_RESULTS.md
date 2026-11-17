# Issue #53 — Validation report (2026-11-13)

**Baseline:** `clamav-webui-v2.1.0beta2-issue52-security-scanning-gates-full-source.zip` (complete source, 1,316 files). **Target:** Java code-quality and CI gate changes only.

## Executed in the offline build workspace

- `python3 scripts/check-issue53-java-quality.py` — PASS: checks Maven Enforcer, JaCoCo, Spotless, SpotBugs/FindSecBugs, PMD, Java 21/25 matrix, CI gate and release integration.
- `python3 scripts/test-issue53-java-quality.py` — PASS: removing JaCoCo threshold, removing the quality gate, replacing `spotless:check` with `apply`, introducing a second SpotBugs or PMD violation, and missing reports are rejected. A historical PMD violation shifted to a new line is accepted as pre-existing debt.
- `python3 scripts/check-ci-workflow.py`, `python3 scripts/test-ci-gate.py` — PASS: 14 stable CI jobs and adversarial gate semantics (including `java-quality`).
- `python3 scripts/check-issue52-security-gates.py`, `python3 scripts/test-issue52-security-gates.py` — PASS: security policy and adversarial scanner tests.
- `python3 scripts/check-issue49-release.py`, `python3 scripts/test-issue49-policy.py`, `python3 scripts/check-issue51-container-release.py`, `python3 scripts/test-issue51-policy.py` — PASS: prior release and GHCR policy checks.
- Prior issue #34 and #36–#52 individual source checks — PASS after updating one stale #43 test that asserted an obsolete exact Maven command text.

**The complete `bash scripts/release-gate.sh --static-only` command exceeded the environment's execution timeout.** It is **not** reported as passing as a whole; individual constituent checks above passed and can be rerun separately.

## NOT executed (must run in GitHub Actions)

- Maven `mvn clean verify`, Maven Enforcer rules and dependency convergence against actual resolved dependencies.
- Spotless Google Java Format execution on Java file changes, SpotBugs/FindSecBugs and PMD on the complete project.
- JaCoCo real line-coverage measurement and verification that the provisional 5% floor is attainable.
- Actual JDK 25 compilation and tests; only installed Java 21 is available locally.
- Live GitHub Actions matrix, PR base-worktree analysis, and tag-triggered release validation.

**Reason:** This environment lacks `mvn`, Docker and external Maven Central connectivity. Enforcer dependency convergence, coverage and plugin configuration might require first-run adjustments. Until those gates run successfully on GitHub, issue #53 should not be closed.

## Acceptance commands on a full Git checkout

```bash
python3 scripts/check-issue53-java-quality.py
python3 scripts/test-issue53-java-quality.py
mvn -B -ntp clean verify
# Fetch the ratchet anchor before Spotless:
git fetch origin main
mvn -B -ntp -Pci-quality -DskipTests -Djacoco.skip=true verify spotless:check
```

PR acceptance: each `java-tests-jdk-{21,25}` lane must produce JaCoCo and test artifacts, `java-quality` must produce valid XML static-analysis reports and reject any new finding relative to its immutable base, and the single required `gate` must pass. A docs-only PR should skip the heavy Java jobs and still report `gate`.
