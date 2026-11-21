# Issue #34 — Database indexes for frequent job and audit queries

**Local candidate:** ClamAV Web UI v2.1.0beta2, including fixes #25–#33.

## The migration is V6, not V5

Issue #28 already shipped vendor-specific **`V5__scan_jobs_history_indexes.sql`**:

- `idx_scan_jobs_status_submitted_id` on `(status, submitted_at, id)` — supports the queue's oldest-first status scan and its stable tie-breaker;
- `idx_scan_jobs_recent_id` on `(submitted_at DESC, id DESC)` — supports recent-job pagination.

Flyway V5 checksums **must not be changed** after deployment. Issue #34 therefore adds **`V6__common_query_indexes.sql`** in each database's existing Flyway location (H2, PostgreSQL, MySQL and MariaDB), rather than renaming or rewriting V5.

### New indexes

| Index name | Leading columns | Rationale |
| --- | --- | --- |
| `idx_scan_jobs_endpoint_at_id` | `scan_jobs(endpoint_id, submitted_at DESC, id DESC)` | Filter by endpoint and list newest jobs; FK operations can also find referencing rows |
| `idx_scan_jobs_verdict_at_id` | `scan_jobs(verdict, submitted_at DESC, id DESC)` | API verdict filters, newest-first |
| `idx_audit_endpoint_at_id` | `audit_events(endpoint_id, at DESC, id DESC)` | Endpoint-scoped audit lookups and ordered history |
| `idx_audit_username_at_id` | `audit_events(username, at DESC, id DESC)` | Actor-specific audit history |
| `idx_app_user_roles_user` | `app_user_roles(user_id)` | User-role joins on **PostgreSQL and H2** |

**MySQL/MariaDB:** The InnoDB foreign key `fk_app_user_roles_user` already requires and creates/reuses an index beginning with `user_id`. Adding another explicit index would duplicate write/storage work, so V6 does not add that extra index in those two vendors. Tests verify the actual index prefix is present on all four databases, regardless of its name.

Composite indexes support their **leading** equality column, so standalone `scan_jobs(endpoint_id)` or `scan_jobs(verdict)` indexes would be redundant. The existing V1 `idx_scan_jobs_status` remains intact; V5's composite status index supersedes it for ordered queue lookups without altering an existing migration.

## Upgrade safety

- No table definitions, record data, audit content, constraints or existing Flyway migrations are modified.
- Flyway automatically applies **V6** once for databases already migrated through V5. An existing untracked database still requires the documented, operator-reviewed Flyway baseline procedure; **do not** globally enable `SPRING_FLYWAY_BASELINE_ON_MIGRATE` for fresh or already managed installations.
- `CREATE INDEX` may scan and lock a populated table (particularly with large PostgreSQL/MySQL tables). Plan a backup and maintenance window for large job histories. Flyway uses ordinary transactional index creation where supported; do not move this into a blind `CONCURRENTLY` statement or change already-shipped migration SQL.
- These indexes improve selective filters, but low-cardinality verdict values and small tables may legitimately use a sequential scan. They add some storage and write amplification; the SQL planner chooses whether to use them.
- The vendor-specific `V6` files are checked for unique names and supported syntax in source checks. The existing H2 migration fixture and optional `-Pdb-integration` Testcontainers profile check both fresh and baselined upgrades, retained rows, and JDBC index-prefix metadata.

## PostgreSQL EXPLAIN verification

On a staging copy **after Flyway completes**, run:

```sql
ANALYZE scan_jobs;
ANALYZE audit_events;
ANALYZE app_user_roles;

-- Replace :endpoint_id with a real, selective endpoint ID.
EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM scan_jobs
WHERE endpoint_id = :endpoint_id
ORDER BY submitted_at DESC, id DESC LIMIT 50;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM scan_jobs
WHERE verdict = 'VIRUS_FOUND'
ORDER BY submitted_at DESC, id DESC LIMIT 50;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM scan_jobs
WHERE status = 'QUEUED'
ORDER BY submitted_at ASC, id ASC LIMIT 100;

EXPLAIN (ANALYZE, BUFFERS)
SELECT id FROM audit_events
WHERE endpoint_id = :endpoint_id
ORDER BY at DESC, id DESC LIMIT 50;

EXPLAIN (ANALYZE, BUFFERS)
SELECT role FROM app_user_roles WHERE user_id = :user_id;
```

The output may include `Index Scan`, `Index Only Scan` or `Bitmap Index Scan` for sufficiently selective queries and sufficiently populated tables. Do **not** hard-assert that PostgreSQL must choose an index on an empty or very small test table.

Check index definitions directly:

```sql
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = current_schema()
  AND tablename IN ('scan_jobs', 'app_user_roles', 'audit_events')
ORDER BY tablename, indexname;
```

## Tests to run before production

```bash
mvn -B -Dtest=Phase2H2MigrationTest,Issue34IndexMigrationTest test
mvn -B -Pdb-integration verify   # needs Docker: PostgreSQL, MySQL, MariaDB Testcontainers
```

The integration fixture checks fresh and Flyway-baselined legacy schemas, ensures sample scan/audit/user records survive migration, checks every expected leading-index prefix, and re-runs migration to confirm idempotence. Live PostgreSQL `EXPLAIN` plans and Testcontainers builds must be performed on an environment with Maven/Docker; static checks alone cannot establish the acceptance criterion.


**Cumulative audit extension:** V7 adds timestamp-first retention indexes for `(status,finished_at,id)` on `scan_jobs` and `(at,id)` on `audit_events`, and switches cleanup to bounded keyset queries. V1–V6 are untouched. Query-plan examples: [PostgreSQL EXPLAIN](sql/CUMULATIVE_AUDIT_RETENTION_EXPLAIN_POSTGRES.sql). See [audit remediation](CUMULATIVE_AUDIT_REMEDIATION_13-35.md).
