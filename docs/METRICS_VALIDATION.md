# Prometheus / Grafana update — validation

Validation date: 30 October 2026 (Europe/Bucharest). Application version: 2.0.0.

## Completed checks

- Maven `clean verify`: **BUILD SUCCESS — 153 tests, 0 failures, 0 errors,
  0 skipped**. The executable Spring Boot JAR was packaged successfully.
- Actual Prometheus exposition was checked for every requested public name and
  label: lowercase scan types/verdicts, type-only duration histograms, signature
  counters, queue/thread gauges, memory bytes and database version/timestamp.
- Full application MVC checks verified missing/incorrect scrape tokens return
  403, an administrator session cannot bypass the token, and a valid token returns
  the requested metrics through `/actuator/prometheus`.
- Representative native ClamD STATS fixtures exercised `QUEUE: 0 items`, queue
  timing suffixes, multiple thread pools and MEMSTATS unit conversion. Missing or
  invalid telemetry stays unknown and unreachable samples are cleared.
- Database timestamp tests verified UTC and Europe/Bucharest conversion.
- Per-signature tests covered multiple files/signatures, duplicate entries for
  one file, missing/legacy metadata and exclusion of file paths from labels.
- Dashboard acceptance tests checked the requested views and separate thread/job
  targets. Alert checks verified canonical names and full endpoint matching.
- All **23 dashboard PromQL expressions and 9 alert expressions parsed
  successfully** using `promql-parser 0.11.0`, with Grafana variables replaced by
  representative values. The parser was a validation-only tool; it is not a
  project or application dependency.
- Phase 1, Phase 2 and Phase 4 structural checks passed. Six-language catalog and
  JavaScript syntax checks passed. No existing migration files changed.

## Added acceptance coverage

The suite increased from 129 to 153 tests: **24 added cases**.

| Class | New cases | Main coverage |
|---|---:|---|
| `ScanMetricsTest` | 5 | Zero series, exact lowercase labels, signature counting/fallback, duration semantics and exclusion of file paths |
| `ClamdTopServiceTest` | 4 | Native queue count, MEMSTATS input, multi-pool aggregation and invalid queue handling |
| `ClamdMetricsCollectorTest` | 11 | Canonical/legacy gauge values, outage invalidation, unknown data, timezone conversion and binary memory units |
| `PrometheusMetricsContractTest` | 1 | Real Prometheus serializer names, labels, histograms and numeric values |
| `PrometheusEndpointTest` | 1 | Full application actuator route, token enforcement and returned exposition |
| `MonitoringDashboardTest` | 2 | Required Grafana views, independent targets and canonical alert expressions |

Existing LDAP/OIDC, role mapping, API, scan completion, webhook, UI and H2 tests
also passed. Mockito's existing Byte Buddy agent was loaded at test JVM startup
because automatic attachment is unavailable in this runtime; production
dependencies and the Dockerfile require no change.

## Limits

- The ClamD samples are fixtures based on the native format; a live daemon was
  not used for this update's telemetry acceptance.
- Grafana import/rendering and a deployed Prometheus scrape/rule evaluation were
  not exercised. Syntax parsing is separate from live query evaluation.
- Docker execution and the external-database integration profile were not run.
- Browser fixtures were not run because Playwright and Chromium are unavailable.
  The combined static release gate stops at the Playwright import in its
  authentication browser script. Structural checks and Maven verification ran
  separately; the combined gate is not reported as successful.

For deployment acceptance, enable the existing scrape token, verify 403/200
behaviour on the chosen listener, complete clean/infected scans, sample a live
daemon and import the supplied Grafana dashboard with the intended datasource.
