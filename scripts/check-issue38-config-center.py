#!/usr/bin/env python3
"""Offline contract check for issue #38 Configuration Center setting.

Complements JUnit tests: does not claim Maven/Spring runtime validation.
"""
import json
import pathlib
import re

root = pathlib.Path(__file__).resolve().parents[1]
checks = []

def check(label, assertion):
    assert assertion, label
    checks.append(label)

catalog = json.loads((root / 'src/main/resources/configuration-center/catalog.json').read_text())
key = 'clamav.clamdtop.idle-timeout-seconds'
field = [f for f in catalog['fields'] if f['key'] == key]
check('Exactly one idle timeout catalog entry', len(field) == 1)
f = field[0]
check('Correct category and independent section', f['category'] == 'Monitoring' and f['section'] == 'clamdtop')
check('Decimal timeout with validated 1..86400 bound', f['type'] == 'number' and f['minimum'] == 1 and f['maximum'] == 86400)
check('180 second default', f['defaultValue'] == '180')
check('Exact Docker variable', f['environment'] == ['CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS'])
check('Restart semantics', f['apply'] == 'restart')
check('Not a private or advanced field', not (f['secret'] or f['sensitive'] or f['advanced']))
section = [s for s in catalog['sections'] if s['id'] == 'clamdtop']
check('Native monitor section has no Prometheus enable gate', len(section) == 1 and section[0]['enableKey'] == '')
check('Section belongs to Monitoring', section[0]['category'] == 'Monitoring')
app = (root / 'src/main/resources/application.properties').read_text()
check('Existing startup value resolves named environment override',
      f'{key}=${{CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS:180}}' in app)
websocket = (root / 'src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java').read_text()
check('Native handler still consumes the property', f'@Value("${{{key}:180}}")' in websocket)
check('WebSocket retains its runtime bounds and idle reaper',
      'idleTimeoutSeconds < 1 || idleTimeoutSeconds > 86_400' in websocket and 'idleTimeoutNanos' in websocket)
ui = (root / 'src/main/resources/static/js/configuration-center.js').read_text()
check('UI displays configured idle value in section summary', "case 'clamdtop': return" in ui and "this.value('clamav.clamdtop.idle-timeout-seconds')" in ui)
check('UI field is rendered via section-driven catalog', 'this.sectionFields(section)' in ui)
check('Documentation identifies GUI location',
      'Monitoring → Native ClamdTop sessions' in (root / 'README.md').read_text())
check('Regression test includes save, restart, override and Spring binding',
      all(text in (root / 'src/test/java/info/trizub/clamav/webclient/configuration/Issue38ClamdtopIdleConfigurationCenterTest.java').read_text()
          for text in ('savesFromGuiRetainsOldActiveValueUntilRestart', 'composeOverridesSavedGuiSettingAndLocksItWithoutErasingIt', 'actualSpringConstructorReceivesSavedSettingAtStartup')))
check('Catalog has no duplicate field keys', len({f['key'] for f in catalog['fields']}) == len(catalog['fields']))
print('PASS: %d/%d issue #38 Configuration Center offline checks' % (len(checks), len(checks)))
for c in checks: print(' -',c)
