# Phase 2: Database portability (feature request #5)

## Scope / safety status

**Candidate pending the database integration suite and host-side scan tests.** This
adds H2, PostgreSQL, MySQL and MariaDB drivers, vendor SQL baselines and Flyway.
Schema changes are executed before Hibernate `ddl-auto=validate`, not `update`.
The same Spring repositories, scan jobs, browser/UI, phase-1 API tokens, REST
monitor, native ClamdTop and Discord configuration remain intact.

**Multi-replica/high availability is NOT supported by this release.**
An atomic status transition prevents duplicate job claiming, and a periodic
reconciler retries persisted QUEUED jobs after transient DB connectivity loss, but the initial
requeue logic and watcher loop are single-instance. Uploads are stored on the
local configured filesystem, not a shared durable object store; a second
replica cannot safely execute those jobs. The watcher has no distributed leader
lease, jobs have no renewable ownership lease, and recovery while a worker is
alive is not coordinated. Do not run `--scale` or point multiple running Web UI
containers at the same DB. An external DB is not a clustering guarantee.

## Schema inventory (from JPA entities in this Phase 1 snapshot)

| Table | Role | Essential columns |
|---|---|---|
| `app_users` | account credentials | `id`, `username`, `password_hash`, `enabled`, `created_at`, `last_login_at` |
| `app_user_roles` | roles | `user_id`, `role` (Set<String> element collection) |
| `api_credentials` | Phase 1 hashed tokens | `token_hash` unique, `scopes`, `endpoint_ids`, `path_roots`, timestamps |
| `clamd_endpoints` | ClamD connections | `id`, `name` unique, `host`, `port`, `platform`, `enabled` |
| `scan_jobs` | jobs & outcomes | `id`, `type`, `status`, `verdict`, `target`, `stored_path`, `found_viruses_json` TEXT, `error_message` TEXT, endpoint & timestamps |
| `audit_events` | actors and outcomes | `id`, `at`, `username`, `action`, `details`, `job_id`, `endpoint_id`, `transport` |
| `watched_directories` | watcher state | `id`, `path`, `endpoint_id`, `enabled` |
| `processed_files` | processed watcher paths | `id`, `path`, `last_modified`, `size_bytes`, `sha256`, `processed_at` |

Default settings and webhook URL are in mounted `./conf`, **not** in the DB.
Uploads and quarantine live in mounted `./data`; restoring only SQL will not
restore those files. Preserve both volumes.

Vendor migrations `src/main/resources/db/migration/{h2,postgresql,mysql,mariadb}`
create V1 for empty databases. Java migration V2 adds any missing Phase 1 token
columns, transport/audit columns and indexes to legacy databases, **without
altering/dropping existing values**. MySQL/MariaDB need a generated SHA-256
path digest index because 4096-character paths cannot be indexed directly in
utf8mb4; full paths remain stored, and hash collision risk is negligible.

## New installation — no variables needed for H2

Use the existing `docker-compose.yml`, unchanged: the default is
`jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`
with `sa` / empty password. Flyway creates schema V1+V2; Hibernate validates.
First run creates one administrator credential, never an insecure default.
If the data directory already contains a DB, **do not treat it as new**.

## PostgreSQL / MySQL / MariaDB configuration

Set environment variables on `clamav-web-client` (illustrative ONLY):

```yaml
SPRING_DATASOURCE_URL: "jdbc:postgresql://db:5432/clamav"
SPRING_DATASOURCE_USERNAME: "clamav"
SPRING_DATASOURCE_PASSWORD: "<secret from Docker secret or local config>"
```

MySQL URL: `jdbc:mysql://db:3306/clamav?connectionTimeZone=UTC`.
MariaDB URL: `jdbc:mariadb://db:3306/clamav`.
Use unique, restricted DB credentials, TLS to remote databases, UTF-8 / utf8mb4
(and synchronized clocks). Do not expose DB ports publicly. Schema `clamav`
must exist, be empty for new installations and be writable for schema setup.
Do not switch `SPRING_DATASOURCE_URL` to point at a different DB expecting
automatic data copying. Migration between DB products requires a separate
export/import and integrity verification (e.g. timestamps/auto IDs).

## EXISTING populated H2 / PostgreSQL — manual baseline procedure

1. Stop the Web UI completely. Do NOT delete SQL data, uploads, quarantine,
   configuration, or rotate keys while migrating. Back up the whole
   `./data` and `./conf` directories; for PostgreSQL use a consistent `pg_dump
   -Fc` plus backed-up mounted data. For MySQL/MariaDB use a consistent
   `mysqldump --single-transaction --routines --triggers` or physical backup.
2. On a *clone* of that database, inspect `INFORMATION_SCHEMA`, enumerate
   row counts in the eight tables above and any historical variations. For
   older builds without API credentials, the `api_credentials` table may be
   absent. Verify all seven core tables first. In PostgreSQL, inspect type of
   `scan_jobs.found_viruses_json` and `scan_jobs.error_message`; see below.
