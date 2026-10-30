# ClamAV Web UI v2.0.0 — consolidated release audit

## Source lineage and scope

This package is based on the six-language Phase 4 **Build Fix 02** source that the operator built on 2026-10-29. No Java application, template, JavaScript, SQL migration, or translation files were changed for this audit package.

## Evidence from the operator's build log

The operator provided the log of `mvn -B -ntp clean verify` (2026-10-29), reporting:

- Maven `BUILD SUCCESS`.
- 95 tests, 0 failures, 0 errors, 0 skipped.
- Packaged executable `target/clamav-web-client-2.0.0.jar`.
- H2 migration tests reached Flyway V4.
- `ScanMetricsTest`, `PrometheusAccessFilterTest`, `ScanCompletionBoundaryTest`, authentication controller tests, and frontend controller smoke tests passed.

This evidence applies to the previous Build Fix 02 Java source; Java source in this package is byte-identical. The results do **not** prove a full Docker image build, production PostgreSQL compatibility, live LDAP/OIDC authentication, or successful Prometheus/Grafana operation.

## Additional audit fixes (packaging / CI only)

1. In the **example** `docker-compose.yml`, `CLAMAV_HOST` now points to `clamav-server` — the included Compose service — rather than a private LAN IP that would not resolve on a CI runner. If clamd is externally hosted, supply its actual host address in your production Compose file.
2. The Phase 0 CI workflow launches `bash scripts/smoke-eicar.sh`; invoking the non-executable file directly would otherwise fail with `Permission denied` on CI.
3. CI now runs the consolidated static regression and localization checks.
4. `scripts/release-gate.sh` adds repeatable source and Maven checks and an optional image-build test.

## Run the checks on the target server

```bash
bash scripts/release-gate.sh
# Optional, to additionally validate the sample Compose and build a fresh image:
bash scripts/release-gate.sh --docker-build
```

The latter builds the `clamav-web-client` service but does not deploy or alter database data. If using a customized production Compose, compare rather than replace it.

After a clean build, verify on the **existing** application:

- Login via the NGINX HTTPS URL; ensure the redirect stays HTTPS.
- LDAP authentication and admin/operator/viewer group permissions; save/reload mappings.
- OIDC login + logout against your actual identity provider, if configured.
- Upload scan / scan job completion, ClamdTop terminal, REST API and scan reports.
- Prometheus token 403 without credentials and authenticated scrape with the configured token.
- Switch all six UI languages, including after logout.
- Compare persisted PostgreSQL data before and after container recreate.

## Important: preserve running configuration

Do **not** overwrite your live `docker-compose.yml`, `.env`, `conf/`, `data/`, LDAP truststore, PostgreSQL database or nginx config when extracting the source. The bundled Compose is only an example. The production installation uses ClamAV UI `192.168.68.112:8081` behind HTTPS NGINX `192.168.68.112:443` and may have different service/environment configuration.

## Release disposition

**Build/test baseline confirmed by the operator's Maven log. Runtime acceptance tests remain pending.** No claim is made that remote OIDC, LDAPS, PostgreSQL, Prometheus, and Grafana were independently tested in this environment.
