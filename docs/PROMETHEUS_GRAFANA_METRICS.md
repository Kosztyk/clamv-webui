# Prometheus / Grafana metric contract (issue #7)

The application remains v2.0.0. The existing LDAP/OIDC changes, database setup and
mounted configuration/data remain compatible. No database migration is required.

## Public metrics

| Prometheus name | Kind | Labels / meaning |
|---|---|---|
| `clamav_scans_total` | Counter | `type="upload|path|watch"`, `verdict="ok|virus_found|error"`; completed scan jobs |
| `clamav_scan_duration_seconds` | Histogram family | `type="upload|path|watch"`; `_bucket`, `_count`, `_sum` and `_max` series; execution time excluding queue time |
| `clamav_viruses_detected_total` | Counter | `signature="..."`; unique file/signature detections from completed positive jobs |
| `clamav_queue_items` | Gauge | ClamD queued items from native STATS |
| `clamav_threads_live` | Gauge | Live ClamD threads |
| `clamav_threads_idle` | Gauge | Idle ClamD threads |
| `clamav_threads_busy` | Gauge | Busy ClamD threads (`live - idle`) |
| `clamav_pools_used_bytes` | Gauge | Used pool memory from the STATS `MEMSTATS` line |
| `clamav_pools_total_bytes` | Gauge | Total pool memory from the STATS `MEMSTATS` line |
| `clamav_database_version` | Gauge | Numeric signature database version from VERSION |
| `clamav_database_timestamp` | Gauge | Signature database timestamp as Unix seconds |

Daemon gauges also have a fixed `slot` label (`0`–`11`). Prometheus supplies its
normal `job` and `instance` labels. The dashboard filters all three independently.
Map a slot to the current endpoint under Admin → Configuration. Slots follow the
ordered enabled endpoints and are not permanent identities across reconfiguration.

`clamav_threads_max` supports thread-saturation queries. `clamav_database_age`
provides age in seconds. Existing worker, queued/running job and endpoint
availability/staleness metrics remain available.

## Counting and telemetry semantics

- Each completed job increments the scan counter once at the shared completion
  boundary, regardless of how many files or signatures it contains.
- Each unique file/signature pair increments its signature counter. The same
  signature in two files counts twice. Duplicate signature entries for one file
  count once. Only stored result values become labels; file keys, usernames and
  job IDs are not exported as labels.
- A positive job without readable signature metadata increments
  `signature="unknown"` once. Clean and failed jobs do not increment detection
  counters. The zero-valued `unknown` series is initialized at startup.
- All nine normal scan type/verdict combinations and three duration histograms
  start at zero. Counters reset on process restart. Prometheus stores historical
  samples; existing database jobs are not replayed into counters.
- Native `QUEUE: N items` is accepted. A missing queue capacity remains unknown.
  Repeated pool sections contribute to aggregate thread and queue counts.
- ClamD prints its `M` memory suffix using 1024 × 1024 bytes. Reported values are
  converted to bytes and rounded to whole bytes; precision remains limited by
  ClamD's rounded source values. Missing/invalid memory stays NaN, not zero.
- `api.v1.clamd.database-timezone` (default UTC) must match the timezone used in
  ClamD's VERSION timestamp. Invalid timestamps remain NaN. An unreachable daemon
  clears its numeric sample and reports unavailable/stale.

## Enable scraping

Keep the existing deployment configuration and supply
`CLAMAV_METRICS_SCRAPE_TOKEN` with at least 24 characters. Requests to
`GET /actuator/prometheus` require `Authorization: Bearer YOUR_TOKEN`. Missing or
incorrect tokens return 403, including requests with an administrator session.
Valid requests return Prometheus exposition text.

Use `dashboards/prometheus.yml.example` to configure your target and token file.
Optional management-port setup is described in [PHASE4_OPERATIONS.md](PHASE4_OPERATIONS.md).

## Grafana dashboard and alerts

Import `dashboards/grafana-clamav-webui.json` and select the Prometheus datasource.
The dashboard includes 18 panels and job/instance/slot filters, covering:

- Scan rate and verdict distribution, plus execution-duration p95.
- Virus detection timeline and a table of the top 25 detected signatures.
- Daemon queue count, separate live/idle/busy/max thread series and saturation.
- Used/total pool memory in bytes.
- Signature database version, timestamp and a freshness indicator.
- Application queue/worker state, scan failures and daemon availability/staleness.

The detection timeline counts malware-positive jobs within each rate interval.
The signature table shows cumulative per-file/per-signature counts since the
selected application processes started; its description states this explicitly.
Freshness uses green below 24 hours, amber from 24 hours and red from 168 hours.
Multiple thread/job series use separate targets, so a set-union query cannot hide
the second series. Unconfigured endpoint slots are excluded from daemon panels.

`dashboards/clamav-alerts.yml` uses the canonical metric names for detections,
queue depth, daemon threads, memory spikes and database freshness. Its thresholds
are examples to tune for the intended workload; queue alerts do not require a
nonexistent queue-capacity value.

## Compatibility

| Previous name / labels | Current behaviour |
|---|---|
| `clamav_scan_completed_total` with uppercase type/verdict | Retained as a compatibility counter; use `clamav_scans_total` for the new contract |
| `clamav_scan_detections_total` | Retained as a count of malware-positive jobs; the new signature counter counts file/signature pairs |
| `clamav_scan_duration_seconds` with uppercase type/verdict | Now uses lowercase `type` only; update any custom queries selecting uppercase values or `verdict` |
| `clamav_clamd_queue_items`, `clamav_clamd_threads_*`, `clamav_clamd_database_version` / `_age` | Retained as aliases to the same daemon sample |
| `clamav_clamd_queue_max` | Retained, but NaN when the native response does not report capacity |

Use one naming family in aggregate queries so compatibility aliases are not
counted twice. Signature-specific series are created as their signatures occur.

## References and validation

- [ClamD native STATS formatting](https://github.com/Cisco-Talos/clamav/blob/main/clamd/thrmgr.c)
- [Micrometer Prometheus timers and naming](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)
- [Completed validation and runtime limits](METRICS_VALIDATION.md)
