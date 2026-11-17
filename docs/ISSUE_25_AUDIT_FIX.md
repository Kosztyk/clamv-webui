# Issue #25 — audit field bounds and best-effort persistence

## Root cause

The existing `audit_events` schema uses maximum lengths for `username` (64), `action` (64), `details` (1024), `outcome` (64), `job_id` (64), `transport` (32), `ip` (64), and `user_agent` (255). A 10 KiB HTTP `User-Agent` or long endpoint/watch details could make the audit insert fail *after* a scan job was queued or an admin action succeeded, resulting in a misleading HTTP 500.

## Design

1. `AuditService` bounds every String for all write entry points, including scan submissions and worker completion. It preserves null values and avoids splitting UTF-16 surrogate pairs.
2. `AuditEventWriter` is a separate Spring bean with `@Transactional(propagation=REQUIRES_NEW)` and `saveAndFlush`. This ensures database write/commit errors do not poison a caller transaction; the caller-facing audit facade catches runtime failures and emits a bounded warning without raw submitted details.
3. `recordScan` now records the truncated User-Agent too (previously omitted it). `recordResult` bounds job ID, submitted identity, and submitted transport.
4. No table schema, Flyway migrations, private upload permissions, container mounts, authentication, or controller behavior changed.

## Verification

Recommended on JDK 21:

```bash
mvn -B -Dtest=AuditServiceTest,UiSmokeTest test
mvn -B verify
```

The HTTP test uses Spring MockMvc (so it does not exercise the live connector/proxy header-size limits). Live 10 KiB HTTP User-Agent requests depend on Tomcat and any front proxy accepting such a header; default maximum header sizes can reject a request before `AuditService` runs. This fix prevents a **database audit** failure, but does not and should not bypass network header-size limits.

Test clean/EICAR scan submission, UI/API scans, endpoint creation, watch operations, and audit listing on H2/PostgreSQL/MySQL/MariaDB before deploying. Audit write failures are best-effort warnings; monitor application logs if you require a complete audit trail. Optional audit retention is not introduced in this change.
