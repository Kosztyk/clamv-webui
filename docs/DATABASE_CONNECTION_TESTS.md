# Database Test connection — v2.1.0 correction

The same database probe serves direct `/setup/` before Spring starts, protected recovery after startup, and the authenticated Configuration Center. The HTTP scheme used by the browser is separate from the database connection's TLS/JDBC configuration. Direct HTTP still requires the existing explicit confirmation before sending a new password.

## Why Test could fail while Start application succeeded

The old probe imposed a **three-second connection/socket timeout** and a seven-second overall deadline. Normal application startup used the configured connection-pool budget and different driver defaults. A PostgreSQL server needing four seconds to complete authentication could therefore fail the test while the application could connect successfully afterward. The old error handler replaced the actual JDBC failure with a generic address/credentials/certificate/network message.

A separate H2 case also produced that generic error: the probe deliberately refused to create a new database file, whereas Start application created it. Existing H2 URLs containing `IFEXISTS` could also fail testing because the probe appended a conflicting duplicate option.

These paths were reproduced with isolated test data. They do not establish which exception a particular external deployment returned; the corrected diagnostics now distinguish those causes without exposing connection values.

## Corrected behavior for every supported database

| Database | Driver | Test behavior |
|---|---|---|
| PostgreSQL | `org.postgresql.Driver` | Authenticate to the selected existing database and run `SELECT 1`; connect/read/login timeout values use seconds. |
| MySQL | `com.mysql.cj.jdbc.Driver` | Authenticate and run `SELECT 1`; connect/read timeout values use milliseconds. Unknown database and permission errors use the vendor's SQL states/error codes. |
| MariaDB | `org.mariadb.jdbc.Driver` | Authenticate and run `SELECT 1`; connect/read timeout values use milliseconds. The distinct JDBC URL selects the MariaDB driver explicitly. |
| Existing H2 | `org.h2.Driver` | Verify the supplied credentials and `SELECT 1`, without Flyway, schema creation or data copying. The probe replaces any existing `IFEXISTS` option with `TRUE`. |
| New H2 | `org.h2.Driver` | Return HTTP 200 with `ok: false` and `status: not_initialized`. The GUI shows an amber informational message explaining that the connection/credentials are unverified and initialization happens through Review → Apply → Start application. No database file is created by the test. |

The probe initializes the driver matching the selected JDBC family using the same allow-listed mapping as configuration validation. It does not rely on which driver happened to initialize earlier in the process.

If the saved H2 URL explicitly includes `IFEXISTS=TRUE`, startup also requires an existing database. Remove that option or use `IFEXISTS=FALSE` when intentionally initializing a new one. The probe itself always requires an existing database and never changes the saved URL.

Database connection/read budgets follow **Connections → Database & persistence → Advanced settings → Pool connection timeout (ms)**, limited to **1–30 seconds** for a bounded interactive test. The default is **10 seconds**. PostgreSQL rounds fractional seconds upward. The total database-test deadline adds five seconds for query validation/cleanup; the default is 15 seconds and maximum 35 seconds. `SELECT 1` retains its two-second query timeout. Other service tests retain their existing seven-second overall deadline. Driver-specific URL options remain relevant; a deliberately stricter timeout in the URL can still cause a timeout.

Tests remain on a separate bounded executor. Testing does not save a draft, replace a retained password, create an external database, migrate schema, copy data or start the application. A test against a new H2 database is not reported as a successful authenticated connection. The normal administration audit records this case as `NOT_INITIALIZED`.

## Diagnostics

Errors distinguish rejected credentials/authentication rules, a missing external database, insufficient permissions, certificate/TLS problems, hostname resolution, refused connections and timeouts. H2 storage/option/locking errors are separate from a missing first-run database. Messages contain no raw JDBC exception text, URL, hostname, username or password.

For an external database, create the database and grant the application account appropriate access before testing. Use its own TLS policy and a trusted certificate; the fix does not turn off certificate verification. A newly staged application truststore must be activated by restart before a probe can use it. A successful connection test confirms connectivity/authentication and a simple query, not every privilege needed by later migrations.

## Regression coverage

- Actual H2 files: missing database, existing database, wrong credentials, retained marker table and no Flyway history, and both `IFEXISTS=TRUE/FALSE` input cases.
- All four packaged JDBC drivers: allow-listed selection, correct timeout units, rounding and bounded budgets.
- Actual PostgreSQL JDBC driver against a loopback protocol fixture: authentication, `SELECT 1`, bad credentials, missing database, and a four-second login exceeding the old timeout. This is a wire fixture, not a PostgreSQL server.
- The same four-second PostgreSQL fixture also authenticates through the application's Hikari connection pool. Both paths now accept that login with the default ten-second budget.
- Native MySQL **8.0.46** and MariaDB **10.11.14**, each with a temporary database and synthetic account over loopback TCP: the packaged app tests an unsaved draft before Spring startup, rejects a wrong password, identifies a missing database, leaves the schema/revision untouched during testing, then saves, starts, migrates, authenticates the administrator and successfully retests. Both engines passed all five reported stages. These are actual database engines, separate from the PostgreSQL protocol fixture.
- Real browser and packaged application over direct HTTP: first-run H2 warning, HTTP consent, slow PostgreSQL testing, failure/correction/retest, unchanged draft revision, review/apply/start, verified initialized H2 and unchanged saved credentials after failed tests.
- The existing Docker/Testcontainers suite now tests unsaved credentials before migrations and the same connection afterward for PostgreSQL 17, MySQL 8.4 and MariaDB 11.8. It runs in the `db-integration` profile and the database CI workflow when Docker is available. Those external-engine tests are not claimed as local passes without Docker.

The MySQL/MariaDB servers can return a permission error for an inaccessible database instead of revealing whether it exists. The probe follows that server result; it does not guess existence or retry with a more privileged account. No live PostgreSQL server or Docker image was run in this validation environment.

Reproduce the Docker-backed suite:

```bash
mvn -B -Pdb-integration verify
```

Run the direct HTTP browser regression after building the JAR and installing Playwright/Chromium:

```bash
python3 scripts/test_configuration_database_setup.py
```

This correction is inside the ordinary application source/JAR. Build the complete source normally:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

Keep existing persistent conf/data volumes and database selection when replacing the image. No special Compose fields or build helper flag is required. See [Configuration Center](CONFIGURATION_CENTER.md), [the complete guide](COMPLETE_USER_GUIDE.md), [validation](CONFIGURATION_CENTER_VALIDATION.md) and [source-package privacy](SOURCE_PACKAGE_PRIVACY.md).

Driver references: [PostgreSQL JDBC settings](https://jdbc.postgresql.org/documentation/use/), [MySQL Connector/J networking settings](https://dev.mysql.com/doc/connector-j/en/connector-j-connp-props-networking.html), [MariaDB Connector/J settings](https://mariadb.com/docs/connectors/mariadb-connector-j/about-mariadb-connector-j).
