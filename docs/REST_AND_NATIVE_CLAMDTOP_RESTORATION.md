# Restoring API Scan, API Reports and *native* ClamdTop in Phase 0 UI

## Objective and provenance

The working application shown in the October 2026 screenshots used the external ClamAV REST API and an **actual interactive ncurses `clamdtop`** console. The Phase 0 reconstruction mistakenly replaced those with a non-following upload form, mismatched REST JSON adapters, and an STATS-only monitor. This build retains the Phase 0 layout and corrects these concrete regressions.

**Provenance limitation:** This ZIP is based on the 2.3.6-derived Phase 0 source plus the reconstructed external REST client, not the exact 2.3.9-kosz.2 original source. Exact feature parity, API schema compatibility and live native terminal behavior cannot be certified without that source and a live build against the existing deployment. Do not deploy to production before acceptance checks pass.

## Changes

1. `/api-scan`: after `POST /rest-monitor/upload`, normalize `{"success":true,"data":{"job_id":"...","status":"queued"}}`, display job ID, poll `/rest-monitor/jobs/{id}`, and render the resulting file-level verdict/detections whenever present. Keep the reports link and refresh the live list.
2. `/api-reports`: normalize nested `success/data/jobs` and `success/data/history` responses, paginate/filter returned records, display job detail, progress, verdict and link to file-level results. Return an explicit API-format error instead of silently displaying an empty list on unknown schemas. Dashboard and reports stats recursively map common counter fields, including zero values.
3. `/rest-monitor/history`: if the server does not implement `/api/v1/history` (404), fall back to `/api/v1/jobs` and select terminal-status jobs. If the server returns a valid but empty history, **do not invent jobs**.
4. `/clamdtop`: real `/usr/bin/clamdtop` process attached to a pseudo-terminal (`/usr/bin/script`), streamed through a same-origin authenticated WebSocket and rendered with bundled xterm.js. Supports endpoint selection, Connect/Disconnect, input keys, ANSI color, and browser resize reconnect. The earlier STATS API still exists at `/api/clamdtop` for programmatic use.
5. Build adds `clamav-daemon`, `util-linux`, `ncurses-term` to **the Web UI runtime image only**. No extra ClamAV daemon is started in that image; `clamdtop` connects to the selected configured ClamD endpoint.

### Known differences from the original

- The native monitor is recreated using the actual executable and terminal protocol, not recovered from the original 2.3.9 WebSocket implementation. Some key binding, layout, and resize behavior may differ.
- The reports adapter supports common JSON wrappers. Exact report fields/scan history semantics remain dependent on the external REST API version.
- Job analytics and CSV/JSON export are derived from the limited list returned by the configured REST API, not from a separate analytics database. Their completeness depends on the API's list and pagination semantics.
- The bundled terminal renderer is a legacy MIT-licensed xterm.js build. Do not remove the accompanying license.

## Local-build deployment (test host)

**Preserve original working 2.3.9 source and image separately.** Copy existing `./data` and `./conf` safely before switching versions, with the service stopped when making an H2 DB snapshot. Do not overwrite or delete the `.mv.db` database or change authentication secrets.

Extract this complete ZIP into an empty directory, enter the folder that contains `Dockerfile`, `pom.xml` and `docker-compose.yml`, then:

```sh
docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
docker compose logs -f clamav-web-client
```

The Web UI base image and `clamav-daemon` apt packages can still download during image build; **only the application image is locally built, rather than pulled from Docker Hub**.

## Acceptance checks (must be completed on your test host)

- `/api-scan`: upload an EICAR test file through the external REST API; get an accepted job ID, queued/running status, then completed/detected verdict and per-file detection name. Verify the same ID shows in `/api-reports`.
- `/api-reports`: compare scanned/infected/bytes counters and the exact retained job ID against the original REST API. All jobs, finished history, filters, detailed job results and downloads should display.
- `/clamdtop`: open native console and confirm ClamD host/port, native ncurses screen, memory/queue, keyboard `h`, `q`, `r`, and active filenames from a shared-volume path scan. **INSTREAM scans cannot expose filenames to clamdtop.**
- `/dashboard`, `/scan`, `/jobs`, `/admin/settings`, `/admin/endpoints`, `/admin/users`, `/admin/watch`, `/admin/audit`: verify no regression, correct user access and persistent data.
- If a reverse proxy is used, make sure `/ws/clamdtop` passes `Upgrade: websocket` and forwards cookie/session headers over HTTPS/WSS.

REST API is configured inline in Compose: `CLAMAV_REST_URL`, `CLAMAV_REST_FORM_KEY=FILES`, and optionally `CLAMAV_REST_API_KEY`. The **ClamdTop endpoint comes from the saved endpoints database**, so `CLAMAV_HOST` in Compose may not override an existing default endpoint. Use the endpoint selector, or Admin > Endpoints, to select the correct ClamD daemon.

If the REST service returns different paths, configure `CLAMAV_REST_STATS_PATH`, `CLAMAV_REST_JOBS_PATH`, `CLAMAV_REST_HISTORY_PATH`, `CLAMAV_REST_SCAN_PATH`, and related variables as described in REST_UI_REINTEGRATION.md. Never provide a browser-supplied upstream URL.

## Validation performed in preparation environment

- Parsed all Java main/test source files with the JDK compiler parser: syntax check only, no type linking.
- `node --check` on the updated REST and native terminal scripts.
- Loaded bundled xterm.js in Node and checked terminal constructor + writer/keyboard APIs.
- Verified XML and YAML parse, and the complete ZIP integrity.

**Not performed here:** full Maven test suite, Docker build, authenticated WebSocket end-to-end test, live ClamD 1.4.x terminal, or REST API 1.6.x schema/e2e tests. Do not treat these as passed.
