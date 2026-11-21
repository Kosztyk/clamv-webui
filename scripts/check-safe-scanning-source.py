#!/usr/bin/env python3
"""Source-only integrity check; does not replace Maven or live ClamAV tests."""
import json
import re
from pathlib import Path
root = Path(__file__).resolve().parent.parent
java = (root / 'src/main/java/info/trizub/clamav/webclient/service/SettingsService.java').read_text()
for key, default in (
    ('UPLOAD_PATH_SCAN_ENABLED', 'false'),
    ('UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM', 'true'),
):
    assert re.search(
        rf'applyEnvironmentOverride\(\s*{key}\s*,\s*ENV_{key}\s*,\s*"{default}"\s*\)',
        java,
    ), f'Unexpected environment fallback for {key}'
    assert re.search(rf'resolved\(\s*{key}\s*,\s*"{default}"\s*\)', java), key
catalog = json.loads((root / 'src/main/resources/configuration-center/catalog.json').read_text())
fields = {item['key']:item for item in catalog['fields']}
assert fields['app.upload.pathScan.enabled']['defaultValue']=='false'
assert fields['app.upload.pathScan.fallbackToInstream']['defaultValue']=='true'
security = (root/'src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java').read_text()
assert re.search(
    r'\.requestMatchers\(\s*HttpMethod\.GET\s*,\s*"/login"\s*\)\s*\.permitAll\(\)',
    security,
)
private = (root/'src/main/java/info/trizub/clamav/webclient/util/PrivateFiles.java').read_text()
assert 'rwx------' in private and 'rw-------' in private
compose = (root/'docker-compose.yml').read_text()
assert 'CLAMAV_BASE_IMAGE' in compose
scanner = compose.split('  clamav-server:',1)[1]
assert not re.search(r'^    (?:ports|volumes):',scanner,flags=re.M)
print('PASS: source defaults, Configuration Center defaults, login patch, private file permissions, and scanner isolation')
