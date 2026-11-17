# Issue #35 — bounded Prometheus labels and canonical completion metrics

## Implementation

- `clamav_scans_total{type,verdict}` is now the **only** scan-completion counter. The duplicate `clamav_scan_completed_total` metric (uppercase labels) is intentionally no longer emitted. Update custom PromQL alerts and dashboards. `clamav_scan_detections_total` remains a distinct metric counting malware-positive **jobs**, not an additional scan-completion counter.
- `clamav_viruses_detected_total{signature=...}` registers **at most N exact signature names for the entire process lifetime**; default N=100, supported N=1–500. `signature="other"` accumulates new signatures after saturation and names exceeding 160 characters. `signature="unknown"` is reserved for positive jobs lacking readable signature metadata. `other` and `unknown` are pre-registered and count separately from the N exact names. Maximum signature-label series: **N+2** (102 by default).
- The admission map is synchronized for correctness across concurrent scan jobs. Admission is sticky: evicting meters would cause unlimited churn in Prometheus even when the in-process cache size stayed bounded. The first N encountered names are retained until the process restarts. This is **not** a frequency-ranked top-N implementation. The stored scan results and the authenticated API still include the original exact signatures.
- `clamav.scan.duration` histogram's expected upper bound is now **24 hours**, matching the maximum configured scan timeout, with 1h/6h/12h/24h SLOs. Additional histogram buckets marginally increase its fixed (type-only) series count.
- All static job counters/timers, `clamav.scan.detections`, failure reason counters and reserved signature buckets are registered once in the constructor rather than rebuilding MeterBuilders on every completion.

## Configuration Center

**Monitoring → Prometheus & Grafana → Advanced**: **Distinct signature metric labels** (`clamav.metrics.signatures.max-distinct`), default `100`, valid `1–500`, **restart required**. An explicit Docker Compose `CLAMAV_METRICS_SIGNATURES_MAX_DISTINCT` override takes precedence and locks the GUI field.

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_METRICS_SIGNATURES_MAX_DISTINCT: "100"  # optional; omit to use Configuration Center
```

## Compatibility

The metric `clamav_scan_completed_total` is removed deliberately. Update external dashboards/alerts to query **`clamav_scans_total`**, using lowercase labels (`upload|path|watch`, `ok|virus_found|error`). The shipped Grafana dashboard and alert rules already use the canonical counter. `clamav_scan_detections_total` remains for users tracking malware-positive jobs. Full per-file signature detail is **unchanged** in scan jobs and the API.

## Validation

Included `Issue35MetricCardinalityTest` feeds **10,000 distinct signatures** and verifies at most N+2 signature meters and correct overflow counts. It also checks concurrent admission, removal of duplicate completion meters, and the extended duration histogram range. Existing `ScanMetricsTest` and `PrometheusMetricsContractTest` still verify the public metric contract and private-path exclusion. Run `mvn -B -Dtest=ScanMetricsTest,Issue35MetricCardinalityTest,PrometheusMetricsContractTest test && mvn -B verify` in an environment with the build dependencies installed. Full Maven/Prometheus tests were not executed in this minimal environment.
