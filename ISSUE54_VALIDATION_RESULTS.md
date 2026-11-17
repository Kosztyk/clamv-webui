# Issue #54 validation report — 2026-11-13

Baseline: full-source issue #53 candidate, version `2.1.0-beta2`.

## Executed in offline workspace

- `python3 scripts/check-issue54-cli-quality.py` — PASS: CI/release workflow selection and gates, staticcheck/govulncheck, two-build reproducibility, SHA-256, keyless signing and verification.
- `python3 scripts/test-issue54-cli-quality.py` — PASS: six-platform checksum inventory, tampering and missing-bundle rejection, unsigned-release rejection, and mutations of CI/release policies.
- Existing issue #48/#49/#50/#51/#52/#53 policy and regression checks — PASS after updating old job-layout expectations.
- `python3 scripts/check-versions.py`, `gofmt -l cli/tools/build.go`, `bash -n scripts/issue54-verify-cli-signature.sh` and Python syntax validation — PASS.

## Not executed

The build workspace has Go **1.23.2** rather than the project's required Go **1.25**, and no installed Maven or Docker. Go staticcheck/govulncheck, actual six-target double compilation, executable `clamctl --version`, Spring integration, and real Cosign OIDC keyless signature creation/verification **were not run locally**. All acceptance criteria remain conditional on live GitHub Actions runs and actual release assets. This report is not evidence of a published signed release.

## Live verification commands

```bash
python3 scripts/check-issue54-cli-quality.py
python3 scripts/test-issue54-cli-quality.py
python3 scripts/issue54-cli-repro.py --out cli/dist
python3 scripts/issue54-cli-repro.py --verify-only --out cli/dist
mvn -B -ntp -DskipTests package
python3 scripts/test-clamctl-integration.py
```

Release acceptance: the CI `gate` must pass and tag-triggered release must sign `clamctl-SHA256SUMS` using Cosign, independently verify its exact GitHub tag-workflow identity, produce the bundle in the draft's assets, and successfully reverify it after protected approval.
