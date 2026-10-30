# Phase 0 UI + external REST reintegration

## Provenance and compatibility

This full archive merges the Phase 0 visual shell with **newly reconstructed** external REST API pages.
The source of the previously deployed `2.3.9-kosz.2` REST module was **not** present in the supplied archive or public GitHub `main`. These are functional replacements, **not a verbatim restoration** of that module. Exact upstream endpoint compatibility, multi-file upload semantics and reporting schemas must be tested against your REST server before promotion to production.

### Preserved, rebuilt or pending

| Feature | State |
|---|---|
| Phase 0 dashboard/sidebar/theme | Retained |
| Direct GUI uploads, path scans, jobs, watch, endpoint/users/settings/audit | Retained |
| Phase 0 live STATS-based ClamdTop and native path scans | Retained |
| External API Scan form, multi-file upload proxy and webhook selection | Reconstructed; requires upstream verification |
| External API Reports, jobs/history pagination, filtering, job detail | Reconstructed; requires upstream verification |
| External API analytics and CSV/JSON export | Reconstructed from API history response; upstream pagination limits apply |
| Original native terminal emulator (`native-clamdtop.js`) | **NOT restored**: original module unavailable |
| Original 2.3.9 Java controller/JS contract | **NOT restored verbatim**: unavailable |
| Original v2.3.9 localized UI text | Not restored; base UI English (original message resources retained) |

### Architectural difference from deployed 2.3.9

Because its source is missing, the REST monitor was reconstructed as a configurable, *allowlisted HTTP adapter*, secured using existing Spring Security roles and CSRF protection. It does not proxy arbitrary browser-selected URLs; `CLAMAV_REST_API_KEY` stays server-side. This differs from any implementation details in the unavailable module. This decision reduces secret disclosure, SSRF, and viewer access to scan history; it may require adapting the REST endpoint mappings to the exact upstream service.

## Configuration (inline in Docker Compose; no .env dependency)

Existing values preserved:

- `CLAMAV_REST_URL` (for example `http://192.168.68.144:3000`)
- `CLAMAV_REST_API_KEY` (blank if the API does not require auth)
- `CLAMAV_REST_FORM_KEY` (default `FILES`)
- `CLAMAV_REST_TIMEOUT_SECONDS` (default `10`)
- `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` (default `3600`)
- `CLAMAV_REST_REPORT_MAX_JOBS` (default `5000`)

Optional route overrides, necessary if the REST API has different paths:

- `CLAMAV_REST_STATS_PATH` default `/api/v1/stats`
- `CLAMAV_REST_JOBS_PATH` default `/api/v1/jobs`
- `CLAMAV_REST_HISTORY_PATH` default `/api/v1/history`
- `CLAMAV_REST_WEBHOOKS_PATH` default `/api/v1/webhooks`
- `CLAMAV_REST_SCAN_PATH` default `/api/v1/asyncscan`
- `CLAMAV_REST_SCAN_HISTORY_PATH` default `/api/v1/scan-history`
- `CLAMAV_REST_WEBHOOK_FIELD` default `webhookId`
- `CLAMAV_REST_AUTH_MODE` `x-api-key` (default) or `bearer`

Routes under `/rest-monitor/` are authenticated browser-to-WebUI requests. All upstream calls originate from the Web UI container. `GET /rest-monitor/summary` allows VIEWER, OPERATOR and ADMIN; the job/history/detail, upload, export and analytics routes require OPERATOR/ADMIN. `/api-scan` and `/api-reports` are OPERATOR/ADMIN pages.

Upstream jobs/history responses may be an array or `{jobs:[...]}`, `{history:[...]}`, `{items:[...]}`, `{results:[...]}` or `{data:[...]}`. Returned timestamps, status and modes are passed through from the upstream service. Reports are limited to the *returned page*; metrics and CSV/JSON exports must not be interpreted as complete lifetime totals without verifying pagination on the upstream REST service. Do not claim that ClamAV signatures replace a sandbox or reputation API.

## Verification

```sh
docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
docker compose logs --tail=100 clamav-web-client
```

Then, as an OPERATOR or ADMIN, open `http://HOST:8080/api-scan` and `http://HOST:8080/api-reports`, use the test API server to validate the configured URL paths, submit a benign test and EICAR file, and verify webhooks and report history. Test VIEWER access to those two pages and `/rest-monitor/jobs` returns 403.

The included test suite covers UI rendering, authorization and the adapter's basic HTTP functionality. An actual upstream integration run is still required.

The external REST 1.6.13-kosz.1 implementation includes `GET /api/v1/jobs/{id}/progress`, job webhook status and a POST webhook retry endpoint. These are forwarded as protected `/rest-monitor/jobs/{id}/progress`, `/rest-monitor/jobs/{id}/webhook`, and `/rest-monitor/jobs/{id}/webhook/retry`. REST POSTs use Spring CSRF protection; no passwords or API keys are sent to browser JavaScript.
