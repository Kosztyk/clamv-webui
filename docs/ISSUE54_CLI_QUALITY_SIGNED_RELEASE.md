# Issue #54 — Go CLI security, reproducibility and signed checksums

**Status:** implemented in the full-source candidate; real CI and signing need execution on GitHub. Built on issue #53, retaining issues #36–#53.

## Unified CI gate

On CLI changes (`cli/`, relevant scripts, the canonical `pom.xml`, or either CI/release workflow), the existing `gate` requires:

| Job | Checks | Output |
|---|---|---|
| `cli-test` | Go 1.25 from `cli/go.mod`; Linux/macOS/Windows unit tests and `go vet`; Linux race detector, `staticcheck` 2026.1, `govulncheck` v1.1.4 | Test failure status, blocks `gate` |
| `cli-build` | Twice independently compile every supported Linux/macOS/Windows amd64/arm64 executable using the exact POM version | `cli/dist/`, JSON manifest, six-entry SHA256SUMS |
| `cli-integration` | Download CI binary artifact, verify all SHA-256 hashes and Linux `--json --version`; package the actual Spring JAR and run `TestSpringApplication` against an isolated fake ClamD | Validates real CLI/API compatibility and scanner behavior |

These jobs depend on one another; a skipped or failed CLI job is not a passing `gate`. Documentation-only PRs still run lightweight checks without cross-compiling. Workflow/Go dependency changes trigger CLI validation; old `go-cli` is replaced. Third-party Actions remain pinned by commit SHA and Go dependency caching uses `cli/go.sum`.

## Deterministic Go release builds

`python3 scripts/issue54-cli-repro.py --out cli/dist` derives the version exclusively from the root `pom.xml` and `SOURCE_DATE_EPOCH` from the exact source commit timestamp (`git show -s --format=%ct HEAD`). It runs `go run ./tools/build.go` **twice** in fresh directories, with the same toolchain and environment: `-trimpath`, disabled VCS stamping, `-ldflags=-s -w -buildid= -X main.version=VERSION`, `CGO_ENABLED=0`, `GOTOOLCHAIN=local`, empty `GOFLAGS` and the fixed `SOURCE_DATE_EPOCH`. All six binary hashes, their CLI manifests and the generated SHA256SUMS must match byte-for-byte. A mismatch stops the job without publishing artifacts. The native Linux/amd64 executable is executed with `--json --version` and must report exactly the canonical version.

This is a reproducibility check **on one pinned CI environment and toolchain**, not a guarantee of byte-identical output across unrelated compiler versions or operating systems.

## Signed release assets

The release workflow separates `release-cli-test`, `release-clamctl-build`, `release-cli-integration`, and `release-cli-sign-checksums` jobs. Every job is gated on the exact tag's verified `main` commit. The signing job has `contents: read` and `id-token: write` only; it downloads the tested binaries, independently hashes them into a deterministic six-line **`clamctl-SHA256SUMS`** manifest (flat release-asset filenames), and invokes:

```bash
cosign sign-blob --yes --new-bundle-format \
  --bundle artifacts/cli-sign/clamctl-SHA256SUMS.sigstore.json \
  artifacts/cli-sign/clamctl-SHA256SUMS
```

Cosign uses GitHub OIDC keyless signing; no repository signing key or private-key secret is required. The signing identity is restricted to the **exact tag-triggered** `.github/workflows/release.yml` path and the GitHub OIDC issuer. The draft and approved publication jobs reverify **the signature, signer identity and SHA-256 data**, not just the existence of the bundle. The image-signing and digest-smoke workflow from issue #51 remains required.

Each GitHub release includes all six CLI executables, `clamctl-SHA256SUMS`, `clamctl-SHA256SUMS.sigstore.json`, and the pre-existing release-wide `SHA256SUMS`. All files are cross-checked by `scripts/release-assets.py`; the release-wide checksum list includes the signed CLI inventory and its bundle, avoiding a circular self-hash.

### Verification by users

Download the six CLI binaries and both `clamctl-SHA256SUMS*` assets from the same release. On macOS/Linux, install Cosign and run from that directory (the helper script is included in the source archive):

```bash
export GITHUB_REPOSITORY=Kosztyk/clamv-webui
bash scripts/issue54-verify-cli-signature.sh \
  clamctl-SHA256SUMS clamctl-SHA256SUMS.sigstore.json v2.1.0
sha256sum -c clamctl-SHA256SUMS
```

On macOS use `shasum -a 256 -c clamctl-SHA256SUMS`. Check a specific file with `sha256sum` or `shasum -a 256` if not all six are downloaded. The verification fails for missing bundles, altered checksums, a wrong signing issuer, or a different GitHub repository/tag. For production deployments also verify the separate GHCR image signature as documented in issue #51.

## Tests and limitations

`python3 scripts/check-issue54-cli-quality.py` validates workflow wiring, dependencies, tool versions, signing stages and the required CI gate. `python3 scripts/test-issue54-cli-quality.py` runs positive/negative tests for detached checksum manifests, tampered binaries, wrong/missing signatures, CI-policy bypass attempts and unsigned release assets. `scripts/release-gate.sh` invokes both checks, but its `--static-only` option does **not** certify the real Go build or Sigstore signing.

**Acceptance requires a live GitHub CI run:** Go staticcheck and govulncheck succeed, both deterministic builds match for all six binaries, and the real Spring integration passes. A tag-triggered release must produce the detached Sigstore bundle and independent verification in both draft and protected-approval jobs. A deliberate synthetic failing CLI change should turn `gate` red; a tampered detached checksum must fail verification. No tag or GitHub release is created by adding these source files alone.
