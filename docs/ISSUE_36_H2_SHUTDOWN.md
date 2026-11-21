# Issue #36 — file-backed H2 graceful shutdown

Issue: https://github.com/Kosztyk/clamv-webui/issues/36

## Root cause

The historical packaged file-backed URL was:

```properties
spring.datasource.url=jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
```

`DB_CLOSE_DELAY=-1` prevents closing the database on the last connection and **is a persistent H2 database setting**. `DB_CLOSE_ON_EXIT=FALSE` disables the JVM's normal H2 shutdown hook. Consequently, simply removing `DB_CLOSE_ON_EXIT=FALSE` would be incomplete for databases that have already been opened with the old URL.

## Resolution

* Packaged `application.properties`, the Configuration Center catalog default, and its H2 browser preset now use `jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0`.
* Explicit `DB_CLOSE_DELAY=0` resets any previously stored `-1` value when an existing file database is opened, allowing closure when the connection pool finishes closing. `DB_CLOSE_ON_EXIT` is omitted, restoring H2's built-in normal JVM exit behavior.
* `ConfigurationStore` upgrades **only the exact legacy shipped H2 URL** in memory when reading a saved Configuration Center file; it does **not** rewrite `configuration-center.properties` until the user next saves settings. All custom database URLs are otherwise preserved. A Compose or system-property override takes precedence and is **not** silently modified.
* Configuration Center rejects newly submitted file-backed H2 URLs containing a negative close delay or `DB_CLOSE_ON_EXIT=FALSE`; in-memory H2 tests retain their independent `DB_CLOSE_DELAY=-1` setting.
* All four bundled standalone Compose examples grant `stop_grace_period: 60s` to the Web UI service. Add the same option to **existing Compose files**; Docker Compose will not update user-managed files automatically.
* Tests and shipped sample scripts that explicitly opened file-backed H2 with `DB_CLOSE_DELAY=-1` have been updated; `jdbc:h2:mem:` test URLs remain intentionally unchanged.
* The HikariCP regression now checks that closing the actual application connection-pool type removes the H2 `.lock.db` file, and that a fresh pool can read persisted rows.
* The upgraded legacy-file regression creates an H2 database with the **old** `DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` URL, confirms a pool close still leaves the lock, and then connects via the **new** `DB_CLOSE_DELAY=0` URL. Without any explicit `SHUTDOWN` between the pools, it checks the old data is preserved and the lock disappears once the corrected pool closes.
* The Docker smoke test deliberately **does not set `SPRING_DATASOURCE_URL`**; it checks the image's packaged default rather than hiding a broken default behind a test-only override. It also rejects SIGKILL/OOM termination as evidence of a graceful shutdown.

This issue requires **no database schema migration** or change to Flyway migrations V1–V7.

## Important deployment boundaries

Embedded H2 is a **single-instance** persistence option, not a shared database. **PostgreSQL is recommended for production**, particularly for large job histories and repeated container lifecycle operations. Do not run multiple Web UI replicas against the same H2 file. PostgreSQL, MySQL and MariaDB connection settings are unaffected by this issue.

For a new H2 database, a nonempty `SPRING_DATASOURCE_PASSWORD` can be supplied via a private Compose environment value; the connection already supports that setting. The legacy default `sa` account and empty password are retained for existing installations. **Do not add or change the password for an existing H2 database without a controlled H2 credential rotation and a verified backup**, or the Web UI will not be able to authenticate. Keep `conf` and `data` securely mounted and not publicly accessible.

Do not send `SIGKILL`, `docker kill`, or `docker compose down -v` as part of routine shutdown or migration. Allow the application to close naturally; a forced shutdown or power loss can still require recovery on next boot.

## Ubuntu Docker smoke test (isolated, never touches production)

1. Build the new image from the corrected source (`docker compose build clamav-web-client`) and confirm the image tag or image ID (`docker compose images`).
2. Run:

```bash
bash scripts/verify-issue36-h2-shutdown.sh clamav-web-client:2.1.0beta2-local
```

The smoke test creates a **new disposable Docker volume** and container (no host-published ports or network), starts the application using its **packaged H2 default** (with no JDBC URL override), checks the new H2 database file, stops with a 60-second grace period, verifies no `.lock.db` remains, restarts against the same database, scans logs for H2 lock/recovery errors, and stops and verifies once more. The temporary volume and container are automatically deleted; use `KEEP_ISSUE36_VOLUME=1` only when you need to retain a failed test's synthetic database for investigation.

You can also run the JUnit class without starting production:

```bash
mvn -B -Dtest=Issue36H2ShutdownTest test
mvn -B verify
```

**Acceptance criteria:** a normal SIGTERM shutdown removes the file lock, and a subsequent restart reads the same database without H2 recovery or lock errors. A file lock still present **while the application is running** is normal; only check after it has stopped. Tests that reuse the existing production H2 file must be performed only during a planned downtime, after a backup.

### Existing Compose deployment

Add under `services.clamav-web-client`:

```yaml
stop_grace_period: 60s
```

If you explicitly set the old URL in Compose, change it to:

```yaml
SPRING_DATASOURCE_URL: "jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0"
```

Only change `SPRING_DATASOURCE_URL` when **the actual deployment uses that H2 file**. Keep any PostgreSQL/MySQL/MariaDB deployment URL as is. Changes to Compose environment require `docker compose up -d --force-recreate --no-deps clamav-web-client` after the new image has been successfully built and data backed up.

Official H2 documentation: https://h2database.github.io/html/features.html#closing_a_database and https://h2database.github.io/html/commands.html#set_db_close_delay
