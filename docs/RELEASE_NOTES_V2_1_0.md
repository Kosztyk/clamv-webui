# ClamAV Web UI v2.1.0

Released as complete application source on 31 October 2026. The application,
`clamctl`, Maven artifact, UI version labels, build helpers and current local
image examples use **2.1.0**. The HTTP API stays at `/api/v1`; Flyway stays at
schema V4. This release adds no SQL migration and requires no new baseline.

## Configuration Center

Administration → Configuration center provides seven categories: Connections,
Scanning, Identity & API, Notifications, Network, Monitoring and System.
Disabled features and advanced options stay collapsed. Connection tests,
masked change review, encrypted persisted credentials and live/restart status
are implemented in the application.

First-run setup and database-independent recovery are available at `/setup/`.
They require the separate owner credential in
`/app/conf/configuration-recovery-token`. Fresh setup opens no database until
the operator applies the reviewed configuration and selects Start application.
An unavailable external database enters recovery without creating an H2
replacement.

## Consolidated administration and live jobs

Configuration Center is the sole GUI editor for global settings. Duplicate
Settings/Authentication menus and Prometheus/observer forms are retired; their
old writes cannot bypass review. Diagnostics, downloads and separate resource
management remain available. LDAP/OIDC retain all runtime fields and existing
credentials; provider limits and scopes use consistent validation. Disabled
OIDC drafts no longer create incomplete Spring registrations. Default-engine
selection remains stable across renames and recreation.

Scan Jobs automatically refreshes status/verdict without navigation, pauses in
the background, retains results on temporary failures and fetches fresh content
on return. See [the complete consolidation audit](CONFIGURATION_CONSOLIDATION.md).

## Direct setup without nginx

Open `http://YOUR_SERVER:8080/setup/` on the published application port. No
reverse proxy, registered DNS name or certificate is required. The interface
uses the address opened in the browser; application defaults and Compose do
not contain a homelab IP or preconfigured external REST URL.

On HTTP, select **Allow configuration over HTTP on this trusted network**
before submitting database passwords or other configuration secrets. This
choice resets on a new page. The browser blocks unconfirmed credential
submission before sending the request, and the server enforces confirmation
independently. HTTP remains unencrypted. HTTPS needs no HTTP confirmation.
Normal sensitive administration still requires an enabled local
administrator's password, role checks, CSRF protection and a current review.

An incorrect recovery credential returns **401** with an explanation that it
must match this application instance. Eight failed attempts trigger a
temporary **429** with `Retry-After: 60`. The persisted owner credential remains
valid after the retry window; restarting does not rotate it.

## Compose remains supported

The minimal Compose example needs no application environment fields.
Operators can still set any supported deployment override in Compose,
Kubernetes, JVM properties or external Spring configuration. Explicit
deployment values take precedence and appear read-only in the GUI, including
explicit `false` values. The GUI never rewrites deployment YAML.

Persistent `conf` and `data`, mounted sockets/scan paths, external services and
host permissions remain deployment responsibilities. Existing installations
should preserve their own volumes, environment, database and encryption keys.

## Build and deploy

From the complete extracted source directory:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

For a fresh GUI-managed installation, prepare the mounted folders for UID/GID
10001 and start the included minimal example:

```bash
mkdir -p conf data
sudo chown -R 10001:10001 conf data
sudo chmod 700 conf
docker compose -f docker-compose.configuration-center.yaml up -d
docker exec clamav-web-client cat /app/conf/configuration-recovery-token
```

For an existing installation, build the same image, select
`clamav-web-client:2.1.0-local` with `pull_policy: never` in its existing service,
and recreate that service using its existing Compose files. Back up the
database and the complete `conf`/`data` directories first. Do not extract source
over production runtime data.

All changes are in application source and the Dockerfile. No
`build-and-start.sh --trusted-proxy` step or image patch is required. Publishing
this source archive does not publish a GitHub release or Docker Hub image.

## Retained behavior and validation

The non-root runtime, reverse-proxy HTTPS recognition, asynchronous bounded
webhook delivery, timeouts and private-address safeguards remain included.
API/CLI/MCP Bearer authentication still requires HTTPS; the GUI's HTTP choice
does not mark a request secure or bypass token checks. Existing scanning,
identity, monitoring, CLI/MCP and database features remain in the full source.

See [setup and compatibility](CONFIGURATION_CENTER.md),
[the field reference](CONFIGURATION_REFERENCE.md),
[deployment](../SERVER_DEPLOYMENT.md) and
[v2.1.0 validation](CONFIGURATION_CENTER_VALIDATION.md). Historical v2.0.0 field
and security reports retain their original scope and results.

## Complete configuration audit

All 107 settings have been audited for a single editor, storage, deployment precedence and runtime consumers. The audit fixes disabled-engine browser/watcher submissions, renamed-primary daemon policy, colliding engine names, watcher interval rounding and failed-enqueue retries. Numeric bounds match the API/metrics implementations; invalid REST field names/paths, empty storage directories and invalid baseline versions are rejected before save.

The final validation has 604 passing Java tests, five environment-limited Unix-socket skips and 236 all-field browser checks, including 55 non-identity GUI saves, recreation, a self-signed REST receiver, metrics on a separate port, API limits and real scan/quarantine/fallback behavior. See [the complete field audit](CONFIGURATION_FIELD_AUDIT.md).

## Complete public-source documentation package — 1 November 2026

README now provides explicit Configuration Center and Compose/hybrid setup, the minimum no-environment Compose, the exact setup/recovery credential command and first administrator login. Generic examples replace private deployment identifiers throughout the package, including external SQL URL templates. The complete source includes named-volume and environment-managed Compose alternatives, SHA-256 manifest, reusable privacy checks and a macOS/Linux GitHub publisher with 12 local-Git regression tests. See [publishing](PUBLISHING.md) and [source privacy](SOURCE_PACKAGE_PRIVACY.md).

## Database Test connection correction — 1 November 2026

The setup and Configuration Center probe now use the configured connection-pool budget for PostgreSQL, MySQL and MariaDB with the correct driver timeout units, explicit packaged-driver selection and a bounded overall deadline. This fixes a reproduced PostgreSQL test failure when authentication takes longer than the old three-second limit while normal startup can connect. Errors distinguish authentication, missing database, permissions, TLS, DNS, connection refusal and timeout without exposing raw JDBC details.

Testing a new H2 database now explains that it has not been initialized and that its credentials are unverified; Review → Apply → Start application still creates it. Existing `IFEXISTS` options are replaced rather than duplicated. Connection tests do not save configuration or migrate schemas. Direct HTTP setup keeps its explicit credential confirmation and works without a proxy. See [all database test behavior and validation](DATABASE_CONNECTION_TESTS.md). No new Compose field or build helper is needed.

## API and deployment documentation package — 1 November 2026

README now includes built-in `/api/v1` token/upload/job examples and a complete optional REST-service Compose. The new [REST deployment guide](REST_API_DEPLOYMENT.md) explains GUI versus environment control, network addresses, endpoint compatibility, multipart field names, upload-size units and checks. The minimal GUI setup, recovery credential command and GitHub publisher remain included. Application/CLI code and version are unchanged from the tested database-probe correction.

## GitHub publisher cache fix — 1 November 2026

The standalone publisher and archived copy now select writable macOS/Linux cache locations and a private temporary fallback, fixing the macOS `~/.local/share` permission failure without elevated privileges or ownership changes. CLI download checksums remain mandatory. The temporary path uses process-scoped Git credentials. All 19 local publisher regressions pass; the corrected script accepts existing complete v2.1.0 ZIPs. See [publishing and recovery](PUBLISHING.md).
