# ClamAV Web UI v2.1.0 — final release

**Tag:** `v2.1.0`  
**Release date:** 20 November 2026  
**Docker Hub image:** `kosztyk/clamav-web-client:v2.1.0`

This final 2.1 release consolidates the security, reliability, performance, database, CI and release-engineering work tracked in issues #25–#57. The beta sections retained below are historical implementation notes; the active release identifier is `v2.1.0`.

## Final-release fixes added after beta validation

- **ClamAV 1.4.7 security update:** the hardened Web UI runtime carries `clamav-libs 1.4.7-r0` and a separately copied `clamdtop`; the `clamav` meta-package is intentionally absent. The default ClamD service is also pinned to 1.4.7. This remediates the 1.4.6 CVE batch reported by Docker Scout.
- **PostgreSQL legacy upgrade auto-repair:** before Flyway runs, the application checks whether `processed_files.idx_processed_path` is the historical UNIQUE constraint on `path`. Only that exact legacy shape is converted to the standalone unique index expected by V9; already-correct databases are unchanged and V9 itself is not edited.
- **Persistent Direct HTTP mode:** on trusted direct-HTTP deployments, the Configuration Center can persist `server.servlet.session.cookie.secure=false` so browser login sessions work without a TLS reverse proxy. Only the explicitly selected cookie policy is saved; forwarded-header trust is not changed implicitly. HTTPS-only secure cookies remain the default.
- **Docker Hub VEX discovery:** `scripts/attach-release-vex.sh` now attaches the digest-scoped OpenVEX document through Docker Scout's normal attestation backend using the short Docker Hub repository reference and verifies the publisher-authored VEX with `--vex-author`. The VEX-aware Scout result for the published image is `0C/0H/0M/0L`; Docker Hub's raw layer view may still display the underlying musl advisory.
- **Container package floors:** `libfontenc>=1.1.10-r0` is enforced for CVE-2026-109908 and the Alpine 3.24 `musl 1.2.6-r3` backport is documented through release-scoped VEX for CVE-2026-107891.

## Upgrade notes

Back up the application database plus mounted `conf/` and `data/` before upgrading. Existing PostgreSQL installations should be allowed to start normally; the compatibility repair runs automatically before Flyway when the exact legacy constraint is present. Do not run `flyway repair` or manually drop the constraint for this v2.1.0 migration. H2, MySQL and MariaDB use their existing vendor-specific migration paths.

For a direct LAN deployment using `http://host:port`, open **Setup & recovery → Direct HTTP configuration**, enable the trusted-network HTTP option, save, restart, and clear stale browser cookies before signing in again. Do not disable Secure cookies on an HTTPS deployment.

---

## Issue #55 — OpenAPI/Java/Go CLI API v1 contract gate (13 November 2026)

API v1 now has a blocking Spectral lint and pinned oasdiff breaking-change policy against both the historical `V2.0.0` release specification and the current PR/push baseline. OpenAPI/controller/CLI structural agreement is validated offline and by the new Spring MockMvc response-schema/handler-mapping test; controller/spec changes additionally run the real `clamctl` integration against the built Spring server. The checks are part of the existing single `gate` and tag-triggered verified release preflight; no new production configuration is required. The OpenAPI document now explicitly defines stable response payloads for uploads, path scans, progress, audit pagination, tokens and health. Source checks have passed, but live Maven/Spectral/oasdiff/GitHub acceptance remains pending. See `docs/ISSUE55_API_CONTRACT_GATE.md`.

## v2.1.0-beta2 — issue #50 (source candidate, 2026-11-13)

Release version consistency: `pom.xml` is the only manually maintained application/CLI release version. Maven-filtered resources provide the runtime app version, OpenAPI version and setup/login/footer displays; the CLI builder derives the version from POM, local Compose uses an unversioned `local` tag, and both the required CI `gate` and tag-release workflow verify version metadata. See `docs/ISSUE50_VERSION_CONSISTENCY.md`. **Not yet runtime-validated for release**; full Maven/Go/Docker CI pending.

