# Issue #54 changed files

Baseline: `clamav-webui-v2.1.0beta2-issue53-java-quality-gates-full-source.zip`. All previous fixes retained.

- `.github/workflows/ci.yml`: Split Go validation into `cli-test`, `cli-build`, `cli-integration`; keep `gate` as sole required check, add source policy validation.
- `.github/workflows/release.yml`: Dedicated CLI quality, deterministic build, integration, keyless signing, and pre-draft/approval signature verification.
- `cli/tools/build.go`: Explicit deterministic build epoch, Go flags, version stamping and locked cross-compilation environment.
- `scripts/issue54-cli-repro.py`: Two independent six-platform Go builds, bytewise checksums and embedded version smoke.
- `scripts/issue54-cli-signing.py`: Deterministic signed CLI checksum manifest creation and structural verification.
- `scripts/issue54-verify-cli-signature.sh`: Exact release tag / GitHub Actions signer identity / OIDC issuer verification using Cosign.
- `scripts/release-assets.py`: Include signed checksum and bundle as verified release assets; enforce signed CLI release approval.
- `scripts/test-clamctl-integration.py`: Use canonical POM version instead of stale hard-coded JAR filename.
- `scripts/ci-evaluate-gate.py`, `scripts/ci-detect-changes.py`, `scripts/check-ci-workflow.py`: New CI job results and CLI change selection.
- `scripts/check-issue49-release.py`, `scripts/check-issue51-container-release.py`: Update contract checks to the signed, separated release path.
- `scripts/release-gate.sh`: Check issue #54 contracts even in static-only mode.
- `scripts/check-issue54-cli-quality.py`, `scripts/test-issue54-cli-quality.py`: New source and adversarial policy tests.
- `README.md`, `cli/README.md`, `docs/ISSUE54_CLI_QUALITY_SIGNED_RELEASE.md`, `ISSUE54_VALIDATION_RESULTS.md`: Documentation and acceptance plan.
- `SOURCE_MANIFEST.sha256`: Regenerated for new complete source.

No Java business logic, database schema, Configuration Center fields, Docker Compose defaults, or application credentials are modified by this maintenance fix.
