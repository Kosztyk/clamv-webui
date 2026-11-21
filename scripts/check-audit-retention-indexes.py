#!/usr/bin/env python3
"""Portable V7 DDL and timestamp+ID keyset smoke check (SQLite, NOT vendor Flyway)."""
import sqlite3
from pathlib import Path
root=Path(__file__).resolve().parent.parent
expected=(('scan_jobs',['status','finished_at','id']),('audit_events',['at','id']))
for vendor in ('h2','postgresql','mysql','mariadb'):
    migrations=root/'src/main/resources/db/migration'/vendor
    path=migrations/'V7__history_retention_indexes.sql'
    assert path.is_file(),path
    db=sqlite3.connect(':memory:')
    db.executescript('''CREATE TABLE scan_jobs (id TEXT PRIMARY KEY, status TEXT, finished_at TEXT, stored_path TEXT, quarantine_path TEXT);
                    CREATE TABLE audit_events (id INTEGER PRIMARY KEY, at TEXT);''')
    db.executescript(path.read_text())
    for table, prefix in expected:
        indexes=db.execute(f'PRAGMA index_list({table})').fetchall()
        matches=[]
        for _,name,*_ in indexes:
            cols=[r[2] for r in db.execute(f'PRAGMA index_info({name})')]
            if cols[:len(prefix)]==prefix:matches.append(name)
        assert matches,(vendor,table,prefix,indexes)
    # Timestamp order is deliberately opposite primary key order, and every
    # timestamp has duplicates (for the ID tie-breaker).
    db.executemany('INSERT INTO scan_jobs(id,status,finished_at) VALUES(?,?,?)',
            [(f'{610-i:04}', 'FINISHED', f'2026-01-{i//90+1:02d}T00:{i//3%60:02d}:00Z') for i in range(610)] +
            [('active','QUEUED','2026-01-01T00:00:00Z')])
    cutoff='2026-02-01T00:00:00Z';after_time=None;after_id='';allids=[]
    while True:
        if after_time is None:
            rows=db.execute('''SELECT id,finished_at FROM scan_jobs WHERE status='FINISHED'
                         AND finished_at < ? ORDER BY finished_at,id LIMIT 100''',(cutoff,)).fetchall()
        else:
            rows=db.execute('''SELECT id,finished_at FROM scan_jobs WHERE status='FINISHED'
                         AND finished_at < ? AND (finished_at > ? OR (finished_at = ? AND id > ?))
                         ORDER BY finished_at,id LIMIT 100''',(cutoff,after_time,after_time,after_id)).fetchall()
        if not rows:break
        allids.extend(row[0] for row in rows)
        after_id,after_time=rows[-1]
    assert len(allids)==610 and len(set(allids))==610 and 'active' not in allids,(vendor,len(allids))
    db.executemany('INSERT INTO audit_events(at) VALUES(?)',
            [(f'2026-01-{i//90+1:02d}T00:{i//3%60:02d}:00Z',) for i in range(610)])
    at=None;last=0;count=0
    while True:
        if at is None:
            rows=db.execute('SELECT id,at FROM audit_events WHERE at < ? ORDER BY at,id LIMIT 100',(cutoff,)).fetchall()
        else:
            rows=db.execute('''SELECT id,at FROM audit_events WHERE at < ?
                    AND (at > ? OR (at = ? AND id > ?)) ORDER BY at,id LIMIT 100''',(cutoff,at,at,last)).fetchall()
        if not rows:break
        count+=len(rows);last,at=rows[-1]
    assert count==610,(vendor,count)
    plan=' '.join(str(x) for x in db.execute('''EXPLAIN QUERY PLAN SELECT id FROM scan_jobs
          WHERE status='FINISHED' AND finished_at < '2026-02-01'
          ORDER BY finished_at,id LIMIT 100''').fetchall())
    assert 'idx_scan_jobs_retention' in plan,(vendor,plan)
    print(f'PASS {vendor}: V7 DDL, index prefixes, 610-row job and audit keyset coverage (SQLite only)')
    db.close()
print('NOTE: SQLite smoke tests do NOT replace H2/PG/MySQL/MariaDB Flyway tests or PostgreSQL EXPLAIN.')