3. For a cleanly identified, backed-up **legacy** database without
   `flyway_schema_history`, set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` on
   the CLONED deployment for **one controlled startup only**. Flyway records
   V1 as the legacy baseline and applies the non-destructive V2 column/index
   reconciliation. `spring.jpa.hibernate.ddl-auto=validate` then blocks schema
   mismatches, rather than modifying more data. Do NOT enable baseline for an
   unrecognized or partially initialized schema.
4. Compare `SELECT count(*)` for every table and sample jobs/roles/audit fields
   against backups; run `/api/v1/health/ready` and `/api/v1/health/clamd` and
   complete a clean/EICAR scan, with job/audit persistence after restarting.
5. Remove `SPRING_FLYWAY_BASELINE_ON_MIGRATE` and restart. It should remain
   **false** for every subsequent deployment. Keep the backup for rollback.

**Important:** `baselineOnMigrate` acknowledges the *current* layout as already
matching V1. It does not copy data. V2 adds missing fields only and fails
closed when essential tables are absent or PostgreSQL OIDs are detected. On
some substantially older variants additional schema reconciliation may be
required; restore a clone instead of using Hibernate `update` blindly.

### PostgreSQL historical OID Large Objects

Historical `@Lob` mappings may store OID identifiers in
`scan_jobs.found_viruses_json` or `scan_jobs.error_message`. They are not
normal VARCHAR/TEXT, and Hibernate validation cannot accept them. Phase 2
**does not auto-convert or drop** these columns: Flyway V2 stops with an
explicit error if either column is OID.

Inspect before any changes:

```sql
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema=current_schema() AND table_name='scan_jobs'
  AND column_name IN ('found_viruses_json','error_message');
```

On a verified, restored copy, the previously provided
`db/PostgresLobMigration.java` code remains available as an **explicit opt-in**
via `APP_DB_POSTGRES_LOB_AUTO_MIGRATE=true` (do not enable during first
Flyway baseline; Hibernate validation happens first). Prefer a planned,
transactional DBA-guided manual conversion of each OID with `lo_get`,
`convert_from(..., 'UTF8')`, row-count and value checks, then rename columns.
Do **not** call `lo_unlink`, drop original OID columns, or modify production
without checked backups and a rollback plan. The conversion may require
increased locks and disk space; coordinate downtime.

## Rollback and disaster recovery

Stop all writers before restoring DB and the **matching** `data` and `conf`
backups. Roll back both application and schema/data to a consistent checkpoint.
Do not remove Flyway history alone or blindly run `flyway repair` to bypass
validation. On MySQL/MariaDB DDL auto-commits: validate in staging first,
and restore the backup if migration fails midway.

## Verification on a Docker-enabled CI/developer host

```
mvn -B verify                     # unit + H2 fresh/legacy migration tests
mvn -B -Pdb-integration verify     # also starts PostgreSQL, MySQL and MariaDB Testcontainers
```

The normal Dockerfile runs `mvn verify` while building the image, without
mounting a Docker socket. The Testcontainers suite is deliberately in the
`db-integration` Maven profile, which must be run on a **Docker-enabled host**
(not from the build stage). CI contains a separate job for the integration
profile. The tests use disposable databases, seed representative users, roles,
audit, jobs, watcher rows, and run new + upgrade migrations. They do not
validate production credentials, uploaded-file persistence after multi-replica
failover, or real ClamD scans; use manual acceptance checks.

## Outage/restart manual acceptance

1. Test on a disposable external DB. Submit a scan; verify FINISHED, persisted
   verdict and audit. Stop the Web UI and start again; confirm the same rows.
2. Pause/stop the database temporarily; expect readiness DOWN, not false UP.
   Observe failure handling and retry of the container, then restore DB. If
   restart is necessary to recover abandoned RUNNING jobs, restart only the
   single Web UI instance (do not scale replicas). Do not delete pending uploads.
3. Confirm that resumed jobs use the same original file and scan outcome; a
   duplicate queue entry must not claim an already RUNNING/FINISHED job.
4. The queued-job reconciler attempts to re-enqueue persisted QUEUED work
   after the DB becomes accessible; verify no duplicate runs or results.
5. Keep watchers single-instance. The database alone cannot guarantee a
   single watcher or access to uploaded files across separate containers.

### Phase 2 completion criteria

- [ ] Clean H2/PostgreSQL/MySQL/MariaDB schema migrations tested in CI
- [ ] Legacy upgraded data retention tested in CI and verified on snapshots
- [ ] Real clean+infected scan job persistence after restart for all four DBs
- [ ] Database outage/restart tested on a disposable environment
- [ ] HA claiming lease + shared uploads + watcher leadership built and tested

The last item is **not implemented in this candidate**, so do not declare HA.
