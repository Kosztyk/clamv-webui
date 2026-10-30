
# Phase 0 REST UI build-fix (2026-10-28)

## What changed
- Added the current `RestMonitorIntegrationTest.java` to the **full source ZIP**. This intentionally replaces an old integration test retained in development directories from the earlier *single combined page* UI. The new tests exercise **separate** `/api-scan` and `/api-reports` pages, and verify auth, CSRF, mocked upstream stats, and webhook retry.
- Restored strict validation for REST-monitor query filters before contacting the configured REST service (unknown filters, invalid dates, pagination). `format` remains local to CSV/JSON export.
- No REST API URL, scanning method, ClamdTop flow, UI layout, database schema, or Compose environment setting was changed.

## Root cause of the last four test failures
The old `RestMonitorIntegrationTest` was still present in the test-server source tree. It tested a combined `/api-reports` page that held an upload form and expected a configured mock upstream API. The new, intentionally split UI moved the upload form to `/api-scan`, while the old test's backend stub was no longer connected. The new test mocks the *specific external REST bridge* so that no LAN service or secret is used during `mvn verify`.

## Clean build steps (your Docker host)
Back up `./data` and `./conf`. Extract the full archive to a **fresh directory** so stale files from old versions cannot persist. Copy your own `conf/` and `data/` only when testing with existing data; no credentials or live data are bundled.

```bash
unzip clamv-webui-phase0-rest-buildfix-source.zip -d /tmp/clamv-rest-test
cd /tmp/clamv-rest-test/clamv-webui-phase0-rest-buildfix

docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
```

If you deliberately overlay on the old source tree, check that `src/test/java/info/trizub/clamav/webclient/web/RestMonitorIntegrationTest.java` was overwritten. Never skip Maven tests to force a successful image.

## Verification limit
This archive has been inspected and ZIP-integrity checked, but Maven and Docker are not available in the archive creation environment. The build and live REST/EICAR/ClamdTop functional tests must run in your Docker host; do not treat them as passed yet.
