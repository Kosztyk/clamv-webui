#!/usr/bin/env bash
# Compatibility entrypoint: release checks have one maintained implementation.
set -euo pipefail
exec bash "$(dirname "$0")/release-gate.sh" "$@"
