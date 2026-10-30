#!/usr/bin/env python3
"""Offline structure checks (not a replacement for Maven/Testcontainers)."""
from pathlib import Path
import xml.etree.ElementTree as ET
root=Path(__file__).resolve().parents[1]
ET.parse(root/'pom.xml')
props=(root/'src/main/resources/application.properties').read_text()
assert 'spring.jpa.hibernate.ddl-auto=validate' in props
assert 'spring.flyway.baseline-on-migrate=false' in props
assert 'spring.flyway.locations=classpath:db/migration/{vendor}' in props
for vendor in ('h2','postgresql','mysql','mariadb'):
 sql=(root/f'src/main/resources/db/migration/{vendor}/V1__initial_schema.sql').read_text()
 assert sql.count('CREATE TABLE ')==8
 assert all(name in sql for name in ('app_users','app_user_roles','scan_jobs','audit_events',
                                     'processed_files','watched_directories','api_credentials','clamd_endpoints'))
 assert 'LONG_PATH' not in sql
 assert ('path_digest' in sql)==(vendor in ('mysql','mariadb'))
assert (root/'src/main/java/info/trizub/clamav/webclient/db/V2__reconcile_legacy_columns.java').exists()
print('PASS: Phase 2 Flyway vendor files, schema inventory, Hibernate validation and POM parsing')
