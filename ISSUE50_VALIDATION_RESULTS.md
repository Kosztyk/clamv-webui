# Issue #50 — Validation results (2026-11-13)

**Source:** Complete issue #49 archive plus issue #50 version-consistency fixes. The package is a candidate, not a published GitHub release.

## Passed locally

- `scripts/check-versions.py`: canonical POM `2.1.0-beta2`, selective Maven resource templates, CLI/Compose/README source contracts, and CI/release integration.
- `scripts/test-issue50-versions.py`: canonical beta tag accepted; uppercase or mismatched stable tag rejected; changing only the POM project version produces a matching inventory; stale app properties, OpenAPI, Compose, CLI, README, release checks and filtered build outputs fail closed in adversarial fixtures.
- `python3 scripts/check-versions.py --tag v2.1.0`: **failed as expected**, stating: `Tag/POM mismatch: tag='v2.1.0', pom.xml version='2.1.0-beta2'; expected tag v2.1.0-beta2`.
- `bash scripts/release-gate.sh --static-only`: **passed**; Maven and browser checks were correctly reported as skipped.
- `scripts/check-ci-workflow.py` and `scripts/test-ci-gate.py`: unified required gate and doc-only/migration selection passed.
- `scripts/check-issue49-release.py`, `scripts/test-issue49-release.py`, `scripts/test-issue49-policy.py`: release provenance, tag constraints and fail-closed publishing checks passed.
- `scripts/check-issue45-supply-chain.py` and `scripts/test-issue45-security-gate.py`: immutable Actions references, vendor hashes, Trivy blocker and negative security-policy tests passed.
- All twelve issue-specific offline source checks for issues #36 through #46 passed (including both issue #38 suites).
- Go version builder, using the installed Go 1.23 in `GO111MODULE=off` mode for its standalone standard-library implementation: rejected `-version 2.0.0` against canonical POM, and accepted matching version before rejecting a deliberately invalid platform target.
- Shell syntax (`bash -n`) and seven YAML files parsed (four Compose files, CI, release and source OpenAPI).
- Full source ZIP integrity, manifest and publisher preflight: see packaging checks.

## Not run locally

- Maven compile, JUnit/Spring integration suite, Maven resource filtering on a real built JAR, Actuator runtime metadata, and Docker/Trivy integration: Maven and Docker are not installed in this execution environment.
- Full Go CLI tests and cross-platform binaries: installed Go 1.23.2 is older than `cli/go.mod` requirement (`go 1.25`). The GitHub CI runner installs the module-declared Go version.
- Live GitHub Actions `gate`, release creation and protected publication: source has not been pushed or tagged.

**Required before closing issue #50:** Push via the reviewed publishing procedure, obtain green `CI/gate`, verify `python3 scripts/check-versions.py --verify-built` on the runner, and demonstrate a deliberately mismatched release tag is refused (the local test already proves that behavior in isolation).
