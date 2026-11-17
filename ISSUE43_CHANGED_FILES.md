# Issue #43 changed files

- `src/main/java/info/trizub/clamav/webclient/api/ApiEndpointRules.java` — shared API endpoint resolution/disabled checks.
- `src/main/java/info/trizub/clamav/webclient/api/ApiController.java` — enforce guards and safe auditing for legacy scans; 404 for missing ClamdTop endpoints; deprecation marker.
- `src/main/java/info/trizub/clamav/webclient/api/ApiExceptionHandler.java` — structured error mapping for malformed, missing, unauthorized and unexpected requests.
- `src/main/java/info/trizub/clamav/webclient/api/v1/ApiV1Controller.java` — reuse the same endpoint resolution and enabled guard.
- `src/test/java/info/trizub/clamav/webclient/api/Issue43LegacyApiIntegrationTest.java` — MVC regression tests for HTTP 404/400 JSON and no queue creation.
- `scripts/check-issue43-legacy-api.py` — offline contract checks.
- `.github/workflows/issue43-legacy-api.yml` — Maven and offline tests.
- `README.md`, `docs/COMPLETE_USER_GUIDE.md`, `src/main/resources/openapi/clamav-v1.yaml`, `docs/ISSUE43_LEGACY_API_PARITY.md` — deprecation, sunset and migration documentation.
- `ISSUE43_VALIDATION_RESULTS.md` — measured validation status and caveats.

No database migrations or new Configuration Center settings; previous issue #36–#42 changes remain intact.
