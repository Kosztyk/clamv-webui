# Building the Web UI container

For the 30 October 2026 dependency security revision, follow
[Security dependency update](SECURITY_DEPENDENCY_UPDATE.md). Use `--pull` and a
fresh build, then scan the resulting local image. An existing image is not
updated by replacing the source archive.

Extract the full source archive and run from the directory containing
`Dockerfile`, `pom.xml`, `src/` and `dashboards/`:

```bash
docker build --pull -t clamav-web-client:2.0.0-local .
```

The non-root and proxy fixes are part of this normal build: the Dockerfile declares
`USER 10001:10001` (issue #10), and the JAR packages
`server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:framework}`
(issue #11). No `build-and-start.sh` command or `--trusted-proxy` flag is
required. HTTPS forwarded by a trusted TLS-terminating proxy is recognized
automatically; the proxy must replace incoming forwarding headers and be the
only path to the backend. Remove an old `SERVER_FORWARD_HEADERS_STRATEGY=none`
override if you want the new default. See [proxy TLS](REVERSE_PROXY_TLS.md).

Issue #12 is also built into the source: asynchronous, bounded webhook delivery,
finite timeouts and private-address filtering. See [webhook configuration](WEBHOOK_RESILIENCE.md)
for optional operator tuning; the defaults need no build helper.

See [Update and verify](UPDATE_AND_VERIFY.md) for the complete existing-deployment
procedure and copyable runtime checks, including local HTTPS with a self-signed
certificate. [Update notes](UPDATE_NOTES_2026_10_31.md) summarize compatibility
and the optional settings.

The build uses Maven with Java 21 inside its build stage and runs `mvn verify`,
including tests. The runtime stage contains the Java application, native
`clamdtop` and its PTY helper. Java, Maven and Go are not required on the Docker
host. The standalone CLI/MCP binaries under `cli/dist` run separately; this
Dockerfile builds the Web UI container.

For a fresh deployment using the included Compose file:

```bash
mkdir -p conf data
sudo chown -R 10001:10001 conf data
sudo chmod -R u+rwX conf data
docker compose config --quiet
docker compose up -d --build
docker compose logs -f clamav-web-client
```

The included Compose file starts a ClamAV daemon and a Web UI with default H2
storage. An existing deployment should retain its own Compose environment,
external database settings, endpoint configuration, secrets and volume mounts.
Do not replace runtime settings or delete `conf`/`data` to update application
source.

The runtime uses UID/GID `10001:10001`. Before updating an existing installation,
prepare its bind-mounted folders using the
[non-root permission migration](NON_ROOT_CONTAINER.md). Image ownership does not
change the ownership of mounted host directories. Docker-managed named volumes
are an alternative for new installs, including Docker Desktop.

If using an existing deployment Compose, select the locally built image in its
Web UI service:

```yaml
image: clamav-web-client:2.0.0-local
pull_policy: never
```

Then recreate just that service (substitute its name if different):

```bash
docker compose up -d --no-deps --force-recreate clamav-web-client
docker compose logs -f clamav-web-client
```

When building on an ARM Mac for an x86-64 server, select the deployment platform:

```bash
docker build --platform linux/amd64 --pull -t clamav-web-client:2.0.0-local .
```

The resulting image stays on the build machine until transferred to the server
or an explicitly chosen registry. Do not use `--platform linux/amd64` for an
ARM64 deployment.

## Maven verification failures corrected on 30 October 2026

The previous Dockerfile copied `pom.xml` and `src/` but omitted `dashboards/`.
`MonitoringDashboardTest` needs the Grafana JSON and Prometheus alert YAML at
those source-relative paths. The build stage now copies that directory before
running verification.

OIDC tests use static mocking. Test resources explicitly select Mockito's inline
mock maker, and Maven starts test JVMs with the managed Mockito JAR as a startup
agent. The dependency plugin resolves its JAR path rather than hard-coding a
host Maven cache path. Surefire and the optional Failsafe profile preserve any
caller/coverage `argLine`. No manual `-javaagent` option is required. Mockito and
its configuration remain test-only and are not added to the application image.

Tests remain enabled. Neither `-DskipTests` nor `maven.test.skip` is used for the
verification step. See `DOCKER_BUILD_VALIDATION.md` for the checks run on this
corrected package.
