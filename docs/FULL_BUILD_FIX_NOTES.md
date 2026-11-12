# Phase 0 full source — build-fix snapshot

This is a **complete archive** of the available Phase 0 source, not a patch. It is based on the uploaded **2.3.6** application source, and includes the local-build `docker-compose.yml` previously requested.

## Included corrections

- `UiSmokeTest`: browser-style anonymous dashboard request now sends `Accept: text/html`; Spring Security HTTP Basic is retained for non-browser clients.
- `SecurityConfig`: `/rest-monitor/summary` accepts VIEWER/OPERATOR/ADMIN, while other `/rest-monitor/**` routes require OPERATOR/ADMIN. Rules precede `/api/**` and `.anyRequest().authenticated()`.
- `docker-compose.yml`: uses the user's inline environment, local image tag and local build. No `.env` is required.

## Important source version limitation

The 2026-10-28 failing build log includes `RestMonitorController`, `RestMonitorIntegrationTest` and `rest-api.js` from a **newer merged source version**. None were supplied in the uploaded 2.3.6/Phase 0 full-source snapshots. They therefore **cannot be incorporated faithfully into this archive**. No dummy JS, controller or test was invented. The REST monitor routes remain reserved by security matchers, but this archive alone does not implement that monitor.

The `CLAMAV_REST_*` variables in the preserved Compose file are for that newer integration and will not enable it in this source. **Do not replace a working 2.3.9-kosz.2 tree with this archive** if you need those features. Provide the current full source to create a merged, complete, functional build of that version.

## Build commands

```
docker compose config --quiet
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
```

This package has been statically inspected; Maven/Docker/EICAR acceptance tests were **not run** in this environment.

## Architecture compatibility

The extra security matchers are a compatible reservation of authorization policy for the future REST monitor, not a replacement implementation. Other Phase 0 sources are left unchanged.
