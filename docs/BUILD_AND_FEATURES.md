# Complete-source build and feature reconciliation

This is a **full source tree**. The Phase 0 sidebar/theme are retained. Direct ClamD scan, jobs, audit, settings, endpoint administration, watched directories, Quarantine, notification, and live STATS-based ClamdTop backends remain present from the Phase 0 source. API Scan and API Reports have been reintroduced as a newly written optional server-side REST bridge.

## Important provenance

The **original 2.3.9-kosz.2 GUI source** was not available when producing this archive. The public GitHub branch and supplied source ZIP are older. The REST 1.6.13-kosz.1 service routes were recovered from prior project work, but the original GUI controller, native terminal integration and JavaScript were not. Thus this is not a byte-for-byte restoration, and feature/API parity has to be tested on the actual REST service. This is an architectural deviation that should not be silently treated as completed functionality.

## New / returned UI features

- API Scan tab: multipart `FILES` upload (multiple files), webhook selection, async submission, API service status and latest external jobs
- API Reports tab: external queue/totals, external jobs and history, status/mode/detection/date/request ID filters, pagination, detail inspection, progress and webhook status/retry, analytics, CSV/JSON export
- Dashboard: separated external REST API statistics and links for API Scan/Reports
- Role enforcement: VIEWER may see summary counters but cannot inspect REST jobs/history, upload, export, or retry webhooks
- Audit: REST uploads and webhook retries recorded in local audit history
- UI: Phase 0 sidebar remains; REST assets rebind after PJAX navigation
- Docker: build locally; inline Compose environment, no `.env` dependency

## External REST service (tested routes to verify)

By default these are expected to match the REST 1.6.13-kosz.1 interface:

- `POST /api/v1/asyncscan`
- `GET /api/v1/stats`, `/api/v1/jobs`, `/api/v1/jobs/{id}`, `/api/v1/jobs/{id}/progress`
- `GET /api/v1/history`, `/api/v1/scan-history`, `/api/v1/webhooks`, `/api/v1/jobs/{id}/webhook`
- `POST /api/v1/jobs/{id}/webhook/retry`

Paths are configurable through the optional environment variables in `docs/REST_UI_REINTEGRATION.md` (not used by default). The form key is `FILES` by default.

## Local build

```bash
docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
docker compose logs --tail=100 clamav-web-client
```

**Back up existing `./data/` and `./conf/` before upgrade**; the archive contains application source and sample settings but not your database or API key. Do not copy a sample `conf` file over customized local settings.

## Verification notes

- JavaScript syntax: checked using Node 22
- REST Java classes: statically compiled against minimal framework type stubs; this **is not Maven verification**
- HTML templates: parsed (17 templates)
- Docker Compose: YAML syntax and local-build mapping checked
- Maven, Docker engine, live Clamd scan, upstream API and native terminal: **not run in this environment**

Do not declare the complete Phase 0 acceptance criteria passed until `mvn verify`, application boot, native EICAR and external REST upload/history checks pass on your development machine.
