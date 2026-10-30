#!/usr/bin/env bash
# A reproducible release check for ClamAV Web UI v2.0.0.
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

printf '\n[1/7] Phase 0/1 regression inventory\n'
python3 scripts/check-phase1-static.py
printf '\n[2/7] Flyway and database inventory\n'
python3 scripts/check-phase2-static.py
printf '\n[3/7] Phase 4 metrics/configuration inventory\n'
python3 scripts/phase4-static-check.py
node scripts/test-configuration-observer.js
printf '\n[4/7] Six-language catalog and browser smoke (if installed)\n'
python3 scripts/check-i18n.py
printf '\n[5/7] LDAP/OIDC Save/Test browser routes (if installed)\n'
python3 scripts/test_authentication_form_routes.py
printf '\n[6/7] Build/test class and executable script checks\n'
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
import re
pom=Path('pom.xml')
root=ET.parse(pom).getroot()
assert root.find('{http://maven.apache.org/POM/4.0.0}version').text=='2.0.0'
for source in Path('src/test/java').rglob('*.java'):
    s=source.read_text()
    assert 'info.micrometer.core.instrument.simple' not in s, f'incorrect Micrometer import: {source}'
    assert not re.search(r'try\s*\([^)]*SimpleMeterRegistry\s*\(',s), f'non-AutoCloseable try-with-resources: {source}'
assert 'clamav-web-client:' in Path('docker-compose.yml').read_text(), 'Expected clamav-web-client service missing from Compose'
assert 'bash scripts/smoke-eicar.sh' in Path('.github/workflows/phase0-ci.yml').read_text()
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
fi
if (( docker_build )); then
  command -v docker >/dev/null 2>&1 || { echo 'ERROR: Docker not installed' >&2; exit 1; }
  docker compose config --quiet
  docker compose --progress plain build --no-cache clamav-web-client
fi
printf '\nRELEASE GATE COMPLETE (consult SKIPPED checks above)\n'
