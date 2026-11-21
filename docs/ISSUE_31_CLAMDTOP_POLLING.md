# Issue #31 — Shared ClamD metrics snapshot and visibility-aware UI polling

## Scope

`ClamdTopService.snapshot()` is now the shared snapshot cache for `/api/clamdtop`,
`/api/v1/metrics/clamd`, and the scheduled `ClamdMetricsCollector`.
The two HTTP endpoints **retain their existing authentication, API-token endpoint scope and output redaction rules**;
the raw daemon STATS output is never added to the v1 metrics response.
The `checkedAt` field reflects the actual cached observation timestamp, not the HTTP response time.

- Cache key includes the selected endpoint ID, connection address, platform, port, name, and enabled state. Changing
  endpoint configuration therefore invalidates the old key without forcing a restart.
- Cache TTL is **2 seconds** by default, bounded to 1–3 seconds via
  `CLAMAV_CLAMDTOP_CACHE_TTL_MS` (`clamav.metrics.clamdtop-cache-ttl-ms`). The property is read at startup.
  The same setting is now in **Administration → Configuration Center → Monitoring → Prometheus & Grafana** as
  **ClamdTop snapshot cache TTL (ms)**. Values are validated from 1000 to 3000 milliseconds. The field is marked
  **restart required** because the cache interval is captured when `ClamdTopService` is constructed. A Docker
  environment variable with the same setting takes precedence and locks the GUI field.
- Per-endpoint single-flight locking prevents concurrent callers from opening redundant ClamD connections. For a
  stale entry, other callers may briefly receive the previous result while the refresh is in progress.
- Failures are also cached for the TTL, limiting load during daemon outages. The existing bounded probe deadline
  still applies to the VERSION + STATS pair.
- PING was removed from this service only. A successful STATS response proves the daemon is reachable; VERSION is
  still requested for signature database metadata. The explicit health-check endpoint is unchanged.
- Cache holds at most 128 endpoint/configuration snapshots in process memory.
- The legacy DOM `clamdtop.js` poller no longer requests data while its tab is hidden, refreshes when visible,
  and avoids overlapping requests.
- The genuine interactive `clamdtop` PTY WebSocket is **not** converted to cached STATS because users need
  its live ncurses display, keystrokes, and active filename visibility.

## Acceptance and verification

`Issue31ClamdSnapshotCacheTest` covers concurrent cold readers, TTL refresh, failure caching, and endpoint changes.
Verify the actual HTML client with multiple simultaneous tabs; within a TTL, one endpoint should generate no more
than a single VERSION+STATS pair, regardless of viewer count. The 30-second scheduled Prometheus collector reuses
this cache. With `CLAMAV_CLAMDTOP_CACHE_TTL_MS=2000`, repeated refreshes should be limited to two ClamD commands
per active endpoint per cache refresh, not three per viewer.

Run `mvn -B -Dtest=ClamdTopServiceTest,Issue31ClamdSnapshotCacheTest,ClamdMetricsCollectorTest test`
and `mvn -B verify` before production. There is no DB migration or Docker Compose change.

## Rollback and configuration

To slow polling further, adjust client/browser polling independently; decreasing cache TTL increases
background ClamD load. Do not disable API authorization to share the cache: sharing occurs only within the server.
Use Configuration Center for per-installation adjustment without changing Compose. Leave
`CLAMAV_CLAMDTOP_CACHE_TTL_MS` unset to make the field editable. If an environment override is needed,
set it in Compose and recreate the container; the GUI will display it as deployment-controlled.
Saving in Configuration Center preserves the current active TTL until the next application restart.
No database migration is necessary.
Existing issue #25–#30 fixes and private upload filesystem permissions are unchanged.
