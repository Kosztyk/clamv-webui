#!/usr/bin/env python3
"""Source/SQLite DDL smoke check for issue #34; NOT a vendor migration test.

Run the Java Phase2H2MigrationTest and -Pdb-integration Testcontainers profile
for real H2/PostgreSQL/MySQL/MariaDB migration coverage.
"""
from pathlib import Path
import re
import sqlite3

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources/db/migration'
EXPECTED = {
    ('scan_jobs', ('endpoint_id', 'submitted_at', 'id')),
    ('scan_jobs', ('verdict', 'submitted_at', 'id')),
    ('scan_jobs', ('status', 'submitted_at', 'id')),
    ('app_user_roles', ('user_id',)),
    ('audit_events', ('username', 'at', 'id')),
    ('audit_events', ('endpoint_id', 'at', 'id')),
}
SCHEMA = '''
CREATE TABLE scan_jobs (id TEXT, endpoint_id INTEGER, status TEXT, verdict TEXT, submitted_at TEXT);
CREATE TABLE audit_events (id INTEGER, endpoint_id INTEGER, username TEXT, at TEXT);
CREATE TABLE app_user_roles (user_id INTEGER, role TEXT);
'''
for vendor in ('h2', 'postgresql', 'mysql', 'mariadb'):
    path = ROOT / vendor
    v5 = path / 'V5__scan_jobs_history_indexes.sql'
    v6 = path / 'V6__common_query_indexes.sql'
    assert v5.is_file() and v6.is_file(), f'{vendor}: missing migration'
    assert not (path / 'V6__scan_jobs_history_indexes.sql').exists()
    with sqlite3.connect(':memory:') as c:
        c.executescript(SCHEMA)
        if vendor in ('mysql', 'mariadb'):
            # InnoDB creates/reuses the index for app_user_roles(user_id) FK.
            # SQLite does not, so model this existing, vendor-specific behavior.
            c.execute('CREATE INDEX fk_app_user_roles_user ON app_user_roles(user_id)')
        c.executescript(v5.read_text())
        c.executescript(v6.read_text())
        actual = set()
        for table in ('scan_jobs', 'audit_events', 'app_user_roles'):
            for _, name, *_ in c.execute(f'PRAGMA index_list({table})').fetchall():
                columns = tuple(row[2].lower() for row in
                                c.execute(f'PRAGMA index_info({name})').fetchall())
                for t, prefix in EXPECTED:
                    if t == table and columns[:len(prefix)] == prefix:
                        actual.add((t, prefix))
        missing = EXPECTED - actual
        assert not missing, f'{vendor}: missing indexes {missing}'
        # Capture status index reuse; do not create a duplicate in V6.
        assert 'idx_scan_jobs_status_submitted_id' not in v6.read_text().split('CREATE INDEX')[-1]
        print(f'PASS {vendor}: V5/V6 CREATE INDEX SQL parses under SQLite and all required prefixes exist')
print('NOTE: SQLite smoke check only. Run Java/Testcontainers for actual vendor migrations.')
