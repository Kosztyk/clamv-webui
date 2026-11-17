# Issue #51 validation report

**Baseline:** issue #50 complete-source ZIP, including every earlier fix.
**Target:** v2.1.0-beta2, GHCR multi-architecture signed-image release pipeline.
**GitHub issue:** https://github.com/Kosztyk/clamv-webui/issues/51

## Executed successfully in this environment

- `scripts/check-issue51-container-release.py`: checked the real tag-triggered YAML for release ordering, least privilege, BuildKit SBOM/provenance, both platforms, per-arch severity gate, signed digest, ref-pinned smoke, source verification and approved draft dependency.
- `scripts/test-issue51-policy.py`: unmodified workflow + **11 adversarial policy mutations** (single architecture, disabled SBOM/provenance, advisory CVE gate, local image swap, removed EICAR/non-root test, removed signature/attestation, missing artifact and dry-run publication).
- `scripts/test-issue51-image-release.py`: **six test methods** covering canonical versions/stable-vs-beta aliases, GHCR digest/commit validation, multi-arch/attestation integrity, malformed CycloneDX, environment-driven staging derivation, and 12-asset SHA256SUMS verification/tampering.
- Existing issue #49 release validator and its eight JUnit-like Python tests, issue #49 adversarial policy tests, issue #50 version consistency including POM-only version bump, issue #48 workflow/gate tests, issue #45 supply-chain contract.
- Previous issue #36–#46 offline source checks remained green.
- `bash scripts/release-gate.sh --static-only` passed, including six-language inventory, JavaScript observer tests and the new issue #51 tests.
- PyYAML parsed both GitHub Actions workflows, Dependabot, and both Compose files. Bash syntax was checked.
- Source-package audit found no prohibited runtime settings or credentials.
- SHA-256 source file manifest, ZIP integrity, and publisher `--check` validated during final packaging.

## Deliberately not claimed as tested

**Maven and Docker are not available here**: no full Maven/Spring JUnit verification, multi-arch Buildx execution, actual GHCR push/pull, Trivy vulnerability database scan, Sigstore OIDC signing, BuildKit provenance examination on a live registry, arm64 image build, pulled-digest non-root/EICAR smoke or real GitHub release was executed. The new scripts simulate valid/invalid metadata, not real registry responses.

## Release acceptance still required

1. Push the updated source to `main` through a PR and confirm the required `gate` passes.
2. Confirm the GitHub repository's `GITHUB_TOKEN` may write to GHCR and package visibility allows intended users to pull.
3. Tag the exact `main` commit using the canonical POM version (`v2.1.0-beta2` for this archive, or `v2.1.0` after a POM-only stable bump).
4. Confirm `image-publish` builds both OCI platforms, blocks unfixed HIGH/CRITICAL OS and library CVEs, signs the immutable index, and verifies two CycloneDX attestations.
5. Confirm both smoke scripts run with the **GHCR digest** as the container image (`docker inspect ... .Config.Image`), not a local build.
6. Confirm GHCR aliases resolve to the exact published image digest; `docker pull ghcr.io/kosztyk/clamav-web-client:<version>` succeeds (public package or authenticated as required), `cosign verify` succeeds, and draft release SHA256SUMS verifies all 12 assets.

**Status:** implementation packaged and offline checks passed; runtime acceptance remains **pending**. No repository push, tag creation, release publication or issue closure performed.
