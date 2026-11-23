# Official CLI — clamctl v2.1.0

Use the public HTTPS URL in `clamctl`. For TLS termination at a reverse proxy
or Ingress, enable `native` forwarded-header handling and allowlist the proxy
addresses. The Web UI defaults to `none` for direct access.
See [proxy TLS configuration](REVERSE_PROXY_TLS.md) for the settings and
`403 tls_required` troubleshooting.

The standalone Go CLI is in [`cli/`](../cli/README.md). The build generates
Linux, macOS and Windows executables for both amd64 and arm64 in `cli/dist`.
The executables require no language runtime.

It implements server configuration, verified Basic/token login, status, endpoint
listing, streamed upload and server-path submissions, wait-for-verdict, filtered
job listing, job details, progress watching and audit listing. Tables are the
default; `--json` provides machine-readable output. `CLAMAV_API_TOKEN` and
`CLAMAV_API_KEY` are supported token aliases. See the CLI guide for installation,
precedence, private CA support, credential storage and CI exit-code semantics.

## Additive API support

- `GET /api/v1/auth`: checks the caller's authentication without requiring an
  unrelated scope. Returns only authenticated status, principal name and kind.
- `GET /api/v1/csrf`: provides the normal session CSRF token/header for Basic-auth
  writes. The CLI retains the session cookie and sends the header. Existing
  browser and token-management CSRF checks remain in force.
- Job DTOs add `signatures` (sorted/distinct, bounded to 1,000 values) and
  `durationMillis` (started-to-finished execution time, excluding queueing).
  Detection-map keys, raw error text and internal file paths remain excluded.
  Missing/invalid metadata is represented by an empty signature list; unfinished
  or invalid timestamps produce a null duration.
- `/api/v1/metrics/clamd` adds pool count and allowlisted native memory quantities.
  Threads and queue values are also allowlisted numeric fields. Unknown values
  are absent/null rather than fabricated zeros. Raw commands, filenames and
  arbitrary daemon text are not exposed through this route.
- The maintained OpenAPI contract is updated to `1.2.0-mcp`; API route prefix
  remains `/api/v1`; the application and CLI release version is `2.1.0`.

These are response additions and authenticated helper routes. No database schema,
Maven dependency, endpoint, identity configuration or existing scan engine change
is required. Deploy the updated web application before using CLI `login` or
Basic-auth scan writes. Existing scoped Bearer API access remains available.

Validation is recorded in [CLI_VALIDATION.md](CLI_VALIDATION.md).
