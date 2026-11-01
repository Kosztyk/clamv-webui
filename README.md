# ClamAV Web UI

**Complete application source with Configuration Center and issues #10, #11 and #12 built in:** build normally:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

**Configuration Center:** configure connections, scanning, identity/API, notifications, network, monitoring and preferences from the GUI. Open `/setup/` directly on the published HTTP port; nginx, a domain and a certificate are optional. Confirm HTTP configuration on a trusted network before submitting credentials. Compose overrides remain supported and read-only; protected setup/recovery works before the database starts. See [setup, minimal Compose and recovery](docs/CONFIGURATION_CENTER.md), the [complete field reference](docs/CONFIGURATION_REFERENCE.md) and [validation](docs/CONFIGURATION_CENTER_VALIDATION.md).

**One settings editor:** duplicate Settings/Authentication forms and monitoring/observer controls are consolidated in Configuration Center. Existing values and credentials are preserved, and retired writes cannot bypass review. Scan Jobs now updates final status automatically. See [the consolidation audit and tests](docs/CONFIGURATION_CONSOLIDATION.md).

**Complete configuration audit:** all 107 controls have persistence, deployment-override and activation checks. Runtime/browser tests cover the non-identity settings too. See [every field, its consumer and validation evidence](docs/CONFIGURATION_FIELD_AUDIT.md).

**Database connection tests:** PostgreSQL/MySQL/MariaDB tests now use a bounded budget based on the configured pool timeout, with driver-specific units and useful, secret-safe failures. A new H2 database shows an informational initialization message rather than a false connection error. See [database test behavior and regressions](docs/DATABASE_CONNECTION_TESTS.md).

