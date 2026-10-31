# Build and deploy the complete application

This archive contains the full application: Java source, templates, static
assets, database migrations, monitoring dashboards, CLI/MCP source and release
binaries, documentation and all earlier fixes. Issues #10, #11 and #12 are
implemented directly in the Dockerfile, application code and configuration.

The same build works from a Git checkout. See [Update and verify](docs/UPDATE_AND_VERIFY.md)
for upgrade commands, local/self-signed HTTPS testing and expected results, and
[update notes](docs/UPDATE_NOTES_2026_10_31.md) for compatibility details.

## Normal Docker build

Extract the full ZIP and build from the extracted application directory:

```bash
unzip clamv-webui-v2.0.0-non-root-proxy-tls-full-source.zip
cd clamv-webui-v2.0.0-non-root-proxy-tls-full-source
docker build --pull -t clamav-web-client:2.0.0-local .
```

Docker builds the JAR and runs Maven verification in the build stage. The
runtime image includes native ClamdTop and its PTY helper. Host Java, Maven,
Node and Go are not required for this Web UI build.

The resulting image already includes:

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
    image: clamav-web-client:2.0.0-local
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

The root Compose builds the same local image and starts a ClamAV daemon with H2
storage. Prepare the bind mounts as shown above, then use:

```bash
docker compose up -d --no-build --pull never
docker compose logs -f clamav-web-client
```

The default host port is 8080. Allow ClamAV time to download its signatures.
The first-install administrator password is generated securely and saved in
`/app/data/bootstrap-admin-password`; existing accounts are preserved.

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

It builds `clamav-web-client:2.0.0-local` before stopping the existing UI, writes
an image override beside the existing Compose files, and verifies startup. Its
legacy `--trusted-proxy` option only writes an explicit runtime `framework`
setting, which is now the application default. `--direct-access` explicitly
sets `none`. Earlier explicit runtime overrides are preserved. If using the
helper, include its `clamv-webui-local-image.override.yml` with the original
Compose files for later operations.

## Verification

See `SERVER_PACKAGE_VALIDATION.json` and
`docs/security/proxy-tls-validation.json` for results from this source package.
They distinguish Maven/HTTP checks from Docker image execution. A Docker image
build and container smoke run require Docker on the target host.
