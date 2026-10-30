# Phase 1 build fix 01 — Oct 28, 2026

## Source

Full Phase 1 source based on `clamv-webui-phase1-stable-secure-api-full-source.zip`.

## Reported build failure

The supplied Docker Maven log reported **53 tests, 0 failures, 7 errors**. Six errors were caused by an application context initialization failure in `ApiV1SecurityIntegrationTest` after `ApiV1ContractIntegrationTest` had created `data/bootstrap-admin-password` using a different in-memory H2 database. The first cause was `java.nio.file.FileAlreadyExistsException: data/bootstrap-admin-password`. The seventh error was a null mock `HttpServletRequest.getRequestURI()` in `AuditServiceTest`.

## Fixes

- Added explicit, long test-only bootstrap passwords to both new Phase 1 Spring Boot integration test classes. This matches the existing Phase 0 integration tests and avoids writing one-time credentials to a shared filesystem path during Maven verification.
- Made audit transport classification null-safe in both `record` and `recordScan`. A token principal is still classified as `API_TOKEN`, a `/api/` URI as `API_BASIC_OR_SESSION`, and an absent/non-API URI as `BROWSER`.
- Added regression assertions for the mock's null request URI, API transport and endpoint audit metadata.
- Production first-run bootstrap password generation and file safeguards remain unchanged. No API routes, authorization policy, UI, ClamdTop, REST adapter, or Compose settings were removed.

## Verification

- Inspected first causes from the uploaded Maven log.
- Verified source edits and archive integrity locally.
- Maven and Docker verification are not available in this execution environment. Run `docker compose build --no-cache clamav-web-client` on the test host and retain the Surefire report if another failure appears.
