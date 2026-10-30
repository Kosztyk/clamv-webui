# ClamAV Web UI

**Self-hosted ClamAV management, scanning, monitoring and integrations.** Manage ClamD from a browser: upload and server-path scans, asynchronous jobs, quarantine, watched directories, audit history, native interactive ClamdTop, a separate REST scanner bridge, local/LDAP/OIDC sign-in, scoped API tokens, Prometheus/Grafana, CLI and MCP.

**Application version:** 2.0.0. **Image used in examples:** [`kosztyk/clamav-web-client:v2`](https://hub.docker.com/r/kosztyk/clamav-web-client). **Source snapshot:** `v2.0.0-security.1`, based on Spring Boot 4.0.9 (see [security revision](docs/SECURITY_DEPENDENCY_UPDATE.md)). The image tag `v2` is mutable; **do not assume it already contains this particular source revision** without verifying its digest/build. To run the security.1 source itself, build a local image using the included `Dockerfile`.

> **Start here:** [**Complete User & Administrator Guide — all features, UI fields, configuration, Docker Compose, APIs, upgrades and troubleshooting**](docs/COMPLETE_USER_GUIDE.md). This is the canonical, single-document how-to, with a linked table of contents.

## What you can do

| Area | Functions | Where |
|---|---|---|
| Scan | Upload multiple files, scan approved server paths, native path scanning/INSTREAM | **Scan** |
| Track | Job history, status, detections, timestamps, quarantine evidence | **Jobs** |
| Observe | Endpoint dashboard, PING/VERSION/STATS, interactive native `clamdtop` | **Dashboard**, **Main**, **ClamdTop** |
| Automate | Watched folders, completion webhooks, scoped `/api/v1` tokens | **Admin → Watch Directories**, **Settings**, **API Tokens** |
| Integrate | Optional external REST scanner jobs/reports (separate backend) | **API Scan**, **API Reports** |
| Secure | Local users and roles, LDAP/AD, OIDC SSO, audit | **Admin → Users**, **Authentication**, **Audit Log** |
| Operate | H2/PostgreSQL/MySQL/MariaDB, mounted `clamd.conf` observer, Prometheus/Grafana | **Configuration**, **Monitoring & clients** |
| Clients | `clamctl` CLI, MCP stdio/loopback HTTP tools/resources | `cli/`, `docs/MCP.md` |
| Interface | Dark/light appearance; English, Romanian, Ukrainian, French, Spanish, German | Header and login |

## Quick start: new single-host installation

Copy [the standalone Compose example](examples/compose/standalone-h2.yaml) to a new directory as `docker-compose.yaml`, then:

```bash
mkdir -p conf data
# Place docker-compose.yaml here first.
docker compose config --quiet
docker compose up -d
docker compose logs --tail=100 clamav-web-client
docker compose exec -T clamav-web-client cat /app/data/bootstrap-admin-password
```

Open `http://127.0.0.1:8080/login`, sign in as `admin` using the **generated** password, change it in **Admin → Users**, then securely delete `data/bootstrap-admin-password`. No insecure standard admin password is configured. `127.0.0.1` binding is intentional: use a TLS reverse proxy for remote access, not an internet-exposed HTTP login.

This example uses **INSTREAM**: upload bytes pass to ClamD without requiring a shared file path. To show original file paths in native ClamdTop and scan `/scandir`, use the [shared paths override](examples/compose/shared-paths.override.yaml), identical absolute mounts, and the allowed scan roots policy. For a remote ClamD and existing PostgreSQL use [the remote deployment example](examples/compose/remote-clamd-postgres.yaml).

## Persistence and security essentials

- Preserve `./conf` (settings and encrypted identity key), `./data` (uploads, quarantine and default H2 database), and your external SQL backups together. Do **not** run `docker compose down -v` as an upgrade procedure.
- H2 is for **one** instance. PostgreSQL/MySQL/MariaDB are supported as database backends, **not** as multi-replica HA for this app. Flyway runs versioned migrations and Hibernate validates; **never enable auto-baselining for an unreviewed existing DB**.
- ClamD TCP 3310 is normally unauthenticated; never publish it to the internet. Browser authentication and API tokens do **not** secure ClamD's raw TCP port.
- The native ClamdTop page needs WebSocket proxy upgrades on `/ws/clamdtop`. External API Scan/Reports requires a separately deployed compatible REST scanner and `CLAMAV_REST_URL`; the built-in `/api/v1` is **not** that scanner.
- For the security.1 revision, see [upgrade/rebuild and verification](docs/SECURITY_DEPENDENCY_UPDATE.md). A source archive and a deployed registry image are not automatically identical.

## Common deployment paths

| Scenario | Example | Note |
|---|---|---|
| New H2 with bundled ClamD | [`standalone-h2.yaml`](examples/compose/standalone-h2.yaml) | Safe initial setup; loopback UI, INSTREAM |
| Remote ClamD + external PostgreSQL | [`remote-clamd-postgres.yaml`](examples/compose/remote-clamd-postgres.yaml) | Create DB beforehand; private `.env` |
| Native path scans and watched directories | [`shared-paths.override.yaml`](examples/compose/shared-paths.override.yaml) | Shared absolute mount paths, approved roots |
| Environment file example | [`.env.example`](examples/compose/.env.example) | **Never commit the populated `.env`** |

Full instructions, every administration field, optional LDAP/OIDC, PostgreSQL migration, Prometheus, Grafana, API, CLI/MCP examples, failure diagnostics, deployment diagrams and security checklist: **[Complete User Guide](docs/COMPLETE_USER_GUIDE.md)**.

## Build from this source

```bash
mvn -B -ntp verify                       # Java 17+ and Maven, when installed
docker build --pull -t clamav-web-client:2.0.0-security.1 .
# To use the local image, replace image: in your deployment Compose with
# clamav-web-client:2.0.0-security.1 and set pull_policy: never.
```

The Dockerfile builds with Maven/Java 21 and includes the native `clamdtop` binary. For development, the repository root `docker-compose.yml` is a **local-build** Compose, whereas the examples above use the **published image**. See the [Docker build guide](docs/DOCKER_BUILD.md) and [security revision details](docs/SECURITY_DEPENDENCY_UPDATE.md). `cli/dist` ships standalone binaries in this source archive; on a plain Git clone, build or obtain appropriate release artifacts separately.

## Source and support

The project is published at [github.com/Kosztyk/clamv-webui](https://github.com/Kosztyk/clamv-webui). For problems, attach sanitized container logs, the app version/image digest, database engine, ClamD connection mode and the steps to reproduce. **Redact** credentials, authorization headers, LDAP bind details, webhook URLs, tokens and file contents. Historical technical documents remain under `docs/`; the single [Complete User Guide](docs/COMPLETE_USER_GUIDE.md) is the current user entry point.
