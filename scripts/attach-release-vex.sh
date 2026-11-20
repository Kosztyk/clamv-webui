#!/usr/bin/env bash
# Attach the release-scoped CVE-2026-107891 OpenVEX statement to an existing
# GHCR or Docker Hub image using Docker Scout. The caller must already be
# authenticated to the target registry and Docker Scout.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
  cat >&2 <<'EOF'
Usage: bash scripts/attach-release-vex.sh IMAGE:TAG [VERSION]
Example:
  bash scripts/attach-release-vex.sh docker.io/kosztyk/clamav-web-client:v2.1.0 2.1.0

Docker Scout's experimental attestation command is invoked with the existing
tagged registry reference. The generated OpenVEX document itself remains bound
to the exact immutable image digest resolved from that tag.
EOF
  exit 2
}

[[ $# -ge 1 && $# -le 2 ]] || usage
input_ref="$1"
version="${2:-$(python3 scripts/check-versions.py --print-version)}"
command -v docker >/dev/null 2>&1 || { echo 'ERROR: docker CLI is required' >&2; exit 1; }
docker scout version >/dev/null 2>&1 || { echo 'ERROR: Docker Scout CLI plugin is required' >&2; exit 1; }

case "$input_ref" in
  docker.io/kosztyk/clamav-web-client:*|ghcr.io/kosztyk/clamav-web-client:*) ;;
  *@sha256:*)
    echo "ERROR: pass an existing image tag, not a digest, to Docker Scout attestation add" >&2
    exit 1
    ;;
  *) echo "ERROR: refusing to attach VEX to an unexpected image: $input_ref" >&2; exit 1;;
esac

# Resolve the registry object to an immutable index digest before generating VEX.
raw="$(docker buildx imagetools inspect "$input_ref")"
digest="$(awk '/^Digest:/ {print $2; exit}' <<<"$raw")"
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] || { echo 'ERROR: could not resolve immutable image digest' >&2; exit 1; }
repo="${input_ref%%@*}"
repo="${repo%:*}"
# Preserve registry ports if ever allowed in the future; current allow-list has none.
case "$repo" in
  docker.io/kosztyk/clamav-web-client|ghcr.io/kosztyk/clamav-web-client) ;;
  *) echo "ERROR: resolved unexpected repository: $repo" >&2; exit 1;;
esac

mkdir -p artifacts/vex
vex_file="artifacts/vex/CVE-2026-107891.vex.json"
python3 scripts/vex_release.py generate --image "$repo" --digest "$digest" --version "$version" --output "$vex_file"
python3 scripts/vex_release.py validate --file "$vex_file" --image "$repo" --digest "$digest" --version "$version"

immutable="$repo@$digest"
author="https://github.com/Kosztyk/clamv-webui"
organization="kosztyk"

# Docker Scout consumes VEX from its normal attestation backend. For Docker Hub,
# use the short repository reference together with the owning Scout organization;
# this avoids the older full docker.io reference resolving through :latest while
# keeping the attestation discoverable by `docker scout cves` and `attestation list`.
attach_ref="$input_ref"
case "$attach_ref" in
  docker.io/*) attach_ref="${attach_ref#docker.io/}" ;;
esac
docker scout attestation add \
  --org "$organization" \
  --file "$vex_file" \
  --predicate-type https://openvex.dev/ns/v0.2.0 \
  "$attach_ref"

# Scout accepts only Docker-authored VEX by default. Verify this publisher-authored
# statement with the exact author explicitly trusted, and filter not_affected CVEs.
docker scout cves \
  --org "$organization" \
  --vex-author "$author" \
  --only-vex-affected \
  --only-cve-id CVE-2026-107891 \
  --exit-code \
  "registry://$input_ref"

echo "Attached and verified OpenVEX via $input_ref (immutable subject: $immutable)"
echo "Publisher-trusted verification: docker scout cves --org $organization --vex-author '$author' --only-vex-affected registry://$input_ref"