# Issue #43 — legacy API parity and deprecation

## Behavior

The legacy `/api/**` scan and monitoring routes now use the same endpoint-resolution
and scan-enabled guard as `/api/v1`. An explicit endpoint ID that does not exist returns
HTTP **404** (JSON `error: not_found`); scan attempts against a disabled endpoint
return HTTP **400** (JSON `error: invalid_request`); missing or malformed input returns
HTTP **400**; oversized multipart requests return HTTP **413**. An unavailable default
endpoint returns HTTP **503**. Unknown job IDs return JSON 404. Error details are not
reflected to clients. Successful legacy scan submission payloads remain unchanged.

The read-only `/api/clamdtop` endpoint validates the requested endpoint ID before
querying the daemon; connectivity failures are still reported using the existing
sanitized, compatible response (`ok: false`). VIEWER output remains the safe
numeric projection introduced in issue #39.

`AuditService` is best effort and records stable outcomes: `QUEUED` after successful
submission and `FAILED` for errors, without storing exception class names or raw paths.
Existing service-side enabled checks remain in place as defense in depth.

Existing Basic account lockout, credential/TLS checks, browser CSRF, token scopes
on `/api/v1`, and rate limits remain unchanged. Do not remove them as part of this fix.

## Migration and removal

**Deprecated** since 2.1.0-beta2. **Planned removal: 2027-12-31**, no earlier than
a major-version release, subject to final release notes. Legacy routes remain usable
for existing clients until removal. Migrate clients as follows:

| Legacy path | Preferred v1 replacement |
| --- | --- |
| `POST /api/scan/upload` | `POST /api/v1/scans/uploads` (202 Accepted) |
| `POST /api/scan/path` | `POST /api/v1/scans/paths` (202 Accepted) |
| `GET /api/jobs` | `GET /api/v1/jobs?page=0&size=25` (paged) |
| `GET /api/jobs/{id}` | `GET /api/v1/jobs/{id}` |
| `GET /api/clamdtop` | `GET /api/v1/metrics/clamd` (sanitized) |
| `GET /api/endpoints` | `GET /api/v1/endpoints` |
| `GET /api/health` | `GET /api/v1/health/live` (and `/ready`) |

Use HTTPS with scoped Bearer tokens in new integrations. The current legacy machine
clients may continue to use Basic authentication with the existing TLS/CSRF flow.

## Test and verification

```sh
mvn -B -Dtest=Issue43LegacyApiIntegrationTest,ApiV1ContractIntegrationTest,PathPrivacyIntegrationTest test
python3 scripts/check-issue43-legacy-api.py
```

Check a nonexistent endpoint against `/api/scan/path` and `/api/scan/upload` for
404 `not_found`, a disabled endpoint for 400 `invalid_request`, and a bad job ID
for 404. The tests assert no scan job is queued for these failures, as well as
unchanged v1 endpoint behavior. Full Maven and Docker tests must pass before
closing the issue.
