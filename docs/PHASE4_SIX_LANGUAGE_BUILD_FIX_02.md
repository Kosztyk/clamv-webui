# ClamAV Web UI 2.0.0 — Phase 4 six-language Build Fix 02

## Two independent failures fixed

1. Maven Docker build `testCompile`: `SimpleMeterRegistry` is **not** `AutoCloseable`, so `try (var registry = new SimpleMeterRegistry())` fails. Both `ScanMetricsTest` methods now use normal `try/finally` and call `registry.close()` explicitly. The correct `io.micrometer` import from Fix 01 is preserved.
2. Host `mvn verify`: Maven's `validate`-phase `copy-resources` plugin previously read **every file under `conf`** using `filtering=true`. This attempts to decode the binary `conf/ldap-test-truststore` as text, throwing `MalformedInputException`, and could also copy runtime secrets into `target/conf`. The task now copies **only** `conf/clamav-web-client.properties.example` without filtering. The `.dockerignore` excludes `conf/` entirely to keep mounted credentials/CA/private data out of Docker build context. The runtime container continues to access `/app/conf` via the existing Docker bind mount.

## Deployment — preserve existing settings

The patch is source-only. Do **not** delete `conf/`, `.secrets/`, or the PostgreSQL data, and do not replace your customized `docker-compose.yml` or LDAP truststore. There are no database migrations.

In the directory containing the updated `pom.xml` and existing Compose file, run:

```sh
python3 scripts/phase4-static-check.py
python3 scripts/check-i18n.py
mvn -B -ntp clean verify
docker compose --progress plain build --no-cache clamav-web-client
```

Deploy only after compilation and tests succeed:

```sh
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
```

If verifying Maven first on the host, `mvn clean verify` can delete old `target/` content, but **does not delete `conf/`**. A successful build must be followed by a live LDAP login and scan smoke test.

## Verification status

Static source checks, Java 21 control-flow compilation smoke test, XML parse, ZIP integrity and unchanged-file comparison passed. Full Maven/JUnit and Docker build cannot run in the packaging environment (Maven/Docker and dependency downloads unavailable). Those are release gates for the deployment host, **not** claimed successes.
