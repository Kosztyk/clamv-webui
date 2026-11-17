#!/usr/bin/env bash
# A reproducible release check for the version declared in pom.xml.
# Use: bash scripts/release-gate.sh [--static-only] [--docker-build]
set -euo pipefail
cd "$(dirname "$0")/.."
static_only=0
docker_build=0
for arg in "$@"; do
  case "$arg" in
    --static-only) static_only=1;;
    --docker-build) docker_build=1;;
    *) printf 'Unknown option: %s\n' "$arg" >&2; exit 2;;
  esac
done

if (( static_only )); then export CLAMAV_SKIP_BROWSER=1; fi
printf '\n[0/7] Private-source inventory and canonical version consistency\n'
python3 tools/audit-source-package.py .
python3 scripts/check-issue56-release-docs.py
version_args=()
if [[ -n "${RELEASE_TAG:-}" ]]; then version_args+=(--tag "$RELEASE_TAG"); fi
python3 scripts/check-versions.py "${version_args[@]}"
python3 scripts/test-issue50-versions.py
printf '\n[1/7] Phase 0/1 regression inventory\n'
python3 scripts/check-static-inventory.py
printf '\n[2/7] Flyway and database inventory\n'
python3 scripts/check-db-inventory.py
printf '\n[3/7] Phase 4 metrics/configuration inventory\n'
python3 scripts/check-monitoring-inventory.py
python3 scripts/check-issue49-release.py
python3 scripts/test-issue49-release.py
python3 scripts/test-issue49-policy.py
python3 scripts/check-issue51-container-release.py
python3 scripts/test-issue51-image-release.py
python3 scripts/test-issue51-policy.py
python3 scripts/check-issue52-security-gates.py
python3 scripts/test-issue52-security-gates.py
python3 scripts/check-issue53-java-quality.py
python3 scripts/test-issue53-java-quality.py
python3 scripts/test-full-source-audit-regressions.py
python3 scripts/test-2026-cumulative-regressions.py
python3 scripts/check-issue54-cli-quality.py
python3 scripts/test-issue54-cli-quality.py
python3 scripts/issue55-api-contract.py
python3 scripts/test-issue55-api-contract.py
python3 scripts/check-issue57-upgrade-path.py
python3 scripts/test-issue57-upgrade-path.py
python3 scripts/check-issue58-hardening.py
python3 scripts/test-issue58-hardening.py
node scripts/test-configuration-observer.js
printf '\n[4/7] Six-language catalog and browser smoke (if installed)\n'
python3 scripts/check-i18n.py
printf '\n[5/7] Central identity Test/Review/Apply browser routes (if installed)\n'
if (( static_only )); then
  printf 'SKIPPED: browser integration tests (--static-only)\n'
elif python3 -c 'import importlib.util, sys; sys.exit(0 if importlib.util.find_spec("playwright") else 1)'; then
  python3 scripts/test_authentication_form_routes.py
else
  if [[ "${CLAMAV_REQUIRE_BROWSER:-0}" == '1' ]]; then echo 'ERROR: Browser tests required but Playwright is missing' >&2; exit 1; fi
  printf 'SKIPPED: Central identity Chromium tests (Playwright not installed; CI installs Playwright and Chromium)\n'
fi
printf '\n[6/7] Build/test class and executable script checks\n'
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
import re
pom=Path('pom.xml')
root=ET.parse(pom).getroot()
assert root.find('{http://maven.apache.org/POM/4.0.0}version').text, 'Maven project version must be explicit'
for source in Path('src/test/java').rglob('*.java'):
    s=source.read_text()
    assert 'info.micrometer.core.instrument.simple' not in s, f'incorrect Micrometer import: {source}'
    assert not re.search(r'try\s*\([^)]*SimpleMeterRegistry\s*\(',s), f'non-AutoCloseable try-with-resources: {source}'
assert 'clamav-web-client:' in Path('docker-compose.yml').read_text(), 'Expected clamav-web-client service missing from Compose'
assert 'bash scripts/smoke-eicar.sh' in Path('.github/workflows/ci.yml').read_text()
assert '<filtering>false</filtering>' in pom.read_text()
assert '<include>clamav-web-client.properties.example</include>' in pom.read_text()
assert 'conf/' in Path('.dockerignore').read_text()
print('PASS: Maven version, registry imports/cleanup, CI smoke invocation, safe config copy and Compose service (endpoint may be customized)')
PY
printf '\n[7/7] Compilation and JUnit verification\n'
if (( static_only )); then
  printf 'SKIPPED: Maven test run (static-only mode requested)\n'
else
  command -v mvn >/dev/null 2>&1 || { echo 'ERROR: Maven not installed; cannot certify a local build' >&2; exit 1; }
  mvn -B -ntp clean verify
  python3 scripts/check-versions.py "${version_args[@]}" --verify-built
fi
if (( docker_build )); then
  command -v docker >/dev/null 2>&1 || { echo 'ERROR: Docker not installed' >&2; exit 1; }
  docker compose config --quiet
  version="$(python3 scripts/check-versions.py --print-version)"
  docker compose --progress plain build --no-cache \
    --build-arg "CLAMAV_APP_VERSION=$version" clamav-web-client
fi
printf '\nRELEASE GATE COMPLETE (consult SKIPPED checks above)\n'
