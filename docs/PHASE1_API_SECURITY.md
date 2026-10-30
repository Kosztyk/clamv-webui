# Phase 1: Stable, secure `/api/v1` + scoped API tokens

## Baseline and preservation

Built on `clamv-webui-phase0-clamdtop-terminal-fit-full-source.zip`, **not** the older GitHub `main` source. Preserves Phase 0 pages, sidebar, native PTY ClamdTop, REST monitor (`/rest-monitor/*`, `/api-scan`, `/api-reports`), Discord webhook settings, H2/Postgres support, Dockerfile, Compose, existing database mappings and legacy `/api` route paths. This release does **not** claim completion of proposals #8 or #9; it provides their API/security foundation.

## Interfaces

OpenAPI 3.0 contract: `src/main/resources/openapi/clamav-v1.yaml`, served at **`GET /api/v1/openapi.yaml`** after authentication with `endpoints:read`. The YAML is a **maintained static contract** rather than annotations synthesized by springdoc; this avoids introducing a new runtime dependency and is an intentional departure from fully auto-generated docs. Keep it updated with endpoint changes.

| Endpoint | Authorization | Behavior |
|---|---|---|
| `GET /api/v1/health/live` | `health:read` or VIEWER+ | Web application liveness (not clamd) |
| `GET /api/v1/health/ready` | `health:read` or VIEWER+ | JDBC connection validity; 503 if unavailable |
| `GET /api/v1/health/clamd?endpointId=` | `health:read` or VIEWER+ | Actual ping, daemon version, DB timestamp/freshness; 503 if unavailable/stale |
| `GET /api/v1/metrics/clamd?endpointId=` | `health:read` or VIEWER+ | Parsed threads/queue only; no raw commands or filenames |
| `GET /api/v1/endpoints` | `endpoints:read` or VIEWER+ | Enabled/disabled endpoint IDs and names, no hostnames |
| `GET /api/v1/jobs?page=&size=&status=&verdict=&endpointId=` | `jobs:read` or OPERATOR+ | Safe paginated DTOs, max 100/page by default |
| `GET /api/v1/jobs/{id}` | `jobs:read` or OPERATOR+ | One job DTO |
| `GET /api/v1/jobs/{id}/progress` | `jobs:read` or OPERATOR+ | Polling state until `completed=true` |
| `POST /api/v1/scans/uploads` | `scan:upload` or OPERATOR+ | Multipart `files` (repeat field) and optional endpointId; HTTP 202 jobIds |
| `POST /api/v1/scans/paths` | `scan:path` or OPERATOR+ | JSON `{ "path": "/scandir/example", "endpointId": 1 }`, HTTP 202 jobId |
| `GET /api/v1/audit` | `audit:read` or ADMIN | Sanitized paginated audit events |
| `GET, POST /api/v1/tokens` / `DELETE /api/v1/tokens/{id}` | ADMIN only | Read public metadata, issue one-time credential, revoke |

## API token lifecycle

Go to **Administration → API Tokens** in the UI **over HTTPS**. Creation over plain HTTP is rejected. Only ADMIN can issue/revoke. Select least-privileged scopes; set an expiration. The secret is shown exactly once and must be copied immediately. Only SHA-256 hash, a short public prefix and metadata are stored in `api_credentials`. Token revocation takes effect at the next request (validated against the DB each time). No signing key or plaintext token is persisted in your `conf` or source archive.

Example using an already-issued token (shell environment variable or secure secrets manager, **not** a token embedded into the command line):

```bash
export CLAMV_API_TOKEN="$(cat "$HOME/.config/clamv/token")"   # permissions 0600
printf 'header = "Authorization: Bearer %s"\n' "$CLAMV_API_TOKEN" | \
  curl -fsS --config - https://clamav.example.com/api/v1/health/ready
printf 'header = "Authorization: Bearer %s"\n' "$CLAMV_API_TOKEN" | \
  curl -fsS --config - 'https://clamav.example.com/api/v1/jobs?page=0&size=25'
```

`scan:path` tokens **must** have explicit `pathRoots` inside the existing global allowed scan roots. Blank path roots deliberately deny path submissions even when `scan:path` scope is selected. Blank endpoint restrictions mean any configured endpoint; set endpoint IDs to narrow them. Endpoint limits also filter job lists and individual job/progress responses, and health/metrics requests.

**HTTPS required** for token and HTTP Basic credentials. The service uses `request.isSecure()` to verify HTTPS. If terminating TLS at a reverse proxy, terminate traffic only at a trusted proxy, *strip/replace* all client-supplied `Forwarded` and `X-Forwarded-*` headers, then set `SERVER_FORWARD_HEADERS_STRATEGY=framework` only after that trust boundary is enforced. The baseline uses `server.forward-headers-strategy=none` for safety, so Bearer/Basic access behind an unconfigured TLS-offloading proxy will return `tls_required`. Browser form sessions continue on the current development HTTP deployment; use HTTPS in production.

