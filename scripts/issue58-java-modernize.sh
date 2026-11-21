#!/usr/bin/env bash
# Run on a clean Git checkout with JDK 21 + Maven 3.9+, review resulting formatting-only patch.
set -euo pipefail
cd "$(dirname "$0")/.."
command -v mvn >/dev/null || { echo 'Maven 3.9+ is required' >&2; exit 1; }
command -v git >/dev/null || { echo 'A Git checkout is required' >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo 'Refusing to modify a dirty source checkout' >&2; exit 1; }
mvn -B -ntp -Pci-quality -Dspotless.ratchet.ref= -DskipTests spotless:apply
mkdir -p artifacts/java-modernization
git diff --binary -- src/main/java src/test/java > artifacts/java-modernization/full-java-format.patch
echo 'Review and commit the formatting-only patch in a separate pull request.'
