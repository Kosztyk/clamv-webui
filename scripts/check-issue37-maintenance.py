#!/usr/bin/env python3
"""Offline contract checks for #37; runtime verification requires Docker/Maven."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
D=(r/'Dockerfile').read_text(); entry=(r/'docker-entrypoint.sh').read_text()
security=re.sub(r'\s+', '', (r/'src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java').read_text())
properties=(r/'src/main/resources/application.properties').read_text()
pom=ET.parse(r/'pom.xml')
ns={'p':'http://maven.apache.org/POM/4.0.0'}
coords=[(e.findtext('p:groupId',namespaces=ns),e.findtext('p:artifactId',namespaces=ns)) for e in pom.findall('./p:dependencies/p:dependency',ns)]
_, _, extraction, runtime = re.split(r'^FROM [^\n]+\n', D, flags=re.M)
runtime_commands = re.sub(r'\\\n\s*', ' ', runtime)
checks={
 'builder/runtime images are pinned by immutable full digests': len(re.findall(r'^FROM .*@sha256:[0-9a-f]{64}(?:\s|$)',D,re.M))==3,
 'runtime never installs scanner packages': 'util-linux-misc' in runtime
     and re.search(r'(?m)^\s*apk add[^\n]*\b(?:clamav-daemon|freshclam)\b', runtime_commands) is None,
 'binary copied from dedicated stage': 'apk add --no-cache clamav-daemon' in extraction
     and 'COPY --from=clamdtop-extract' in runtime,
 'no scanner executable in final': 'test ! -e /usr/sbin/clamd' in runtime
     and 'test ! -e /usr/bin/freshclam' in runtime,
 'Docker healthcheck': 'HEALTHCHECK --interval=30s' in D and 'issue37-healthcheck.sh' in D,
 'anonymous liveness only': '.requestMatchers(HttpMethod.GET,"/actuator/health/liveness").permitAll()' in security,
 'actuator and metrics protections retained': '.requestMatchers("/actuator/**").hasRole("ADMIN")' in security and '.requestMatchers("/actuator/prometheus").permitAll()' in security,
 'H2 and mail starters removed': not any(a in ('spring-boot-h2console','spring-boot-starter-mail') for _,a in coords),
 'H2 frame/CSRF exemptions removed': 'frame.sameOrigin()' not in security and '/h2/' not in security,
 'Java 21 targeted': pom.findtext('./p:properties/p:java.version',namespaces=ns)=='21',
 'JVM memory-aware flags and preserved exec': 'MaxRAMPercentage' in entry and 'exec java' in entry,
 'database drivers retained for compatibility': all(a in [x[1] for x in coords] for a in ('h2','postgresql','mysql-connector-j','mariadb-java-client')),
 'Boot liveness enabled': 'management.endpoint.health.probes.enabled=true' in properties,
 'digest update configuration present': (r/'.github/dependabot.yml').is_file(),
 'offline tests and runtime measurement present': (r/'src/test/java/info/trizub/clamav/webclient/security/Issue37LivenessSecurityTest.java').is_file() and (r/'scripts/issue37-verify-and-compare.sh').is_file(),
}
for name, ok in checks.items():
 print(('PASS' if ok else 'FAIL')+': '+name)
if not all(checks.values()): raise SystemExit(1)
print(f'{len(checks)}/{len(checks)} offline checks passed')
