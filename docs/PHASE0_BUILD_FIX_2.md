# Phase 0 Build Fix 2 (October 28, 2026)

## Findings from Docker build output
- `mvn verify`: 30 tests, 2 failures, 0 test errors.
- REST monitor `GET /rest-monitor/jobs` is now correctly denied to `ROLE_VIEWER` (403).
- `GET /api-reports` still returns HTTP 200 to `ROLE_VIEWER`, although the integration test requires 403. This is a real authorization issue.
- Dashboard Thymeleaf shell lacks `rest-api.js`; the REST monitor test expects the JS hook on rendered pages.
- The old application's startup logs are from a previously created image, not proof that the failed Docker build launched the new release.

## What is fixed in this complete baseline ZIP
- `SecurityConfig.java` denies `/api-reports` and subpaths for `ROLE_VIEWER`; OPERATOR/ADMIN can access them when the route exists.
- `UiSmokeTest` asserts the denial with a regression check.
- Existing `/rest-monitor/summary` read-only and `/rest-monitor/**` permission rules remain intact.
- The application is still built with `mvn verify` and inline Docker Compose settings; no external `.env` dependency.

## IMPORTANT: 2.3.9 source compatibility
The available full base archive is v2.3.6-derived, NOT your full 2.3.9 source. It does NOT contain your later `RestMonitorController.java`, `rest-api.js`, `api-reports.html` or native ClamdTop module. Do not replace the actual 2.3.9 source with this base archive if you need those features.

This archive includes `tools/reconcile_current_source.py`, which applies both remaining fixes to a COPY of your existing local source checkout and emits a NEW FULL SOURCE ZIP, retaining all newer modules. It rejects a REST source missing its JavaScript implementation; it will never manufacture a blank placeholder.

Run from your existing test checkout:

```bash
python3 /path/to/extracted/full-archive/tools/reconcile_current_source.py \
  --source /path/to/clamav \
  --output /tmp/clamav-webui-phase0-current-full.zip

mkdir -p /tmp/clamav-phase0-final
unzip /tmp/clamav-webui-phase0-current-full.zip -d /tmp/clamav-phase0-final
cd /tmp/clamav-phase0-final/clamav-webui-phase0-merged
docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
```

If tests still fail, upload the CURRENT full source archive (not just build logs) so integration-specific code can be inspected. Docker/Maven integration tests have not been run in the packaging environment.
