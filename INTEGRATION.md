# ClamAV Web GUI 2.3.9-kosz.2 — REST API integration

This update connects your Java/Spring GUI to the custom `clamav-rest-api:1.6.13-kosz.1` built earlier. The API source/image does not need another change.

## What is integrated

| GUI page | Added API information or action |
| --- | --- |
| Dashboard | Lifetime scanned/infected files, failed jobs, scanned bytes, live running/queued/uploading counts, retention and storage health |
| Main | API statistics, ClamAV version and local/published signature versions, with independent connection errors |
| Scan jobs | API jobs alongside the existing direct GUI jobs; live polling, pagination, file results and webhook state |
| API reports (new tab) | Filters, async upload, job details, current file and completed-file progress, failed webhook retry, daily UTC activity, detected signatures and CSV/JSON reports |
| Scan | Link to the API upload workflow; existing upload/path scanning continues to work |
| Clamdtop | Live API queue strip above the existing native terminal |
| Audit | API upload, webhook retry and report export actions performed through the GUI are recorded with the logged-in user |

The integration monitors one configured REST API service. Existing clamd endpoints remain independently selectable. API and direct GUI counts are labelled separately, and API jobs are not copied into the direct job database.

## Configure and build locally

Extract the updated source into your existing project directory, **preserving your existing `conf/` and `data/`**. This source archive excludes runtime databases and uploaded scan payloads. Keep your existing database connection environment variables if you use PostgreSQL.

The included `docker-compose.yml` now sets your API URL directly:

```yaml
CLAMAV_REST_URL: "http://192.168.68.144:3000"
CLAMAV_REST_API_KEY: ""
CLAMAV_REST_FORM_KEY: "FILES"
```

No `.env` file is required. If the API has `APP_API_KEY` configured, set the same value in Compose under `CLAMAV_REST_API_KEY`. Edit Compose directly to change the other REST settings. The application still supports these environment variables independently of Compose.

The URL must be reachable from the GUI container. A full URL ending in `/api/v1` also works. The GUI server makes API calls and keeps the key out of browser HTML and JavaScript.

```bash
docker compose -f docker-compose.yml build --no-cache clamav-web-client
docker compose -f docker-compose.yml up -d --force-recreate --no-deps clamav-web-client
docker compose -f docker-compose.yml logs --tail=100 clamav-web-client
```

The local image tag is `kosztyk/clamav-web-client:2.3.9-kosz.2` and `pull_policy: never` makes Compose use that local image. `build_docker.sh` uses the same Compose service/tag. Recreate the container after changing environment variables.

Open `http://YOUR_GUI_HOST:8080`, sign in as an operator/admin, and choose **API reports**. The footer should show `2.3.9-kosz.2-rest-api-integration`. Dashboard/Main/Clamdtop show API panels once `CLAMAV_REST_URL` is set; API reports also provides setup guidance while disabled.

Your existing Compose clamd service and host settings were retained. If you already run clamd elsewhere, start/recreate only the GUI service with `--no-deps` as above. Do not change the daemon address to the REST HTTP port.

## Make ClamTop and API scans match

ClamTop still connects directly to clamd TCP port 3310. Configure the selected endpoint under **Endpoints** to the same host/port as `CLAMD_IP`/`CLAMD_PORT` in the REST API. Existing endpoint records are stored in the GUI database: changing `CLAMAV_HOST` alone does not change an existing record.

The API's `/stats` contains application job statistics, not daemon threads, RAM or all daemon activity. Native ClamTop supplies those live daemon metrics. API scans use `INSTREAM`, so the daemon may display a stream rather than the original upload filename; API job details show filenames and detections. A short scan may finish between ClamTop refreshes.

## Reports and retention

- Job filters: status, sync/async mode, infected/clean, exact request ID, creation-time range. Date inputs use your browser timezone; daily report buckets use UTC.
- Generate analytics and CSV/JSON exports use the **applied** filters and **terminal** jobs only. Select completed/failed or all statuses for history reports; queued/running filters produce no terminal results.
- Lifetime dashboard totals come from `/stats`. Reports use `/history` and can only describe retained jobs, governed by API `APP_HISTORY_LIMIT` and `APP_HISTORY_DAYS`.
- Reports show included and matching retained job counts. The default cap is 5,000 jobs with a bounded collection time; partial reports are explicitly labelled. Narrow the date range to collect fewer records. JSON includes coverage metadata; CSV exposes it through response headers and a GUI download message.
- CSV has one row per file, including signatures and errors; spreadsheet formula prefixes are neutralized.
- Reports are live snapshots, not a durable audit archive. Retention changes and jobs finishing during collection can change coverage. Save exports if you need longer history.
- Failed jobs may contain successful or infected file results from before the failure. Reports count those files without treating the overall job as completed/clean.
- Progress measures completed files. It is not ClamAV's internal percentage, and a failed job can remain below 100%.

## Uploads, notifications and audit

API uploads are streamed from the GUI's disk-backed multipart storage to `/asyncscan`. No full upload is loaded into a Java byte array. GUI upload/request limits and API size/file-count/queue limits both apply. Files can be scanned without sharing the GUI upload directory with clamd.

The browser's initial percentage measures transfer to the GUI. The GUI then waits for API acceptance; after a `202` response the job runs independently and appears in history even if the browser closes. If an upload times out, check API jobs before resubmitting to avoid duplicates.

The webhook selector lists server-configured IDs from `/webhooks`. Blank uses the API's default webhook if one is configured. Destination URLs and signing secrets are not exposed. The GUI can retry failed deliveries; it does not configure destinations or receive webhook callbacks. Five-second polling updates jobs; engine/signature information refreshes once per minute while visible.

GUI operators/admins can read API filenames/history, upload and retry webhooks. Viewers receive aggregate API summaries and health only. New mutation routes retain CSRF protection. The GUI audit records actions taken through this integration. The API does not supply the submitting GUI user/IP for jobs sent by external clients; their `request_id` is shown as a correlation field, not a verified identity.

## Optional environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `CLAMAV_REST_URL` | empty (disabled) | REST API origin or `/api/v1` base URL |
| `CLAMAV_REST_API_KEY` | empty | Must match API `APP_API_KEY` |
| `CLAMAV_REST_FORM_KEY` | `FILES` | Must match API `APP_FORM_KEY` |
| `CLAMAV_REST_TIMEOUT_SECONDS` | `10` | Connect/read request timeout for monitoring |
| `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` | `3600` | API upload/acceptance request timeout |
| `CLAMAV_REST_REPORT_MAX_JOBS` | `5000` | Report cap, clamped to 100–10,000 |

These settings live in the server environment, not the legacy GUI Settings form. The API key is never persisted to the properties file or included in UI responses.

## Build/test from source

Java 17+ and Maven 3.9+:

```bash
mvn test
mvn package
```

The Dockerfile continues to build with Java 21 and preserves the native clamdtop binary/PTY setup. See `VALIDATION.md` for the checks actually run on this update.
