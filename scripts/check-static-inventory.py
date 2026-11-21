#!/usr/bin/env python3
"""Offline structural/contract regression checks; does not replace Maven/JUnit."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / 'src/main/java/info/trizub/clamav/webclient'

def read(path): return (ROOT / path).read_text()

assert (ROOT / 'src/main/resources/templates/pages/clamdtop.html').is_file()
assert (ROOT / 'src/main/resources/templates/pages/api-scan.html').is_file()
assert (ROOT / 'src/main/resources/templates/pages/api-reports.html').is_file()
assert (ROOT / 'src/main/resources/templates/pages/api-tokens.html').is_file()
assert (ROOT / 'src/main/resources/static/js/native-clamdtop.js').is_file()
assert (ROOT / 'src/main/resources/static/js/rest-api.js').is_file()
assert 'app.webhook.url' in read('src/main/java/info/trizub/clamav/webclient/service/SettingsService.java')
assert 'API Tokens' in read('src/main/resources/templates/fragments/shell.html')
# Guard the established header and prevent the right-side terminal black strip
header = read('src/main/resources/templates/fragments/shell.html')
assert 'topbar-language' in header and 'topbar-username' in header
assert 'Dark Theme' in header and 'id="themeToggle"' in header
assert 'topbar-logout' in header
native_js = read('src/main/resources/static/js/native-clamdtop.js')
native_css = read('src/main/resources/static/css/phase0.css')
assert 'terminal.renderer?.dimensions?.actualCellWidth' in native_js
assert "background: '#2e3436'" in native_js
assert '#native-clamdtop-terminal {background:#2e3436;border:0;border-radius:0;' in native_css
assert '#native-clamdtop-terminal .xterm-viewport {overflow-y:hidden!important}' in native_css
assert 'rest-api.js' in read('src/main/resources/templates/fragments/shell.html')
assert 'MULTISCAN' in read('src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java')
assert 'clamdtop' in read('Dockerfile')
assert 'CLAMAV_REST_URL' in read('src/main/resources/configuration-center/catalog.json')
assert not re.search(r'192\.168\.68\.', read('docker-compose.yml'))
# The secure beta2 Compose intentionally supplies CLAMAV_HOST/CLAMAV_PORT.
# Reject published daemon ports or full data mounts instead of the legacy
# blanket prohibition on an application environment section.
compose = read('docker-compose.yml')
assert 'CLAMAV_HOST:' in compose and 'CLAMAV_PORT:' in compose
scanner = compose.split('  clamav-server:', 1)[1]
assert not re.search(r'^    (?:ports|volumes):', scanner, re.M)
assert '    build:' in compose and '${CLAMAV_WEBUI_IMAGE_REF:-clamav-web-client:${CLAMAV_WEBUI_IMAGE_TAG:-local}}' in compose
assert 'ApiV1Dtos.Job' in read('src/main/java/info/trizub/clamav/webclient/api/ApiController.java')
assert 'tokens.authenticate' in read('src/main/java/info/trizub/clamav/webclient/security/ApiTokenAuthenticationFilter.java')
assert 'MessageDigest.isEqual' in read('src/main/java/info/trizub/clamav/webclient/service/ApiCredentialService.java')
assert 'getSubmittedTransport()' in read('src/main/java/info/trizub/clamav/webclient/service/AuditService.java')
# Completion auditing moved to proxied finalization service. Validate the current boundary.
finalizer = read('src/main/java/info/trizub/clamav/webclient/service/ScanJobFinalizationService.java')
executor = read('src/main/java/info/trizub/clamav/webclient/service/ScanExecutionService.java')
assert 'audit.recordResult(job)' in finalizer
assert 'void afterCommit()' in finalizer and 'TransactionSynchronizationManager.registerSynchronization' in finalizer
assert 'finalizer.finishFound(' in executor and 'finalizer.finishError(' in executor
assert 'hasRole("ADMIN")' in read('src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java')
ET.parse(ROOT / 'pom.xml')
try:
    import yaml
except ImportError:
    print('PyYAML missing, skipping OpenAPI parse (contract file exists)')
else:
    spec = yaml.safe_load(read('src/main/resources/openapi/clamav-v1.yaml'))
    assert spec['openapi'] == '3.0.3'
    expected = {'/health/live', '/health/ready', '/health/clamd', '/endpoints', '/jobs',
                '/jobs/{id}', '/scans/uploads', '/scans/paths', '/audit', '/tokens', '/metrics/clamd'}
    assert expected <= set(spec['paths'])
for source in JAVA.rglob('*.java'):
    data = source.read_text()
    assert not re.search(r'discord\.com/api/webhooks/[0-9]{12,}', data), str(source)
print('PASS: Phase 0 assets retained; v1 security, OpenAPI, audit and source structure checked')