## Health truthfulness

* `/api/health` is retained as **legacy compatibility** and is not authoritative for actual ClamD health.
* `/api/v1/health/live` proves only that the HTTP app is responding.
* `/api/v1/health/ready` attempts a real JDBC connection.
* `/api/v1/health/clamd` uses a live ClamD PING and VERSION response, parses the signature database timestamp and compares it to the configured maximum age (168 hours by default). The timestamp from ClamD VERSION lacks an explicit timezone; the default assumption is UTC and can be changed using `API_V1_CLAMD_DATABASE_TIMEZONE`. If it is missing/unparseable, freshness is `UNKNOWN`, **not** `FRESH`. Unreachable/STALE returns HTTP 503, UNKNOWN is surfaced explicitly. A server can be reachable without a provably current database.

## Security, privacy and audit

* Tokens are generated from `SecureRandom` (32 random bytes), hashed at rest (SHA-256), verified each request against revocation/expiry, and never logged.
* No token has `ROLE_ADMIN`, and bearer credentials cannot mint or revoke other tokens. Management is protected by ADMIN role and session CSRF token, with a browser UI.
* New job response DTOs omit `storedPath`, `quarantinePath`, raw detection/path JSON, raw errors and user private data; endpoint DTOs omit network host/port.
* **Intentional legacy schema hardening:** `GET /api/jobs` and `/api/jobs/{id}` still exist but now serialize safe DTOs rather than JPA entities. Stable core fields `id`, `type`, `status`, `verdict`, timestamps remain; sensitive fields `storedPath`, `quarantinePath`, user info, internal errors, raw detections and absolute target paths are removed. This is a response-schema difference and may require legacy client migration. Upload submission response shapes and route paths remain unchanged.
* Browser, legacy API and v1 submission routes call the same `ScanJobService` and `ScanExecutionService`. New scan audit events use `endpointId`, `jobId`, `transport` (BROWSER/API_SESSION/API_TOKEN/API_BASIC_OR_SESSION/WATCH), `QUEUED`, `FAILED`, then worker `SCAN_RESULT` verdict `OK`, `VIRUS_FOUND` or `ERROR`.
* An in-process limiter covers legacy, browser and v1 scan-submission endpoints, default **30 submissions/minute per IP/credential**. This is not distributed across replica instances. Add a trusted reverse proxy/API gateway limiter for multi-node/high-risk internet deployment. Rejections return HTTP 429.
* Error DTOs use generic codes/messages and do not expose exception strings or server paths. API audit DTOs omit raw details, IP addresses and user agents.

## Configuration / upgrades

All changes are additive DDL managed by the baseline `spring.jpa.hibernate.ddl-auto=update` (`api_credentials` table, `audit_events.transport`, `audit_events.endpoint_id`, `scan_jobs.submitted_transport`). For production, back up H2/Postgres before upgrade and consider replacing Hibernate auto-DDL with explicit migration tooling in Phase 2/#5. No change to the mounted `./data` and `./conf` volumes. The external REST monitor remains **separate** from the local v1 API.

| Spring property | Default | Environment variable |
|---|---|---|
| `api.v1.scan-rate-limit-per-minute` | 30 | `API_V1_SCAN_RATE_LIMIT_PER_MINUTE` |
| `api.v1.max-page-size` | 100 | `API_V1_MAX_PAGE_SIZE` |
| `api.v1.clamd.max-database-age-hours` | 168 | `API_V1_CLAMD_MAX_DATABASE_AGE_HOURS` |
| `api.v1.clamd.database-timezone` | UTC | `API_V1_CLAMD_DATABASE_TIMEZONE` |
| `server.forward-headers-strategy` | none | `SERVER_FORWARD_HEADERS_STRATEGY` (trusted proxy only) |

## Verification and limitations

Automated Java tests added for token issuance/revocation, HTTPS requirement, scoped reads and writes, CSRF and DTO redaction. Existing Phase 0 tests preserved. Static validation done on this archive: complete file inventory, OpenAPI YAML parse, security/path audits, Java lexical parsing and ZIP CRC. This environment lacks Maven and Docker; **full `mvn verify`, image build, database upgrade, webhook delivery, live ClamD health and EICAR acceptance have not been executed here**. Run on the test host:

```bash
docker compose build --no-cache clamav-web-client
docker compose up -d --no-build
docker compose logs -f clamav-web-client
```

Then validate role matrix, token rotation/revocation, 401/403, upload and path scans, job progress, `SCAN_RESULT` audit parity for clean/infected/error, database readiness vs daemon availability, ClamdTop terminal, existing REST API integration and Discord. Do not remove legacy routes until CLI/MCP callers have migrated.
