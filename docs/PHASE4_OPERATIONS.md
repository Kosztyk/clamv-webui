# Phase 4 — Operations: Prometheus, Grafana and ClamD configuration observer

## Baseline and version

The application remains **v2.0.0** as requested for the seven roadmap items. This is the Phase 4 source implementation, not a claim of production validation. No database migration is necessary. Previous settings and mounted `./data` / `./conf` are preserved.

## Scope

- UI: fixes clipped language menu, consistent sidebar icon/width/alignment, API Reports below API Scan; Configuration added under Administration. No existing UI features removed.
- Scan metrics: completed jobs counted **once at ScanExecutionService's common terminal completion boundary**, using `clamav_scans_total` with lowercase `type` and `verdict` labels. Timer histograms record execution time with lowercase `type`, excluding time spent in QUEUED state. `clamav_viruses_detected_total{signature="..."}` counts unique file/signature detections from stored results; file paths never become labels. The legacy aggregate completion/detection counters remain available. See [PROMETHEUS_GRAFANA_METRICS.md](PROMETHEUS_GRAFANA_METRICS.md) for the full public contract and compatibility details.
- Current `QUEUED`/`RUNNING` job gauges query the database every 15s; worker utilization from the ThreadPoolExecutor. A DB outage reports `clamav_scan_database_available 0` and `-1` for unavailable job counts; do not interpret -1 as a valid count.
- ClamdTop snapshots: one polling service (default 30s) samples at most 12 enabled endpoints and uses **fixed slot labels `0..11`**. Canonical gauges export queue depth, live/idle/busy/max threads, pool memory in bytes and signature database version/timestamp/age. Native `QUEUE: 0 items` is supported without inventing a queue capacity; repeated pool sections are aggregated. Unavailable snapshots have **NaN numeric values** and `available=0`, `stale=1`. Missing memory or timestamp fields remain unknown. Slot-to-endpoint mapping appears in Administration → Configuration. Filepaths, users, job IDs and endpoint names do not enter metric labels; engine signatures appear only on the dedicated detection counter.
- Config observation and synchronization: explicitly mounted `clamd.conf` / `freshclam.conf`, comments and quoting support, repeated options and bounded includes, secret redaction. An explicit clamd path enables synchronization of the endpoint named `default` and its scan policy; set `CLAMAV_CONFIG_DISCOVERY_ENABLED=false` for inspection only. File updates do **not** reload ClamD or change allowed roots. The configuration page shows sampled VERSION/STATS beside desired file values and the effective client policy. See [DAEMON_CONFIGURATION_SYNC.md](DAEMON_CONFIGURATION_SYNC.md).

## Secure metrics (must configure before scraping)

**Metrics access is denied unless a bearer token of at least 24 characters is configured.** The `/actuator/prometheus` gate is separate from browser sessions and normal API tokens. Other actuator paths require Admin.

For **Docker Compose** in the ClamAV service, merge these settings into your **existing** environment and ports; do not replace the PostgreSQL/LDAP/NGINX configuration:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_METRICS_SCRAPE_TOKEN: "REPLACE_WITH_A_RANDOM_32+_CHARACTER_SECRET"
      MANAGEMENT_SERVER_PORT: "9091"
      MANAGEMENT_SERVER_ADDRESS: "0.0.0.0"
    ports:
      - "8081:8080"
      - "127.0.0.1:9091:9091" # host-loopback only; a containerized Prometheus needs a trusted internal network instead
```

For a containerized Prometheus attached to the same isolated Compose network, **do not publish management port 9091 on the host**; scrape `clamav-web-client:9091` from that network instead, with firewall/segment controls. Update `dashboards/prometheus.yml.example` target accordingly. Store the token as an injected secret, not in Git or a broadly readable Compose file. Set TLS between Prometheus and the listener if it travels outside an isolated trusted network.

Generate a token on the host with `openssl rand -hex 32` and ensure its content matches the contents of `/etc/prometheus/secrets/clamav_metrics_token` used by your Prometheus instance. Restrict permissions.

Verify that unauthenticated requests return HTTP 403, and a valid token returns Prometheus text:

```bash
curl -i http://127.0.0.1:9091/actuator/prometheus
curl -i -H "Authorization: Bearer YOUR_TOKEN" http://127.0.0.1:9091/actuator/prometheus
```

`management.server.port` creates a separate management context. **Validate the 403/200 behavior after deployment; this implementation has not been live-tested on your JVM.** Never expose management 9091 on a public address without its token gate and trusted network restriction.

## Optional config mounts

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_CONFIG_PATH: "/app/config-observer/clamd.conf"
      FRESHCLAM_CONFIG_PATH: "/app/config-observer/freshclam.conf"
      CLAMAV_CONFIG_DISCOVERY_ENABLED: "true" # false = inspection only
    volumes:
      - ./clamd-config:/app/config-observer:ro
```