The Dockerfile runs the application as **UID/GID `10001:10001`** (issue #10),
and `application.properties` defaults to **`framework` forwarded-header
handling** (issue #11). Bearer authentication recognizes HTTPS terminated by
a reverse proxy or Kubernetes Ingress without a helper script or opt-in flag.
For proxy deployments, keep the backend reachable only through the trusted
proxy, which must replace forwarded headers. Direct GUI deployments can ignore
forwarded headers by selecting `none` under Network. See [server deployment](SERVER_DEPLOYMENT.md),
[volume permissions](docs/NON_ROOT_CONTAINER.md) and
[proxy configuration](docs/REVERSE_PROXY_TLS.md).

**Webhook resilience (issue #12):** delivery runs on a dedicated bounded queue
with finite connect/read/DNS/total timeouts. Private destinations are denied by
default; operators can explicitly allow internal receivers. See
[webhook policy and deployment settings](docs/WEBHOOK_RESILIENCE.md).

For the complete upgrade procedure, local/self-signed HTTPS checks and expected
test results, follow [Update and verify](docs/UPDATE_AND_VERIFY.md). See also
the [31 October update notes](docs/UPDATE_NOTES_2026_10_31.md) and
[reported field validation](docs/FIELD_VALIDATION.md).

**Self-hosted ClamAV management, scanning, monitoring and integrations.** Manage ClamD from a browser: upload and server-path scans, asynchronous jobs, quarantine, watched directories, audit history, native interactive ClamdTop, a separate REST scanner bridge, local/LDAP/OIDC sign-in, scoped API tokens, Prometheus/Grafana, CLI and MCP.

**Application version:** 2.1.0. See [v2.1.0 release notes](docs/RELEASE_NOTES_V2_1_0.md). **Registry image used in older examples:** [`kosztyk/clamav-web-client:v2`](https://hub.docker.com/r/kosztyk/clamav-web-client).

## Configure the app with Configuration Center

Use this method to keep application settings in the GUI. The minimum Compose starts the Web UI with persistent configuration and data; it does not start a ClamD daemon or provision an external database. Connect an existing ClamD in setup, or use the bundled-scanner example linked below. No nginx, public DNS name, certificate or build helper is required for direct GUI setup.

### Minimum docker-compose.yaml

For a **fresh installation**, copy [docker-compose.minimal.yaml](docker-compose.minimal.yaml) to `docker-compose.yaml`, or save the following file:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:2.1.0-local
    pull_policy: never
    container_name: clamav-web-client
    ports:
      - "8080:8080"
    volumes:
      - clamav-conf:/app/conf
      - clamav-data:/app/data
    restart: unless-stopped

volumes:
  clamav-conf:
  clamav-data:
```

Build from the full source directory and start this exact file:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose -f docker-compose.yaml up -d
docker compose -f docker-compose.yaml logs --tail=100 clamav-web-client
```

The named volumes are initialized with the image's non-root ownership. No application environment fields or host-directory `chown` commands are needed for this fresh named-volume example. Keep these volumes when recreating the container. For an **existing deployment**, retain its original conf/data mounts and external database connection; changing mounts selects different storage. Never use `down -v` as an update procedure. The explicit `-f docker-compose.yaml` also avoids accidentally selecting the repository's separate development `docker-compose.yml`.

### Obtain the setup and recovery credential

After the container starts, run this command **on the Docker host that runs this container**:

```bash
docker exec clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

`clamav-web-client` is the `container_name` in the example above. If your container has a different name, substitute that name. Alternatively, address the Compose service, regardless of container name:

```bash
docker compose -f docker-compose.yaml exec -T clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

Open `http://YOUR_DOCKER_HOST:8080/setup/` in your browser; use `http://localhost:8080/setup/` when browsing on that Docker host. Paste the credential into **Recovery credential** and select **Unlock configuration**. This random owner credential is created by the application and retained in the conf volume. It is **not** an administrator password or API token. Keep it private; never include it in Git, screenshots or issue comments. A missing file usually means startup is still in progress or the conf volume cannot be initialized: inspect container logs and volume access. Do not invent a token file or read one from a different application instance.

### Complete first-run setup

1. For direct HTTP, select **Allow configuration over HTTP on this trusted network** before submitting credentials. This is a page-scoped confirmation; HTTP remains unencrypted. HTTPS can be configured separately.
2. Under **Connections**, enter your own ClamD hostname/port or mounted Unix socket. A hostname must be reachable from the Web UI container; `localhost` refers to that container. Choose built-in H2 for a new single-instance installation, or enter an existing PostgreSQL/MySQL/MariaDB connection. Selecting another database does not migrate existing records.
3. Enable the optional features you need and complete their fields. Disabled features stay collapsed. **Test connection** checks the current draft without saving it.
4. Select **Review changes**, inspect activation requirements, acknowledge any database change, and apply. Then select **Start application**. Wait for the main application to finish starting before signing in.
5. For a new database with no supplied bootstrap password, obtain the generated local administrator password:

   ```bash
   docker exec clamav-web-client sh -c 'cat /app/data/bootstrap-admin-password; echo'
   ```

   Sign in as `admin`, set your own password under **Users**, and remove the one-time bootstrap password file after verifying access. Existing databases retain their existing accounts; a recovery credential does not reset their login passwords.
6. After login, use **Administration → Configuration center** for all global settings. Live changes apply immediately. For fields marked **Restart required**, save and then run:

   ```bash
   docker compose -f docker-compose.yaml restart clamav-web-client
   ```

All 107 fields, their aliases and activation rules are documented in [the field reference](docs/CONFIGURATION_REFERENCE.md). See [the Configuration Center guide](docs/CONFIGURATION_CENTER.md) for recovery, encrypted backups, bind mounts, provider credentials and restart behavior. CLI/MCP API Bearer tokens still require HTTPS.

## Configure the app with docker-compose.yaml

Compose/environment configuration remains fully supported. Put only the settings you want Compose to control under the service's `environment` mapping. They override saved GUI values and are shown as **Deployment configuration**, read-only in Configuration Center. Explicit `false` and empty environment values are overrides too. The rest remains editable through the GUI.

[docker-compose.environment.yaml](docker-compose.environment.yaml) is a complete example with Compose-managed ClamD and SQL connections and GUI-managed remaining settings:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:2.1.0-local
    pull_policy: never
    container_name: clamav-web-client
    ports:
      - "8080:8080"
    environment:
      CLAMAV_HOST: "${CLAMAV_HOST:?Set the ClamD host in your private .env}"
      CLAMAV_PORT: "${CLAMAV_PORT:-3310}"
      CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "false"
      SPRING_DATASOURCE_URL: "${SPRING_DATASOURCE_URL:?Set the JDBC URL in your private .env}"
      SPRING_DATASOURCE_USERNAME: "${SPRING_DATASOURCE_USERNAME:?Set the database user in your private .env}"
      SPRING_DATASOURCE_PASSWORD: "${SPRING_DATASOURCE_PASSWORD:?Set the database password in your private .env}"
    volumes:
      - clamav-conf:/app/conf
      - clamav-data:/app/data
    restart: unless-stopped

volumes:
  clamav-conf:
  clamav-data:
```

For a fresh deployment, copy this file to `docker-compose.yaml`, copy [the placeholder environment example](examples/compose/.env.example) to a private `.env`, and replace its example connection values and password. The database and ClamD must already exist. On macOS/Linux, restrict the populated file with `chmod 600 .env`. Do not commit it. A Compose `.env` supplies interpolation values; it reaches the container only when referenced in `environment` or loaded with `env_file`.

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml up -d
```

Complete `/setup/` with the same recovery-token command above; deployment-controlled fields will already be filled and locked. For one-time baselining of a compatible legacy database, use the migration procedure below rather than applying baseline flags to every fresh install.

After editing Compose environment or mounts, **recreate** the service to load them:

```bash
docker compose -f docker-compose.yaml up -d --force-recreate clamav-web-client
```

A plain restart does not update an existing container's environment. To move an individual setting from Compose to GUI management, remove its environment entry, recreate the container, then edit it in Configuration Center. A previously saved GUI value can become effective once its deployment override is removed, so inspect the selected value before applying other changes. Mixing Compose-managed connections with GUI-managed optional features is supported. Host ports, image tags, networks, mounts, secret files and database provisioning remain deployment responsibilities.

**Important note for users upgrading from v1 to v2:**

If you're upgrading an existing installation and want to preserve your database, please review the database migration instructions before starting v2.

For compatible legacy databases that do not yet have Flyway migration history, a one-time migration may require these Docker Compose environment variables:

```yaml
SPRING_FLYWAY_BASELINE_ON_MIGRATE: "true"
SPRING_FLYWAY_BASELINE_VERSION: "1"
```

This applies to existing H2, PostgreSQL, MySQL, and MariaDB installations where manual Flyway baselining is appropriate. It is **not needed for fresh installations or databases already managed by Flyway**.

Always back up your database and the application's `conf` and `data` directories, verify the existing schema, and test the migration before upgrading production.

After successful migration, remove the baselining settings.

Full instructions: [Database Migration Guide](https://github.com/Kosztyk/clamv-webui/blob/main/docs/PHASE2_DATABASE_PORTABILITY.md).

> **Start here:** [**Complete User & Administrator Guide — all features, UI fields, configuration, Docker Compose, APIs, upgrades and troubleshooting**](docs/COMPLETE_USER_GUIDE.md). This is the canonical, single-document how-to, with a linked table of contents.

## What you can do

| Area | Functions | Where |
|---|---|---|
| Scan | Upload multiple files, scan approved server paths, native path scanning/INSTREAM | **Scan** |
| Track | Job history, status, detections, timestamps, quarantine evidence | **Jobs** |
| Observe | Endpoint dashboard, PING/VERSION/STATS, interactive native `clamdtop` | **Dashboard**, **Main**, **ClamdTop** |
| Automate | Watched folders, completion webhooks, scoped `/api/v1` tokens | **Admin → Watch Directories**, **Configuration center**, **API Tokens** |
| Integrate | Optional external REST scanner jobs/reports (separate backend) | **API Scan**, **API Reports** |
| Secure | Local users and roles, LDAP/AD, OIDC SSO, audit | **Admin → Users**, **Configuration center → Identity & API**, **Audit Log** |
| Operate | H2/PostgreSQL/MySQL/MariaDB, mounted `clamd.conf` observer, Prometheus/Grafana | **Configuration center**, **Daemon status & files**, **Monitoring & clients** |
| Clients | `clamctl` CLI, MCP stdio/loopback HTTP tools/resources | `cli/`, `docs/MCP.md` |
| Interface | Dark/light appearance; English, Romanian, Ukrainian, French, Spanish, German | Header and login |

## Built-in API, CLI and MCP

The Web UI includes an authenticated **`/api/v1` API** for managed ClamD scans, job history/progress, endpoint health and audit. It works with the normal managed scanner connection. Global API limits are configured under **Configuration Center → Identity & API**; individual credentials are issued and revoked under **API Tokens**.

Create a scoped token as an administrator, copy its one-time secret, and use your actual HTTPS Web UI address. Load the token privately into `CLAMAV_API_TOKEN` before running these examples:

```bash
WEBUI_URL='https://clamav.example.test'
curl --fail-with-body \
  -H "Authorization: Bearer ${CLAMAV_API_TOKEN:?Set CLAMAV_API_TOKEN privately first}" \
  "$WEBUI_URL/api/v1/health/ready"
curl --fail-with-body \
  -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  -F 'files=@./sample.txt' "$WEBUI_URL/api/v1/scans/uploads"
curl --fail-with-body \
  -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  "$WEBUI_URL/api/v1/jobs?page=0&size=25"
```

The examples need `health:read`, `scan:upload` and `jobs:read` scopes respectively. Upload acceptance returns **202** with `created` and `jobIds`; poll `GET /api/v1/jobs/{id}/progress` for completion and the final verdict. Other routes include approved path scans, endpoint lists, audit and `GET /api/v1/openapi.yaml`. The built-in upload field is lowercase **`files`**. API tokens require HTTPS; for a self-signed deployment, trust its CA (for example `curl --cacert /path/to/your-ca.crt`). The setup recovery credential and administrator password are separate credentials.

See [API routes, scopes and examples](docs/REST_API_DEPLOYMENT.md#built-in-api-quick-start), [the OpenAPI contract](src/main/resources/openapi/clamav-v1.yaml), [CLI](docs/CLI.md) and [MCP](docs/MCP.md). CLI/MCP use the built-in Web UI API.

## Optional external ClamAV REST API

A separate REST scanner can serve File Browser and the Web UI's **API Scan / API Reports** integration. Here is a sanitized deployment example using `kosztyk/clamav-rest-api:renovate`, an existing ClamD and a named temporary-upload volume. Save it as [examples/compose/clamav-rest-api.yaml](examples/compose/clamav-rest-api.yaml):

```yaml
# Optional REST service for an existing ClamD. This does not start a daemon.
# Set CLAMAV_HOST in a private .env; CLAMAV_PORT defaults to 3310.
# For a fresh Web UI + REST stack, combine with docker-compose.minimal.yaml.
services:
  clamav-rest-api:
    image: kosztyk/clamav-rest-api:renovate
    container_name: clamav-rest-api
    restart: unless-stopped
    environment:
      NODE_ENV: "production"
      APP_PORT: "3000"
      APP_FORM_KEY: "FILES"
      CLAMD_IP: "${CLAMAV_HOST:?Set the existing ClamD host in your private .env}"
      CLAMD_PORT: "${CLAMAV_PORT:-3310}"
      # Bytes per file: 262144000 = 250 MiB.
      APP_MAX_FILE_SIZE: "262144000"
      APP_MAX_FILES_NUMBER: "5"
      APP_TEMP_FILE_DIR: "/tmp/clamav-uploads"
      APP_UPLOAD_TIMEOUT: "0"
      APP_MAX_CONCURRENT_SCANS: "1"
      # Milliseconds: 3600000 = one hour.
      CLAMD_TIMEOUT: "3600000"
      APP_MORGAN_LOG_FORMAT: "combined"
    ports:
      - "${REST_API_BIND:-127.0.0.1}:3000:3000"
    volumes:
      - rest-api-uploads:/tmp/clamav-uploads

volumes:
  rest-api-uploads:
```

Set `CLAMAV_HOST` to your own reachable daemon host in a private `.env`; `CLAMAV_PORT` defaults to 3310. The example's REST port is published on loopback. Choose `REST_API_BIND` for clients on another host and configure the REST service's own access protection. The Web UI does not build or manage that separate image.

For a **fresh installation** with both containers on the same Docker network:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose --env-file .env -f docker-compose.minimal.yaml \
  -f examples/compose/clamav-rest-api.yaml config --quiet
docker compose --env-file .env -f docker-compose.minimal.yaml \
  -f examples/compose/clamav-rest-api.yaml up -d
```

Keep using that file pair for Compose operations. For an existing installation, add the REST service to its existing Compose and preserve its original volumes/database. Complete `/setup/` with the recovery credential shown above, or open **Configuration Center → Connections → External REST scanner** after login. Enable it, set URL **`http://clamav-rest-api:3000`**, form field **`FILES`**, request timeout **10 seconds**, upload timeout **3600 seconds** and report limit **5000**. Set credentials/authentication only as required by that REST server. Review/apply and restart when indicated.

Alternatively, add these client settings to the **Web UI service's** `environment` mapping:

```yaml
environment:
  CLAMAV_REST_ENABLED: "true"
  CLAMAV_REST_URL: "http://clamav-rest-api:3000"
  CLAMAV_REST_FORM_KEY: "FILES"
  CLAMAV_REST_TIMEOUT_SECONDS: "10"
  CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS: "3600"
  CLAMAV_REST_REPORT_MAX_JOBS: "5000"
```

Recreate the Web UI container after environment changes; these fields become deployment-controlled and read-only in Configuration Center. The URL above requires a shared Docker network; otherwise use the REST server's reachable address. Configuration Center manages the Web UI's connection to the REST service; the REST container's `APP_*` / `CLAMD_*` settings stay in its own Compose.

The bridge's default upload route is **`/api/v1/asyncscan`**, and its connection test calls **`/api/v1/stats`**. Full API Scan/Reports needs compatible async jobs, history and reporting endpoints. A basic synchronous-only API is insufficient. The external synchronous endpoint uses uppercase multipart **`FILES`**. The example labels its size correctly: **262144000 bytes = 250 MiB**. The additional `APP_TEMP_FILE_DIR`, `APP_UPLOAD_TIMEOUT` and `APP_MAX_CONCURRENT_SCANS` controls follow the selected image's contract and were not independently runtime-verified for that image.

Read [REST deployment, GUI/Compose mappings, endpoint compatibility, curl checks and upload limits](docs/REST_API_DEPLOYMENT.md). The built-in API and external REST service keep their own jobs, limits and authentication.

## Additional deployment examples

The [standalone H2 and bundled ClamD example](examples/compose/standalone-h2.yaml) includes a scanner service. Its Web UI is bound to loopback by default; complete `/setup/` using the owner credential above, then sign in after selecting **Start application**. For a separate scanner and PostgreSQL, use [the remote example](examples/compose/remote-clamd-postgres.yaml). For identical shared scan paths use [the shared-paths override](examples/compose/shared-paths.override.yaml). Prepare bind-mounted directories for UID/GID 10001 as explained in [non-root deployment](docs/NON_ROOT_CONTAINER.md).

## Persistence and security essentials

- The Web UI image runs as **UID/GID `10001:10001`**. Existing bind-mounted `conf` and `data` folders must be writable by that identity before upgrading. See [non-root deployment and permission migration](docs/NON_ROOT_CONTAINER.md), including Docker Desktop, named volumes, Unix sockets and Kubernetes examples.
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
docker build --pull -t clamav-web-client:2.1.0-local .
# To use the local image, replace image: in your deployment Compose with
# clamav-web-client:2.1.0-local and set pull_policy: never.
```

The Dockerfile builds with Maven/Java 21 and includes the native `clamdtop` binary. For development, the repository root `docker-compose.yml` is a **local-build** Compose, whereas the examples above use the **published image**. See the [Docker build guide](docs/DOCKER_BUILD.md) and [security revision details](docs/SECURITY_DEPENDENCY_UPDATE.md). The complete source includes `cli/`; generated platform binaries are separate build/release artifacts. Build or obtain the client executable for your platform as described in [the CLI guide](docs/CLI.md).

## Push this complete source to GitHub

The archive includes [publish-clamav-webui.sh](publish-clamav-webui.sh) and a SHA-256 manifest. The script verifies every source file, prepares an isolated clone, commits the complete source snapshot and pushes normally. It retains Git history and never force-pushes. If the default branch requires review, it publishes an update branch and opens a pull request. Git's pager is disabled, so file summaries do not pause at `:`.

```bash
bash publish-clamav-webui.sh /path/to/clamv-webui-v2.1.0-configuration-center-full-source.zip OWNER/REPOSITORY
```

Replace `OWNER/REPOSITORY` with the repository you intend to update. On macOS and Linux, the script uses an installed GitHub CLI or downloads a pinned official binary and verifies its SHA-256 digest. Downloaded CLI files use `~/Library/Caches/clamav-publisher` on macOS and the XDG/home cache on Linux, with a private temporary fallback when the cache is unwritable. The earlier macOS `~/.local/share` permission failure is fixed; replace the standalone script and rerun the same command with your existing ZIP. It prompts for browser authentication only if needed. `--check` validates the archive offline; `--dry-run` prepares the update without pushing. Read [the publishing guide](docs/PUBLISHING.md) for exact commands, required workflow permissions, protected branches and what the full-source update replaces. This publishes source only; Docker images and GitHub releases are separate operations.

## Source and support

The project is published at [github.com/Kosztyk/clamv-webui](https://github.com/Kosztyk/clamv-webui). For problems, attach sanitized container logs, the app version/image digest, database engine, ClamD connection mode and the steps to reproduce. **Redact** credentials, authorization headers, LDAP bind details, webhook URLs, tokens and file contents. Historical technical documents remain under `docs/`; the single [Complete User Guide](docs/COMPLETE_USER_GUIDE.md) is the current user entry point.
