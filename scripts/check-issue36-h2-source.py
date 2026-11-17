#!/usr/bin/env python3
"""Offline issue #36 source contract. Usage: python3 scripts/check-issue36-h2-source.py"""
from pathlib import Path
import json
import sys

ROOT = Path(__file__).resolve().parents[1]
SAFE = 'jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0'
LEGACY = 'jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE'

checks = {
    'application.properties': ('spring.datasource.url=' + SAFE) in (ROOT/'src/main/resources/application.properties').read_text(),
    'Configuration Center catalog': next(field['defaultValue'] for field in json.loads(
        (ROOT/'src/main/resources/configuration-center/catalog.json').read_text())['fields']
        if field['key'] == 'spring.datasource.url') == SAFE,
    'Configuration Center browser preset': ("h2: '" + SAFE + "'") in (
        ROOT/'src/main/resources/static/js/configuration-center.js').read_text(),
    'legacy Configuration Center upgrade': LEGACY in (
        ROOT/'src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationStore.java').read_text(),
    'JDBC unsafe file option prevention': 'file-backed H2 must close' in (
        ROOT/'src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java').read_text(),
    'Compose graceful shutdown (all standalone variants)': all(
        'stop_grace_period: 60s' in (ROOT/name).read_text()
        for name in ('docker-compose.yml', 'docker-compose.minimal.yaml',
                     'docker-compose.configuration-center.yaml', 'docker-compose.environment.yaml')),
    'JUnit regression tests': (ROOT/'src/test/java/info/trizub/clamav/webclient/configuration/Issue36H2ShutdownTest.java').is_file(),
    'Disposable smoke exercises packaged default': (
        (ROOT/'scripts/verify-issue36-h2-shutdown.sh').is_file()
        and "-e 'SPRING_DATASOURCE_URL=" not in (
            ROOT/'scripts/verify-issue36-h2-shutdown.sh').read_text()
        and 'check_graceful_exit' in (
            ROOT/'scripts/verify-issue36-h2-shutdown.sh').read_text()),
    'Hikari pool closure regression': 'hikariPoolCloseReleasesTheFileLockAndRestartKeepsExistingRows' in (
        ROOT/'src/test/java/info/trizub/clamav/webclient/configuration/Issue36H2ShutdownTest.java').read_text(),
    'Live legacy -1 reset regression': all(fragment in (ROOT/'src/test/java/info/trizub/clamav/webclient/configuration/Issue36H2ShutdownTest.java').read_text() for fragment in (
        'safeFileUrlResetsAnAlreadyOpenLegacyDatabaseWithoutForcedShutdown',
        'oldPool = hikari(base + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")',
        'correctedPool = hikari(base + ";DB_CLOSE_DELAY=0")',
        'Legacy -1 setting deliberately keeps MVStore open',
        'New URL must reset -1 and close the database on pool shutdown')),
}
for name, valid in checks.items():
    print(f"{'PASS' if valid else 'FAIL'}: {name}")
if not all(checks.values()):
    sys.exit(1)
print(f'PASS: issue #36 source checks ({len(checks)} checks)')
