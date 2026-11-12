# Build and deploy the complete application

This archive contains the full application: Java source, templates, static
assets, database migrations, monitoring dashboards, CLI/MCP source and release
assets, documentation and all earlier fixes. Issues #10, #11 and #12 are
implemented directly in the Dockerfile, application code and configuration.
Direct HTTP setup works at the application's published port, with explicit on-page confirmation for credential submission; no reverse proxy or preconfigured URL is required. The Configuration Center is also built in; see [minimal Compose, GUI setup and database-independent recovery](docs/CONFIGURATION_CENTER.md).

The same build works from a Git checkout. See [Update and verify](docs/UPDATE_AND_VERIFY.md)
for upgrade commands, local/self-signed HTTPS testing and expected results, and
[update notes](docs/UPDATE_NOTES_2026_10_31.md) for compatibility details.

## Configuration methods

Use [the minimum named-volume Compose](docker-compose.minimal.yaml) for GUI-managed application settings, or [the Compose environment example](docker-compose.environment.yaml) for deployment-managed core connections. Both are explained with first-run recovery-token and login steps in [README](README.md#configure-the-app-with-configuration-center). Existing deployments should retain their original mounts and SQL connection. To publish source, use [the included GitHub script and guide](docs/PUBLISHING.md).

## Normal Docker build

Extract the full ZIP and build from the extracted application directory:

```bash
unzip clamv-webui-v2.1.0-configuration-center-full-source.zip
cd clamv-webui-v2.1.0
docker build --pull -t clamav-web-client:2.1.0-local .
```

Docker builds the JAR and runs Maven verification in the build stage. The
runtime image includes native ClamdTop and its PTY helper. Host Java, Maven,
Node and Go are not required for this Web UI build.

The resulting image already includes:

- **Configuration Center:** seven categorized tabs, conditional inputs, deployment locks, encrypted persistence, reviewed changes and protected setup/recovery before database initialization.

- **Non-root runtime:** `USER 10001:10001`; writable image-owned `/app/data`
  and `/app/conf`; a root-owned, read-only application JAR.
- **Forwarded-header protection (issue #13):** the packaged default is
  `server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:none}`.
  Direct requests retain their socket IP address and actual transport security.
  TLS proxy support (issue #11) is an explicit opt-in using `native` and an
  allowlist of actual proxy peers. Native mode alone trusts nobody.
- **Webhook resilience (issue #12):** notifications use a dedicated bounded
  executor, finite timeouts and a public-address policy by default. Internal
  receivers require an explicit startup setting; see
  [webhook policy](docs/WEBHOOK_RESILIENCE.md).

No deployment script is required. HTTPS, token validity, scope enforcement and
revocation checks remain active. Remove or replace old explicit `framework`
overrides in Compose, command-line settings and saved Configuration Center
settings; higher-priority deployment configuration still overrides defaults.
Use `none` for direct access, or follow the proxy opt-in below.

## Update an existing deployment

Keep the existing Compose service, database settings, ports, ClamD settings,
LDAP/OIDC settings, secrets and mounted `conf`/`data` directories. Select the
local image in that service:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:2.1.0-local
    pull_policy: never
    # Keep the rest of your existing service configuration here.
```

Prepare existing bind mounts for UID/GID 10001 on the Docker host. Run these
commands in the existing deployment directory, using its actual mount paths:

```bash
mkdir -p conf data/uploads
sudo chown -R 10001:10001 conf data
sudo chmod -R u+rwX conf data
```

For named volumes, Docker Desktop, restricted engines or Unix socket mounts,
see [non-root deployment and permission migration](docs/NON_ROOT_CONTAINER.md).
Image ownership cannot change a host bind mount's permissions.

From the existing Compose deployment directory, recreate only the UI service:

```bash
docker compose config --quiet
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
docker compose logs -f clamav-web-client
```

Substitute your actual service name. The newly built image must exist on that
Docker host. Back up the database and `conf`/`data` before upgrading; do not
extract over the live deployment's data. These changes do not require a
Flyway baseline, database reset or account reset. Preserve any migration
settings already needed by a v1-to-v2 upgrade.

## First installation

The root Compose builds the local image and starts a separate ClamAV daemon. Supply its existing-database environment values before starting. For a fresh GUI-managed H2 installation with bundled ClamD, use `examples/compose/standalone-h2.yaml` instead. Prepare the bind mounts, including `data/uploads` when native upload scanning is enabled, then use:

```bash
docker compose up -d --no-build --pull never
docker compose logs -f clamav-web-client
```

The root Compose publishes the Web UI on host port 8081; the standalone example uses loopback port 8080. On installations waiting for first-run setup, open `/setup/` on that published port and read the
owner credential from this container:

```bash
docker exec clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

Unlock setup. When using HTTP, select **Allow configuration over HTTP on this
trusted network** before submitting credentials. Enter `clamav-server` and `3310`
for the bundled scanner, choose the database, review and apply, then select
**Start application**. Neither nginx nor a domain or certificate is required.
Allow ClamAV time to download its signatures. The first-install administrator
password is generated securely and saved in `/app/data/bootstrap-admin-password`;
existing accounts are preserved.

## ClamD network and filesystem isolation

Never expose unauthenticated ClamD port 3310 to untrusted networks. The bundled
scanner publishes no host port; the Web UI connects to `clamav-server:3310`
through Compose. Optional host diagnostics can use the supplied loopback-only
override. Loopback binding is not authentication.

ClamD receives only `./data/uploads:/app/data/uploads:ro`, plus its independent
signature volume. Do not mount the whole application data directory into it:
that exposes the H2 database and bootstrap administrator password. The app uses
`CLAMAV_UPLOAD_DIR=/app/data/uploads` so native MULTISCAN and ClamdTop filenames
continue to work at the same absolute path. Uploads must be readable by ClamD.

On an existing deployment, remove the daemon's old broad data mount and host
port mapping, then recreate it; adding another narrower mount does not remove
the old one during Compose merging. Keep the Web UI's data mount.

Create the upload directory before starting; the mount deliberately does not
create a missing host directory with root ownership:

```bash
sudo install -d -o 10001 -g 10001 -m 0700 data/uploads
```

This creates/prepares only the directory, preserving existing contents. Retain
your current database, mounts and configuration during an update. The root
Compose and standalone example pin the scanner image by version and digest;
review and update that pin when applying upstream image security updates.
`CLAMAV_REST_URL` is blank by default and refers only to an optional, separate
REST scanner. See [isolation, architecture and smoke-test details](docs/CLAMD_ISOLATION.md).

## Reverse proxy deployment

Enable native forwarding and specify only the proxy's actual socket IP/CIDR as
seen by the application (Docker NAT may change it):

```yaml
environment:
  SERVER_FORWARD_HEADERS_STRATEGY: native
  SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: "172.30.0.10/32"
  SERVER_TOMCAT_REMOTEIP_TRUSTED_PROXIES: "(?!)"
  SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
```

Replace the example address with your proxy. IPv6 `/128`, comma-separated CIDRs
and Tomcat regular expressions are supported. The default `(?!)` matches nobody;
never use a blank value or trust an entire client LAN. Keep the backend reachable
only from the proxy/Ingress. Replace incoming forwarded headers and send
`X-Forwarded-Proto` and `X-Forwarded-For`; native mode does not interpret RFC
`Forwarded` alone. Use the public HTTPS URL in `clamctl`, MCP and API clients.
See [safe proxy deployment and migration](docs/REVERSE_PROXY_TLS.md).

## Optional existing-deployment helper

`build-and-start.sh` is retained as a convenience for operators who want it to
detect an existing Compose deployment and prepare its bind-mount ownership.
It is not used by the Dockerfile and is not required to apply these changes:

```bash
bash build-and-start.sh
```

It builds `clamav-web-client:2.1.0-local` before stopping the existing UI, writes
an image override beside the existing Compose files, and verifies startup. Its
`--trusted-proxy` option requires `CLAMAV_TRUSTED_PROXY_CIDRS` and writes native
mode with that allowlist. `--direct-access` explicitly sets `none`. An older
managed `framework` override requires an explicit choice to migrate it. If using the
helper, include its `clamv-webui-local-image.override.yml` with the original
Compose files for later operations.

## Verification

See [v2.1.0 validation](docs/CONFIGURATION_CENTER_VALIDATION.md) and
`SERVER_PACKAGE_VALIDATION.json` for current results. Earlier security reports
retain their original v2.0.0 test scope.
They distinguish Maven/HTTP checks from Docker image execution. A Docker image
build and container smoke run require Docker on the target host.

## Daemon timeouts and scheduler capacity

The bundled scheduler uses four threads so a slow daemon metrics poll does not serialize configuration polling, watcher checks, job gauges and queue reconciliation. TCP and Unix health snapshots have a shared five-second budget; DNS/connect work is capped at three seconds within it. INSTREAM and MULTISCAN use a separate 24-hour total scan budget, with bounded stalled writes and replies capped at 4 MiB.

These are startup deployment settings. Defaults work without adding environment entries; optional overrides are:

```yaml
environment:
  CLAMAV_CLIENT_CONNECT_TIMEOUT: "3s"
  CLAMAV_CLIENT_COMMAND_TIMEOUT: "5s"
  CLAMAV_CLIENT_SCAN_TIMEOUT: "24h"
  SPRING_TASK_SCHEDULING_POOL_SIZE: "4"
```

Recreate the application after changing them. Connect/command durations must be 1 ms–1 minute, the scan duration 1 ms–7 days, and the pool size 4–64; invalid values fail startup. Health failures return promptly instead of consuming the scan budget. These settings are separate from the optional REST scanner's HTTP timeouts and from `clamd.conf` limits. See [timeout behavior and tests](docs/CLAMD_TIMEOUTS.md).

## Upload retention and private native scan inputs

Clean uploaded copies are deleted by default after completion; infected copies are quarantined or deleted. Configure the 24-hour upload retention and optional job/audit history limits under **Configuration Center → Scanning → Upload cleanup and history**. See [complete settings and upgrade steps](docs/UPLOAD_RETENTION.md).

POSIX uploads now use `0700` directories and `0600` files. The bundled native scanner builds from the pinned official ClamAV base with its daemon account mapped to UID/GID 10001. Rebuild/recreate both services with `docker compose up -d --build --force-recreate clamav-server clamav-web-client`; keep the database, conf/data and keys. For a separately managed daemon, match the upload owner UID or use INSTREAM. No database schema migration is needed.

## Browser session limits and account revocation (issue #22)

Account deletion, disabling, role changes and password resets revoke existing
browser sessions after commit. Local account state is rechecked before protected
requests. The default idle timeout is **30 minutes**; a separate **one-hour
absolute lifetime** requires fresh authentication even during activity. Adjust
both in Configuration Center → Network → Reverse proxy & session security →
Advanced, then recreate the application. Optional deployment overrides:

```yaml
environment:
  SERVER_SERVLET_SESSION_TIMEOUT: "30m"
  CLAMAV_SECURITY_SESSION_MAX_AGE: "1h"
```

The absolute setting accepts 1s–24h and rejects invalid startup values. Session
revocation returns API 401 or a browser login redirect. Admin → Users also offers
explicit local/external **Revoke sessions** and access editing. Remove external
group access at the IdP before revoking its recorded identity's sessions; live
LDAP/OIDC membership removal is not automatically pushed to the app. Explicit
revocations are per application instance. No schema migration is required.
See [complete behavior, WebSocket handling and verification](docs/SESSION_REVOCATION.md).

## Bounded login and scan throttling (issue #23)

The default login store tracks at most 10,000 realm/account records; the scan
store tracks at most 10,000 token/account/anonymous-IP budgets. Records expire
and indexed cleanup performs bounded work. At capacity, new keys are temporarily
throttled rather than evicting active protection. Monitor capacity warnings and
retain ingress abuse controls; a saturated login store can block a new legitimate
account until space is released. Existing browser sessions remain usable.

Optional overrides on the existing web service:

```yaml
environment:
  CLAMAV_SECURITY_LOGIN_MAX_ENTRIES: "10000"
  API_V1_SCAN_RATE_LIMIT_MAX_ENTRIES: "10000"
  API_V1_SCAN_RATE_LIMIT_PER_MINUTE: "30"
```

Capacities accept 1–100000. Scan attempts accept 1–10000 per subject per
60-second window. These settings require restart and are also in Configuration
Center → Identity & API. State is per instance; no distributed throttle or schema
migration is added. See [identity rules and verification](docs/BOUNDED_LIMITERS.md).

## Scan path privacy and alias compatibility (issue #24)

Rebuild/recreate the app to apply lexical root screening before any untrusted
target lookup and canonical containment afterward. No setting or schema change is
required. Configured root aliases remain usable through their configured or real
spelling. Arbitrary aliases outside all configured/canonical roots are denied;
submit the canonical in-root path or the configured root spelling instead.

Missing and forbidden paths share a generic validation error. Restricted Bearer
path denials use one 403 status, including missing targets, so private filesystem
existence cannot be inferred from those errors. Diagnose root mounts/permissions
on the host when needed. See [behavior and tests](docs/SCAN_PATH_PRIVACY.md).