## v2.1.0-beta2 maintenance update — issue #44 (2026-11-13)

- LDAP canonical principals use a persistent per-database directory namespace instead of changing when `ldap.url` or `baseDn` changes. First upgrade startup pins the exact legacy issuer for principal/audit continuity; optional explicit `CLAMAV_IDENTITY_LDAP_DIRECTORY_ID` and Configuration Center Advanced field for new deployments. Pinned ID cannot be changed without migration.
- Flyway **V8** adds `ldap_directory_identity` on H2, PostgreSQL, MySQL and MariaDB. Keep the table when migrating databases.
- A real, local JDK 21 StartTLS certificate SAN mismatch regression checks that trusted certificates for the wrong host fail. No permissive hostname verifier is introduced.
- **Upgrade order matters:** leave the old LDAP URL and base DN for the first beta2 startup, then change connection details; preserve the user DN and database. See [issue #44 guide](ISSUE44_LDAP_STABLE_DIRECTORY_ID.md).

# ClamAV Web UI v2.1.0

Released as complete application source on 31 October 2026. The application,
`clamctl`, Maven artifact, UI version labels, build helpers and current local
image examples use **2.1.0**. The HTTP API stays at `/api/v1`; Flyway was at
schema V4 for that original release; the current beta2 source includes later schema
migrations through V9. The existing `SPRING_FLYWAY_BASELINE_*` upgrade guidance
is unchanged.

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

On HTTP, select **Allow sign-in and configuration over HTTP on this trusted network (saved)**; this now persists the session-cookie policy and requires restart
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

## Beta2 security maintenance — issue #40 (13 November 2026)

External REST API credentials are no longer sent over non-loopback HTTP unless administrators explicitly enable `CLAMAV_REST_ALLOW_INSECURE=true` (Configuration Center: Connections → External REST scanner → Advanced; restart required). Credentialed HTTPS and loopback HTTP remain supported. Both REST clients, including reports, and the Connection Center REST probe use the same policy. Both REST clients enforce an upstream response size bound of 10 MiB; unsafe upstream errors are returned to browsers as fixed-shape JSON without exposing upstream bodies. See [issue #40 deployment notes](ISSUE40_REST_TRANSPORT_SECURITY.md). Existing HTTP+key deployments require HTTPS or explicit opt-in before startup. This changes no Flyway schema.

## Beta2 CSV integrity — issue #41 (13 November 2026)

REST job-history and detailed report CSV exports now share a lossless spreadsheet-safe field encoder. Previously, a destructive regular expression could turn `-5` into `'5` or `==x` into `'x`. The encoder now prefixes one apostrophe to potentially active spreadsheet expressions and retains the complete original value, including leading spaces, tabs/newlines and the prefixes `|` or `%`. Normal values and JSON export are unchanged. No new Configuration Center field, environment variable, or Flyway migration is needed. See [CSV export migration and tests](ISSUE41_CSV_EXPORT_INTEGRITY.md).


#### Issue #42 — runtime configuration privacy
Git no longer tracks `conf/clamav-web-client.properties`; the credential-free example remains in source. Existing webhook URLs are migrated to encrypted Configuration Center storage and removed from the legacy plaintext file after a successful write.


### Issue #56 — release governance and source privacy

Added a maintainer release checklist (`RELEASE.md`), Keep a Changelog log,
review template, security policy with private GitHub reporting guidance,
`CODEOWNERS` and automated GitHub release-note categories. All embedded
operator LAN addresses in the application source/documentation were replaced
with TEST-NET examples. A fail-closed RFC1918 audit now runs on PRs, releases
and before publishing the full-source ZIP. Deliberate SSRF test fixtures stay
allowlisted. GitHub repository admins must separately **enable private
vulnerability reporting**, protect `main` and configure required reviewers.
See [issue #56 runbook](ISSUE56_RELEASE_PROCESS_AND_SOURCE_PRIVACY.md).

- Candidate V9 adds endpoint-aware watcher fingerprints and durable per-source quarantine journal; see `AUDIT10_UPGRADE_NOTES.md`.