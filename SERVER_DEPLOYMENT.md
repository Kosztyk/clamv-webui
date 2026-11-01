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
- **Reverse proxy TLS support:** `application.properties` packages
  `server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:framework}`.
  Both RFC `Forwarded` and `X-Forwarded-Proto` HTTPS schemes are recognized by
  Spring before Bearer authentication checks `request.isSecure()`.
- **Webhook resilience (issue #12):** notifications use a dedicated bounded
  executor, finite timeouts and a public-address policy by default. Internal
  receivers require an explicit startup setting; see
  [webhook policy](docs/WEBHOOK_RESILIENCE.md).

No deployment script or `--trusted-proxy` flag is required for these fixes.
HTTPS, token validity, scope enforcement and revocation checks remain active.
An old runtime `SERVER_FORWARD_HEADERS_STRATEGY=none` setting still overrides
the packaged default; remove it to use automatic proxy HTTPS recognition.
The included Compose examples and environment example now default to
`framework` as well.

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
mkdir -p conf data
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

The root Compose uses the same local image and starts a separate ClamAV daemon.
Choose built-in H2 or an existing external database during first-run setup. Prepare the bind mounts as shown above, then use:

```bash
docker compose up -d --no-build --pull never
docker compose logs -f clamav-web-client
```

The default host port is 8080. Open `http://YOUR_SERVER:8080/setup/` and read the
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

## Reverse proxy deployment

Keep the backend reachable only from the trusted proxy/Ingress. The proxy must
replace incoming `Forwarded` and `X-Forwarded-*` headers and forward the original
HTTPS scheme. No application opt-in is needed. Use the public HTTPS URL in
`clamctl`, MCP and API clients. See [reverse proxy TLS](docs/REVERSE_PROXY_TLS.md)
for Nginx, Kubernetes and direct-access opt-out guidance.

## Optional existing-deployment helper

`build-and-start.sh` is retained as a convenience for operators who want it to
detect an existing Compose deployment and prepare its bind-mount ownership.
It is not used by the Dockerfile and is not required to apply these changes:

```bash
bash build-and-start.sh
```

It builds `clamav-web-client:2.1.0-local` before stopping the existing UI, writes
an image override beside the existing Compose files, and verifies startup. Its
legacy `--trusted-proxy` option only writes an explicit runtime `framework`
setting, which is now the application default. `--direct-access` explicitly
sets `none`. Earlier explicit runtime overrides are preserved. If using the
helper, include its `clamv-webui-local-image.override.yml` with the original
Compose files for later operations.

## Verification

See [v2.1.0 validation](docs/CONFIGURATION_CENTER_VALIDATION.md) and
`SERVER_PACKAGE_VALIDATION.json` for current results. Earlier security reports
retain their original v2.0.0 test scope.
They distinguish Maven/HTTP checks from Docker image execution. A Docker image
build and container smoke run require Docker on the target host.
