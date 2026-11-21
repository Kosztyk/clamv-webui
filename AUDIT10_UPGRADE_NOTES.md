# Cumulative audit fixes — operational upgrade notes

**Baseline:** v2.1.0beta2 audit fix #8 archive. The replacement package retains previous fixes #1–8 and patches all ten subsequent findings.

## Data migration (NEW)

Flyway V9 is added to each supported database vendor folder (H2/PostgreSQL/MySQL/MariaDB). It changes `processed_files` uniqueness from global path to (endpoint, path), including the MySQL/MariaDB SHA-256 digest index. Old records have unknown endpoint ownership and will be ignored and lazily pruned during hourly watcher housekeeping. All watched files become eligible for a one-time rescan. V9 also adds `quarantine_artifacts` with foreign-key cascade cleanup and indexes.

Back up your existing database and persistent `data/` and `conf/` volumes before deploying. Test migration against a *copy* of the real database. The old application cannot safely operate against a schema already migrated to V9 without a rollback plan.

## Behavior and compatibility

There are no API schema changes and no new mandatory environment variables. API path redaction is preserved. A quarantined PATH/WATCH scan now records its first successful destination in the existing job column and each individual move in the journal table. `spring.lifecycle.timeout-per-shutdown-phase` defaults to 45s with a combined scan wait of at most 40s; Docker Compose keeps 60s stop grace. Adjust custom deployment timeouts together.

The journal is a recovery ledger for filesystem operations; it does **not** make a filesystem rename and a database commit an atomic distributed transaction. Startup reconciliation handles the known crash windows and fails closed if it cannot finish reconciliation.

## Required preproduction validation

```bash
mvn -B -ntp clean verify
mvn -B -ntp -Pdb-integration verify
python3 scripts/test-2026-cumulative-regressions.py
bash scripts/run-cumulative-audit-regressions.sh
```

The cumulative runner also needs Docker, Maven, and a real ClamAV binary (`CLAMD_BIN`). Test H2/PostgreSQL/MySQL/MariaDB V8→V9 upgrades, endpoint reassignments/overlaps, quarantine crash windows, detection truncation, scanner I/O failures, restart, and SIGTERM under load before production.

## Follow-up V11 quarantine identity migration

See `docs/QUARANTINE_IDENTITY_V11.md`. In addition to V9/V10, V11 adds three
nullable evidence/outcome fields to `quarantine_artifacts` and an optional
quarantine-intent field to `scan_jobs`. Legacy PREPARED entries with no source
identity are not moved by pathname on restart; they yield explicit warnings.
Back up before upgrading, verify all four Flyway vendors, and restore a matching
backup to downgrade.