Bind mount the directory containing config includes. Parser refuses include paths resolving outside that directory (and limits total include files), so it cannot recursively read unrelated host locations. It reports unreadable files and does not expose secret-like directives. **The `CLAMAV_CONFIG_PATH` and `FRESHCLAM_CONFIG_PATH` must be explicit:** no mount means disabled.

Connection precedence: explicit `CLAMAV_HOST`/`CLAMAV_PORT` prevents file-driven connection changes; otherwise mounted configuration creates or updates the endpoint named `default`, including an existing record. Other named endpoints and the default endpoint's enabled/disabled state are preserved. `LocalSocket` takes priority over TCP: mount it at the same absolute path in the web container. Wildcard TCP bind addresses map to loopback, appropriate to a shared host or pod network. For separate container networks, use an explicit reachable `CLAMAV_HOST` override. The mounted daemon policy still restricts the default endpoint.

The poll defaults to 30 seconds (`CLAMAV_CONFIG_POLL_MS`); included files are re-read too. Invalid or incomplete updates retain the last valid configuration in memory and show a warning. If no valid configuration has been observed, scans through the managed default endpoint are paused. Application upload limits remain ceilings; daemon StreamMaxLength/MaxScanSize/MaxFileSize can lower them. ExcludePath applies to path/watch targets, stored upload paths and recursive child files. No settings file or database schema change is required.

For remote ClamD configurations, keep config mounts disabled if there is no shared config; metrics still work against the configured ClamD TCP endpoint.

## Grafana, scraping, alerting

- Import `dashboards/grafana-clamav-webui.json` and select your Prometheus datasource.
- Apply `dashboards/clamav-alerts.yml` to Prometheus rule files, then reload Prometheus.
- Configure scraping from `dashboards/prometheus.yml.example`; protect token-file read permissions and restrict metrics network traffic.
- Alert thresholds in the example are defaults: signatures > 7d; daemon unavailable > 3min; queue > 10 items for 5min; thread saturation >= 90% for 5min; pool memory above 150% of its one-hour average for 10min; any malware-positive scan in the last 5min. Tune these examples for your workload.
- Scan counters and duration histograms are initialized to zero before the first scan. Daemon gauges remain unknown until a successful sample. Counter values reset on service restart (Prometheus `increase` handles resets); detailed incident history remains in DB and is not replayed into counters.
- Grafana label `slot` identifies an ephemeral ordered endpoint slot; consult Admin → Configuration to map it to the current endpoint name.

## Validation matrix

| Check | Expected |
|---|---|
| Missing metrics token | 403 for `/actuator/prometheus` |
| Valid token | 200 in Prometheus format |
| Viewer browser session | No access to Admin → Configuration |
| Admin browser session | Read-only observer visible |
| No config mounts | NOT_MOUNTED, no file guessing |
| Changed config file | Updated view after next 30s poll, no daemon reload |
| Missing/invalid included file | Notice; no expansion beyond mounted root |
| One completed scan | One `clamav_scans_total` increment with lowercase labels |
| Positive scan with known signatures | One per-file/per-signature counter increment; no file path labels |
| Native `QUEUE: 0 items` | Zero queue count; no fabricated queue capacity |
| ClamD unreachable | `available=0`, `stale=1`, numeric stats unknown |
| Existing default DB ClamD endpoint | Connection updated when synchronization is enabled and no explicit host/port override exists |
| Other named ClamD endpoints | Unchanged by file synchronization |
| Invalid config update | Last valid policy retained; warning shown |
| Unix LocalSocket | Used for health, telemetry, INSTREAM, path scans and native ClamdTop |
| Scan roots | Unchanged by ExcludePath or limits in a config file |

Compilation, regression tests, Prometheus exposition and protected MVC endpoint checks are recorded in [METRICS_VALIDATION.md](METRICS_VALIDATION.md). Deployed ClamD/Prometheus/Grafana and Docker acceptance remain separate from those checks. Run the matrix above on the intended installation.
