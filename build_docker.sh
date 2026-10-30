#!/usr/bin/env bash
set -euo pipefail
# Build and test the current ClamAV Web UI source before tagging the image.
mvn -B -ntp clean verify
docker compose config --quiet
docker compose build
