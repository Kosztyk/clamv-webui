# Issue #32 — Bounded and cached external REST monitoring

Status: implemented in the local **v2.1.0beta2** candidate. This documentation describes the code changes and limits; it does not claim that a production stress test has passed.

## Problem

`RestMonitorController.fetchedJobs` previously requested full upstream `jobs` or `history` on **every** page, analytics and export call. The bridge decoded the entire response with an unlimited `BodyHandlers.ofString`. With several browser viewers, an untrusted or malfunctioning upstream could create excessive network, parsing and memory load.

## Changes

1. **Bounded upstream response reader:** `RestApiBridge.execute` uses `BodyHandlers.ofInputStream`, checks a declared `Content-Length`, then reads at most **10 MiB + 1 byte** for requests without trustworthy length (including chunked responses). It immediately closes the stream and returns **HTTP 502** if the cap is exceeded; all REST bridge operations, including uploads and job details, use this limit. JSON decoding only happens after the response passes the cap.
2. **Stable upstream errors:** status 404 is preserved for compatibility with history-to-jobs fallback; other upstream HTTP failures return a generic **502** JSON (`error`, `message`) without passing through upstream body contents. A controller exception handler uses the same stable JSON fields for local 400/502 errors. No raw upstream stack trace or error JSON is returned to the browser.
3. **Shared history cache:** `RestJobHistoryCache` caches each of `history` and `jobs` for five seconds, shared across authenticated users and page/filter/analytics/export requests. Each key has exactly one synchronized refresh at a time; on transient gateway exceptions a one-second failure cache prevents connection storms. Cache entries are invalidated on successful REST upload submission and webhook retry. This is a **bounded, in-process** cache for the two history endpoints, not durable storage. Other API endpoints continue to request current data and are protected by the response cap.
4. **Reduced browser polling:** hidden tabs stop scheduled summary/jobs REST requests, and resume on visibility. Visible pages with active jobs refresh job lists every 5 seconds; idle views refresh every 30 seconds. Summary polls move from 10 seconds active to 30 seconds idle. Explicit user actions still refresh immediately.
5. **Configuration Center:** Connections → External REST API → Advanced → **External REST history cache TTL (ms)**, default **5000**, range **1000–30000**, requires application restart. `CLAMAV_REST_HISTORY_CACHE_TTL_MS` in Docker Compose overrides and locks this field. The upstream limit is deliberately a fixed safety ceiling of 10 MiB.

## Limitations and compatibility

- Upstream pagination conventions differ; the existing controller continues **local filtering/pagination** on a cached list rather than sending unknown pagination parameters to incompatible upstream APIs. Repeated navigation within the TTL avoids redundant list downloads. If an upstream has a history payload larger than 10 MiB, the report endpoint now fails safely with 502; implement upstream server-side paging before increasing such a cap.
- `CLAMAV_REST_REPORT_MAX_JOBS` still bounds aggregated records and export output, but it does not limit the number of upstream records parsed within the 10 MiB safety ceiling.
- Cache snapshots are **process-local**; multiple independently deployed Web UI replicas each maintain their own cache. Cache does not alter managed `/jobs` queries, native `clamdtop`, or scanning jobs. Role checks and audit logging remain in place.
- Saved Configuration Center values are unchanged. The existing REST API endpoint URL, API key and field mappings remain unchanged.

## Tests

- `RestJobHistoryCacheTest` verifies 20 concurrent viewers use one refresh, TTL expiry, invalidation and unsupported-key rejection.
- `RestApiBridgeTest` includes oversized `Content-Length` and chunked responses (HTTP 502), and upstream error sanitization.
- `RestMonitorIntegrationTest` verifies two report pages, analytics and CSV export share one upstream snapshot.
- `node --check src/main/resources/static/js/rest-api.js` checks frontend syntax. A live multi-user browser/ClamAV upstream benchmark is still recommended.

Run `mvn -B -Dtest=RestJobHistoryCacheTest,RestApiBridgeTest,RestMonitorIntegrationTest test` and `mvn -B verify` before deployment.
