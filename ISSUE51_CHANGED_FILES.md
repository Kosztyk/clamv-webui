# Issue #51 — changed files

Baseline: `clamav-webui-v2.1.0beta2-issue50-version-consistency-full-source.zip`.

- `.github/workflows/release.yml` — add gated multi-architecture GHCR Buildx publication; pushed digest scan on both architectures, BuildKit SLSA provenance and SPDX SBOM, CycloneDX SBOMs, cosign keyless sign/verify, digest-pinned non-root/EICAR smoke, release alias promotion and checksum evidence; draft and approval reverify signatures. Keep existing tag/main/gate, Maven, database, CLI, browser, local Docker, Trivy and approval workflow.
- `.github/workflows/ci.yml` — include issue #51 security contract and adversarial tests in stable required `gate`.
- `docker-compose.yml` — optional `CLAMAV_WEBUI_IMAGE_REF` immutable digest selection, default unchanged local build tag.
- `docker-compose.ghcr.yml` — published-image deployment example, no scanner installed inside Web UI.
- `scripts/issue51-image-release.py` — tag/owner/version derivation, multi-arch OCI attestation and CycloneDX validation, signed-image reference evidence.
- `scripts/issue51-verify-signature.sh` — cosign certificate identity and issuer verification.
- `scripts/release-assets.py` — package and verify four additional image evidence assets under SHA256SUMS; require image evidence on approved release.
- `scripts/check-issue51-container-release.py`, `scripts/test-issue51-image-release.py`, `scripts/test-issue51-policy.py` — new positive, negative, regression and tampering checks.
- `scripts/release-gate.sh` — include the new source/policy tests.
- `scripts/check-versions.py`, `scripts/test-issue50-versions.py`, `scripts/check-static-inventory.py` — recognize the optional digest image reference while preserving the local-version-neutral default.
- `README.md`, `docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md`, `docs/ISSUE51_SIGNED_GHCR_RELEASE.md`, `docs/releases/v2.1.0beta2.md` — operator, migration and verification documentation.
- `ISSUE51_VALIDATION_RESULTS.md`, `SOURCE_MANIFEST.sha256` — validation status and integrity manifest.

No application Java classes, database migrations, or Configuration Center settings were changed.
