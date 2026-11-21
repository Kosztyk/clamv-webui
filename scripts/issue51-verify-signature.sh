#!/usr/bin/env bash
# Independent Sigstore identity + issuer + keyless CycloneDX attestation check.
set -euo pipefail
[[ $# == 2 ]] || { echo 'Usage: issue51-verify-signature.sh image@sha256:digest vTAG' >&2; exit 2; }
ref="$1"
tag="$2"
[[ "$ref" =~ ^ghcr\.io/[a-z0-9._-]+/clamav-web-client@sha256:[a-f0-9]{64}$ ]] || { echo 'Expected immutable GHCR image digest, not a tag' >&2; exit 1; }
[[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid release tag' >&2; exit 1; }
: "${GITHUB_REPOSITORY:?Expected owner/repository}"
identity="https://github.com/${GITHUB_REPOSITORY}/.github/workflows/release.yml@refs/tags/${tag}"
issuer='https://token.actions.githubusercontent.com'
command -v cosign >/dev/null || { echo 'cosign must be installed' >&2; exit 1; }
cosign verify --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$ref" >/dev/null
cosign verify-attestation --type cyclonedx --certificate-identity "$identity" --certificate-oidc-issuer "$issuer" "$ref" > /dev/null
echo "PASS: Sigstore signed image and CycloneDX attestation verified for ${tag}"
