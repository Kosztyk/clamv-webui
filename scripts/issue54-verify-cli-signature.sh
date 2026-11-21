#!/usr/bin/env bash
# Verify detached CLI checksums using the *exact* expected GitHub tag OIDC identity.
set -euo pipefail
[[ $# == 3 ]] || { echo 'Usage: issue54-verify-cli-signature.sh CHECKSUMS SIGSTORE_BUNDLE vTAG' >&2; exit 2; }
manifest="$1"
bundle="$2"
tag="$3"
[[ "$tag" =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[A-Za-z0-9.-]+)?$ ]] || { echo 'Invalid signed CLI release tag' >&2; exit 1; }
: "${GITHUB_REPOSITORY:?Expected owner/repository from GitHub Actions}"
[[ -s "$manifest" && -s "$bundle" ]] || { echo 'Missing CLI checksum or Sigstore bundle' >&2; exit 1; }
command -v cosign >/dev/null || { echo 'cosign not installed' >&2; exit 1; }
identity="https://github.com/${GITHUB_REPOSITORY}/.github/workflows/release.yml@refs/tags/${tag}"
cosign verify-blob --new-bundle-format --bundle "$bundle" \
  --certificate-identity "$identity" \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  "$manifest" >/dev/null
echo "PASS: keyless-signed clamctl manifest verified for $tag"
