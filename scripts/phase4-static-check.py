#!/usr/bin/env python3
"""Dependency-free Phase 4 source checks; does NOT replace Maven/integration tests."""
from pathlib import Path
import xml.etree.ElementTree as ET, json
root=Path(__file__).resolve().parents[1]
ET.parse(root/'pom.xml')
json.load((root/'dashboards/grafana-clamav-webui.json').open())
shell=(root/'src/main/resources/templates/fragments/shell.html').read_text()
css=(root/'src/main/resources/static/css/phase0.css').read_text()
security=(root/'src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java').read_text()
assert 'fa-tachometer-alt' in shell and 'fa-heartbeat' in shell
assert 'sidebar-nav-group' in shell and '.sidebar-nav-group' in css
assert 'href="@{/admin/configuration}"' in shell
assert '.app-main { contain:none !important' in css
assert 'requestMatchers("/actuator/prometheus").permitAll()' in security
assert (root/'dashboards/clamav-alerts.yml').exists()
assert (root/'src/main/java/info/trizub/clamav/webclient/metrics/PrometheusAccessFilter.java').exists()
# Regression guard: a previous release accidentally imported info.micrometer (typo)
# in a JUnit test and passed other static checks, but failed Maven testCompile.
java_sources = list((root/'src').rglob('*.java'))
invalid_imports = [str(p.relative_to(root)) for p in java_sources
                   if 'import info.micrometer.' in p.read_text(encoding='utf-8')]
assert not invalid_imports, f'Invalid Micrometer import(s): {invalid_imports}'
metrics_test = (root/'src/test/java/info/trizub/clamav/webclient/metrics/ScanMetricsTest.java').read_text()
assert 'import io.micrometer.core.instrument.simple.SimpleMeterRegistry;' in metrics_test
# Regression guards for both build failures seen after Phase 4 rollout.
assert 'try (var registry=' not in metrics_test, 'Micrometer MeterRegistry is not AutoCloseable; use finally/close()'
assert metrics_test.count('registry.close();') >= 2, 'Close each test registry explicitly'
resources = ET.parse(root/'pom.xml')
ns={'m':'http://maven.apache.org/POM/4.0.0'}
configs = resources.findall('.//m:execution[m:id="copy-resources"]/m:configuration/m:resources/m:resource',ns)
assert len(configs)==1, f'Unexpected copy-resources configuration: {len(configs)}'
resource=configs[0]
assert resource.findtext('m:filtering',namespaces=ns)=='false', 'Do not filter binary truststores'
assert resource.findtext('m:directory',namespaces=ns)=='conf', 'Expected bundled example only'
assert [n.text for n in resource.findall('m:includes/m:include',ns)] == ['clamav-web-client.properties.example'], 'Do not package mounted credentials from conf/'
assert 'conf/' in (root/'.dockerignore').read_text().splitlines(), 'Exclude mounted conf/ from image build context'
print('PASS Phase 4 static source/JSON/XML/layout & build-regression checks (not a Java build)')
