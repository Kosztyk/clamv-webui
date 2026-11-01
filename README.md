# ClamAV Web UI

**A self-hosted console for malware scanning, ClamD monitoring and automation.**

Upload files, scan approved server paths, follow jobs through completion and manage your ClamAV connections from a browser. Add LDAP/OIDC sign-in, Prometheus/Grafana, webhooks, a CLI and MCP as needed.

**Source version: 2.1.0** · **HTTP API: `/api/v1`** · **Databases: H2, PostgreSQL, MySQL, MariaDB**

[Quick start](#quick-start) · [Configuration](#configuration) · [API & clients](#built-in-api-cli-and-mcp) · [Upgrades](#upgrades-and-database-migration) · [Troubleshooting](#troubleshooting) · [Complete guide](docs/COMPLETE_USER_GUIDE.md)

## Find what you need

| Your goal | Start here |
|---|---|
| Install with minimal Compose and configure through the GUI | [Quick start](#quick-start) |
| Keep settings in Compose, or combine Compose and GUI | [Configuration methods](#configuration) |
| Find the setup credential or first administrator password | [Setup and recovery credential](#obtain-the-setup-and-recovery-credential) · [First login](#first-administrator-login) |
| Scan from scripts, CLI or an MCP client | [Built-in API, CLI and MCP](#built-in-api-cli-and-mcp) |
| Connect File Browser or an external REST scanner | [External ClamAV REST API](#optional-external-clamav-rest-api) |
| Upgrade an existing installation without losing its data | [Upgrades and database migration](#upgrades-and-database-migration) |
| Publish the complete source to GitHub | [Publishing](#push-this-complete-source-to-github) |
| Look up a setting, feature or deployment procedure | [Documentation map](#documentation-map) |

<img width="1686" height="732" alt="Screenshot 2026-11-01 at 17 22 15" src="https://github.com/user-attachments/assets/7537bb09-0cc1-4859-80a7-05cb6c2b04bf" />


## What you can do

| Capability | Included features | Where to find it |
|---|---|---|
| **Scan files and paths** | Multiple uploads, approved server paths, native path scanning and INSTREAM | **Scan** |
| **Track results** | Asynchronous jobs, automatic final-status refresh, detections, timestamps and quarantine evidence | **Jobs** |
| **Observe ClamD** | Endpoint dashboard, PING/VERSION/STATS and native interactive `clamdtop` | **Dashboard**, **Main**, **ClamdTop** |
| **Automate work** | Watched directories, completion webhooks and scoped API tokens | **Watch Directories**, **Configuration center**, **API Tokens** |
| **Connect external scanning** | A separate REST scanner's jobs, reports and supported webhook features | **API Scan**, **API Reports** |
| **Manage access** | Local users and roles, LDAP/Active Directory, OIDC SSO and audit history | **Users**, **Configuration center → Identity & API**, **Audit Log** |
| **Operate the service** | H2/PostgreSQL/MySQL/MariaDB, mounted `clamd.conf` observer, Prometheus and Grafana | **Configuration center**, **Daemon status & files**, **Monitoring & clients** |
| **Use machine clients** | `clamctl` CLI, MCP stdio/loopback HTTP tools and resources | [`cli/`](cli/), [CLI guide](docs/CLI.md), [MCP guide](docs/MCP.md) |
| **Choose the interface** | Dark/light appearance; English, Romanian, Ukrainian, French, Spanish and German | Header and login |

## Quick start

This path is for a **fresh installation** managed through Configuration Center. Existing installations should follow [the upgrade procedure](#upgrades-and-database-migration) and keep their original storage and database connection.

You need Docker with Compose, the full application source and an existing ClamD reachable from the container. The minimum Compose starts the Web UI and its persistent storage. Use [the bundled ClamD example](examples/compose/standalone-h2.yaml) if you also need a scanner service. External databases must be provisioned separately.

### Minimum docker-compose.yaml

Copy [docker-compose.minimal.yaml](docker-compose.minimal.yaml) to `docker-compose.yaml`, or save this complete file:

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

There are **zero application environment fields** in this example. Docker initializes the named volumes with the image's non-root ownership; a fresh named-volume installation needs no host-directory `chown` commands.

From the full source directory, build and start:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose -f docker-compose.yaml up -d
docker compose -f docker-compose.yaml logs --tail=100 clamav-web-client
```

The fixes are included in the application code. No build helper, proxy opt-in flag, nginx, public DNS name or certificate is required for direct GUI setup. Explicit `-f docker-compose.yaml` selects your deployment file rather than the repository's separate development `docker-compose.yml`.

### Obtain the setup and recovery credential

Run this **on the Docker host running the container**:

```bash
docker exec clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

Open **`http://YOUR_DOCKER_HOST:8080/setup/`**, paste the value into **Recovery credential** and select **Unlock configuration**. When browsing on the Docker host itself, use `http://localhost:8080/setup/`. Protected setup and recovery are available before the database starts.

The command uses the example's `container_name`, `clamav-web-client`. Substitute your container's name if different. You can also address the Compose service regardless of container name:

```bash
docker compose -f docker-compose.yaml exec -T clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

The application generates this owner credential and retains it in the conf volume. Keep it private: do not put it in Git, screenshots or issue comments. If the file is missing, check startup progress, container logs and conf-volume access. Use the credential from this instance; do not create a token file manually.

### Complete first-run setup

1. **Confirm the transport.** For direct HTTP, select **Allow configuration over HTTP on this trusted network** before submitting credentials. This confirmation lasts for the page; HTTP remains unencrypted. HTTPS can be added separately.
2. **Connect ClamD.** Under **Connections**, enter your daemon's reachable hostname/port or mounted Unix socket. A container's `localhost` refers to that container.
3. **Choose the database.** Use built-in H2 for a new single-instance installation, or supply an existing PostgreSQL/MySQL/MariaDB connection. Selecting a different database does not migrate existing records.
4. **Enable what you need.** Complete the enabled feature's fields; disabled features stay collapsed. **Test connection** checks the draft and saves nothing. A new H2 database reports that it is not initialized; review/apply/start creates it, and a later test verifies its credentials. See [database test behavior](docs/DATABASE_CONNECTION_TESTS.md).
5. **Review and start.** Select **Review changes**, inspect activation requirements, acknowledge any database change and apply. Select **Start application**, then wait for startup to finish before signing in.

### First administrator login

For a new database without a supplied bootstrap password, read the generated password:

```bash
docker exec clamav-web-client sh -c 'cat /app/data/bootstrap-admin-password; echo'
```

Sign in as **`admin`**, set your own password under **Users**, verify access and remove the one-time bootstrap password file. Existing databases retain their accounts; a recovery credential does not reset their passwords.

| Credential | Purpose | How to obtain it |
|---|---|---|
| **Setup/recovery credential** | Unlock this instance's `/setup/` | `/app/conf/configuration-recovery-token`, using the command above |
| **Administrator password** | Normal local sign-in and administrator verification | Existing account password, or the generated bootstrap file for a new database |
| **API token** | Scoped machine API access over HTTPS | Issue it under **API Tokens** and copy the one-time secret |

## Configuration

Choose GUI management, deployment management or a combination. The application supports all three.

| Method | Values are supplied through | What Configuration Center shows | How changes activate |
|---|---|---|---|
| **GUI** | Reviewed changes in Configuration Center | Editable fields with their current source and activation requirement | Live where supported; restart for marked fields |
| **Compose** | The service's `environment` and other deployment inputs | Authoritative deployment values, read-only | Recreate the container after changing its environment or mounts |
| **Hybrid** | Compose for selected settings, GUI for the rest | Deployment locks only on explicitly supplied values | Follow the relevant rule for each setting |

### Configure the app with Configuration Center

After login, open **Administration → Configuration center**. This is the single editor for global application settings:

| Category | Settings you will find there |
|---|---|
| **Connections** | Managed ClamD, optional REST scanner, database and persistence |
| **Scanning** | Scan policy, uploads, storage, quarantine and watcher behavior |
| **Identity & API** | Local recovery links, LDAP/AD, OIDC, roles, scopes and API limits |
| **Notifications** | Webhook destination, delivery options and address policy |
| **Network** | Reverse-proxy handling, session security and private CA trust |
| **Monitoring** | Prometheus/Grafana and the mounted daemon-configuration observer |
| **System** | Preferences and recovery controls |

Make changes, test available connections, review and apply. **Live** fields apply immediately. For **Restart required** fields, save first and then run:

```bash
docker compose -f docker-compose.yaml restart clamav-web-client
```

The old Settings/Authentication forms and duplicate monitoring/observer controls have been consolidated here. Existing values and credentials are retained; retired writes cannot bypass review. Users, API tokens, additional endpoints and watched-directory records remain operational resources on their own management pages.

All **107 controls** have persistence, deployment-override and activation checks, including non-identity runtime/browser coverage. See [the Configuration Center guide](docs/CONFIGURATION_CENTER.md), [all fields and aliases](docs/CONFIGURATION_REFERENCE.md), [the consolidation audit](docs/CONFIGURATION_CONSOLIDATION.md) and [per-field evidence](docs/CONFIGURATION_FIELD_AUDIT.md). The guide also covers encrypted backups, recovery, bind mounts and provider credentials.

### Configure the app with docker-compose.yaml

Use [docker-compose.environment.yaml](docker-compose.environment.yaml) when Compose should manage the core ClamD/SQL connections and the GUI should manage remaining settings.

<details>
<summary><strong>View the complete Compose-managed connection example</strong></summary>

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

</details>

For a fresh deployment:

1. Copy that file to `docker-compose.yaml`.
2. Copy [the placeholder environment file](examples/compose/.env.example) to a private `.env` and supply your own connection values and password. ClamD and the database must already exist.
3. On macOS/Linux, restrict the populated file with `chmod 600 .env` and keep it out of Git.
4. Build, validate the Compose and start:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose -f docker-compose.yaml config --quiet
docker compose -f docker-compose.yaml up -d
```

Complete `/setup/` using the same recovery-credential command. Deployment-controlled fields will be prefilled and locked. A Compose `.env` supplies interpolation values; they reach the container only through `environment` references or `env_file`.

**Precedence and updates:**

- Explicit deployment values override saved GUI values, including **`false` and empty strings**. Other fields stay editable.
- A plain restart does not load changed container environment or mounts. Recreate the service:

  ```bash
  docker compose -f docker-compose.yaml up -d --force-recreate clamav-web-client
  ```

- To return a setting to GUI management, remove its environment entry, recreate the container and inspect its effective value. A previously saved GUI value may become active again.
- Host ports, image tags, networks, mounts, secret files and database provisioning remain deployment responsibilities.
- Use the reviewed [migration procedure](#upgrades-and-database-migration) for compatible legacy databases; baseline flags are not fresh-install defaults.

## Built-in API, CLI and MCP

The authenticated **`/api/v1` API** uses the managed ClamD connection and the Web UI's Scan Jobs database. Configure global limits under **Configuration Center → Identity & API**; issue and revoke individual credentials under **API Tokens**.

Create a scoped token as an administrator, copy its one-time secret and load it privately into `CLAMAV_API_TOKEN`. Replace the example address with your actual **HTTPS** Web UI address:

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

| Example | Required scope | Result |
|---|---|---|
| Database readiness | `health:read` | Database availability |
| File upload | `scan:upload` | **202** with `created` and `jobIds` |
| Job listing | `jobs:read` | Paged managed jobs |

Poll `GET /api/v1/jobs/{id}/progress` for completion and the final verdict. The built-in multipart upload field is lowercase **`files`**. Approved path scans, endpoint lists, audit and `GET /api/v1/openapi.yaml` are also available.

API Bearer tokens require HTTPS, including CLI/MCP use. For a self-signed deployment, trust its CA, for example with `curl --cacert /path/to/your-ca.crt`. The GUI's HTTP confirmation does not change API token transport requirements.

- [API routes, scopes and examples](docs/REST_API_DEPLOYMENT.md#built-in-api-quick-start)
- [OpenAPI contract](src/main/resources/openapi/clamav-v1.yaml)
- [CLI: `clamctl`](docs/CLI.md) and [MCP tools/resources](docs/MCP.md), both using the built-in API

## Optional external ClamAV REST API

The external REST service serves clients such as File Browser and the Web UI's **API Scan / API Reports** pages. Its jobs, limits and authentication belong to that service. The built-in API works independently of this integration.

| Connection | Configuration location | Typical upload field |
|---|---|---|
| Built-in Web UI `/api/v1` | Managed ClamD + **Identity & API** + **API Tokens** | `files` |
| External REST scanner | REST container's Compose + **Connections → External REST scanner** for the Web UI client | `FILES` |

<details>
<summary><strong>View the complete external REST service Compose</strong></summary>

Save this as [examples/compose/clamav-rest-api.yaml](examples/compose/clamav-rest-api.yaml). It uses the supplied public image, an existing ClamD and a named temporary-upload volume.

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

Set your reachable daemon host as `CLAMAV_HOST` in a private `.env`; `CLAMAV_PORT` defaults to 3310. The published REST port defaults to loopback. Choose `REST_API_BIND` and the REST service's own access protection for clients on another host. The Web UI does not build or manage the separate REST image.

</details>

### Start and connect the REST service

For a **fresh installation**, build the Web UI and start both services on the same Docker network:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose --env-file .env -f docker-compose.minimal.yaml \
  -f examples/compose/clamav-rest-api.yaml config --quiet
docker compose --env-file .env -f docker-compose.minimal.yaml \
  -f examples/compose/clamav-rest-api.yaml up -d
```

Keep this file pair for later Compose operations. On an existing deployment, add the REST service to its existing Compose while keeping its original volumes and database. Finish `/setup/` using the owner credential, or open **Configuration Center → Connections → External REST scanner** after login.

| Web UI client setting | Same-network example |
|---|---|
| Enable the external REST scanner | Enabled |
| URL | `http://clamav-rest-api:3000` |
| Upload form field | `FILES` |
| Request timeout | **10 seconds** |
| Upload timeout | **3600 seconds** |
| Report limit | **5000** jobs |
| Credentials and authentication | Match the external server's requirements |

Review/apply and restart when indicated. The service-name URL requires a shared Docker network; otherwise use the server's reachable address.

<details>
<summary><strong>Use Compose instead of the GUI for the Web UI's REST client</strong></summary>

Add this mapping to the **Web UI service's** environment:

```yaml
environment:
  CLAMAV_REST_ENABLED: "true"
  CLAMAV_REST_URL: "http://clamav-rest-api:3000"
  CLAMAV_REST_FORM_KEY: "FILES"
  CLAMAV_REST_TIMEOUT_SECONDS: "10"
  CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS: "3600"
  CLAMAV_REST_REPORT_MAX_JOBS: "5000"
```

Recreate the Web UI container to activate environment changes. These fields become read-only deployment values in Configuration Center. The GUI controls the Web UI's REST client; the external container's own `APP_*` / `CLAMD_*` settings stay in its Compose.

</details>

### Check compatibility and limits

- The default bridge upload route is **`/api/v1/asyncscan`**; **Test connection** calls **`/api/v1/stats`**.
- Full API Scan/Reports requires compatible async jobs, history and reporting endpoints. A synchronous-only API is insufficient; its upload endpoint uses uppercase **`FILES`**.
- The example's limit is **262144000 bytes = 250 MiB**. Web UI, proxy, REST, ClamD and storage limits must permit the intended upload size.
- `APP_TEMP_FILE_DIR`, `APP_UPLOAD_TIMEOUT` and `APP_MAX_CONCURRENT_SCANS` follow the chosen image's contract. Their runtime behavior was not independently verified for that separate image.

See [REST deployment, GUI/Compose mappings, endpoint compatibility, curl checks and upload limits](docs/REST_API_DEPLOYMENT.md).

<a name="additional-deployment-examples"></a>
<a name="common-deployment-paths"></a>

## Deployment examples

| Scenario | Example | What to know |
|---|---|---|
| Fresh, GUI-managed Web UI | [Minimum Compose](docker-compose.minimal.yaml) | Existing ClamD; no application environment fields |
| Compose-managed connections | [Environment-managed Compose](docker-compose.environment.yaml) | Existing ClamD/SQL; remaining settings can stay in the GUI |
| H2 and a bundled scanner | [Standalone H2 + ClamD](examples/compose/standalone-h2.yaml) | Loopback UI by default; INSTREAM; finish `/setup/` and select **Start application** before login |
| Remote ClamD and PostgreSQL | [Remote connection example](examples/compose/remote-clamd-postgres.yaml) | Create the database first; private `.env` |
| Native path scans and watched directories | [Shared-paths override](examples/compose/shared-paths.override.yaml) | Identical approved absolute paths in Web UI and ClamD |
| Optional external REST service | [REST service Compose](examples/compose/clamav-rest-api.yaml) | Compatible separate backend; own queue/history and limits |
| Private deployment inputs | [Placeholder `.env.example`](examples/compose/.env.example) | Replace placeholders; never commit the populated file |

Prepare bind-mounted paths for **UID/GID 10001** as described in [non-root deployment](docs/NON_ROOT_CONTAINER.md).

## Persistence and security essentials

### Keep storage and permissions consistent

- The Web UI runs as **UID/GID `10001:10001`**. Existing bind-mounted `conf` and `data` folders must be writable by that identity. The [non-root guide](docs/NON_ROOT_CONTAINER.md) covers permission migration, Docker Desktop, named volumes, Unix sockets and Kubernetes.
- Back up **conf, data and external SQL together**. Conf contains settings and encryption/identity keys; data contains uploads, quarantine and the default H2 database.
- Retain the original volumes and database connection across container recreation. Changing mounts selects different storage; do not use `docker compose down -v` to upgrade.
- H2 supports **one instance**. PostgreSQL/MySQL/MariaDB provide database portability; multi-replica HA is unsupported for this application. Flyway runs migrations and Hibernate validates the schema.

### Protect service boundaries

- ClamD's raw TCP port **3310** is normally unauthenticated. Keep it off the internet; Web UI sign-in and tokens do not secure that port.
- The packaged `server.forward-headers-strategy` in `src/main/resources/application.properties` defaults to **`framework`**. A trusted proxy/Ingress can terminate HTTPS for API Bearer requests without a helper flag. Restrict backend access to the trusted proxy, which must replace forwarded headers.
- Direct deployments can select **`none`** under Network to ignore forwarded headers. Configure HTTPS separately for API/CLI/MCP Bearer use. See [proxy/TLS guidance](docs/REVERSE_PROXY_TLS.md) and [server deployment](SERVER_DEPLOYMENT.md).
- Native ClamdTop needs WebSocket upgrades on **`/ws/clamdtop`** through a reverse proxy.
- Webhooks run on a dedicated bounded queue with finite connect/read/DNS/total timeouts. Private destinations are denied by default; operators can explicitly allow internal receivers. See [webhook policy](docs/WEBHOOK_RESILIENCE.md).

## Upgrades and database migration

For an existing installation, preserve its conf/data mounts, matching encryption keys and external database connection. A source archive and a deployed registry image are not automatically identical. Follow [Update and verify](docs/UPDATE_AND_VERIFY.md) and [security revision upgrade/rebuild guidance](docs/SECURITY_DEPENDENCY_UPDATE.md).

**Upgrading from v1 to v2:** back up the database and conf/data, inspect the existing schema and test the migration before starting against production. A compatible legacy database without Flyway history may require this one-time configuration:

```yaml
SPRING_FLYWAY_BASELINE_ON_MIGRATE: "true"
SPRING_FLYWAY_BASELINE_VERSION: "1"
```

This applies to **H2, PostgreSQL, MySQL and MariaDB** where manual baselining is appropriate. It is **unnecessary for fresh databases or databases already managed by Flyway**. Never enable auto-baselining for an unreviewed schema; remove the baseline settings after successful migration.

Read the [Database Migration Guide](https://github.com/Kosztyk/clamv-webui/blob/main/docs/PHASE2_DATABASE_PORTABILITY.md) before enabling these flags.

## Build from this source

```bash
mvn -B -ntp verify                       # Java 17+ and Maven, when installed
docker build --pull -t clamav-web-client:2.1.0-local .
# To use the local image, replace image: in your deployment Compose with
# clamav-web-client:2.1.0-local and set pull_policy: never.
```

The Dockerfile builds with Maven/Java **21** and includes native `clamdtop`. Standalone Maven builds need Java **17+** and Maven. The minimum and environment-managed Compose files above use the local image you build. The root `docker-compose.yml` is a separate local-build development Compose; other deployment examples may reference a published image, so check their image selection before use.

Older examples use [`kosztyk/clamav-web-client:v2`](https://hub.docker.com/r/kosztyk/clamav-web-client). The complete source includes `cli/`; generated client binaries are separate build/release artifacts. Build or obtain the executable for your platform using [the CLI guide](docs/CLI.md). See [Docker build details](docs/DOCKER_BUILD.md).

## Included in this source edition

| Area | Behavior and evidence |
|---|---|
| Container and TLS fixes | Non-root runtime **#10** and proxy TLS recognition **#11** are built into the normal image |
| Webhook resilience | Dedicated bounded delivery, timeouts and private-address safeguards **#12** |
| Configuration consolidation | One global editor, retained values/credentials and reviewed writes; automatic final Scan Jobs refresh |
| Complete field audit | All **107** controls checked for persistence, deployment precedence, activation and runtime consumers, including non-identity settings |
| Database connection tests | PostgreSQL/MySQL/MariaDB use a bounded pool-based budget with driver-specific timeout units and secret-safe errors; new H2 gets an initialization explanation |

See [v2.1.0 release notes](docs/RELEASE_NOTES_V2_1_0.md), [validation results and limits](docs/CONFIGURATION_CENTER_VALIDATION.md), [31 October update notes](docs/UPDATE_NOTES_2026_10_31.md) and [reported field validation](docs/FIELD_VALIDATION.md).

## Push this complete source to GitHub

The archive contains [publish-clamav-webui.sh](publish-clamav-webui.sh) and a SHA-256 manifest. The publisher checks every source file, prepares an isolated clone and commits the complete tracked snapshot. It retains history and never force-pushes. A protected default branch gets an update branch and pull request. Git's pager is disabled, so summaries do not stop at `:`.

```bash
bash publish-clamav-webui.sh /path/to/clamv-webui-v2.1.0-configuration-center-full-source.zip OWNER/REPOSITORY
```

Replace `OWNER/REPOSITORY` with the intended destination. The script uses an installed GitHub CLI or downloads a pinned official binary and verifies its SHA-256. Browser authentication is requested only when needed.

| Publisher option | Behavior |
|---|---|
| `--check` | Validate the archive offline |
| `--dry-run` | Prepare the update without pushing |
| Default command | Push normally, with a branch/PR fallback when required |

Downloaded CLI files use `~/Library/Caches/clamav-publisher` on macOS and the XDG/home cache on Linux, with a private temporary fallback. The earlier macOS `~/.local/share` permission failure is fixed: replace the standalone script and rerun with the existing ZIP. No ownership change is needed for that cache error.

For exact commands, workflow permissions, protected branches and snapshot replacement behavior, use [the publishing guide](docs/PUBLISHING.md). Source publication, GitHub releases and Docker image publication are separate operations.

## Troubleshooting

| Symptom | First check |
|---|---|
| Setup credential file is missing | Container startup logs and conf-volume access; use the correct instance/container |
| Setup unlock succeeds but normal login fails | Use the administrator password; the recovery credential unlocks `/setup/` |
| A field is read-only | Inspect its deployment override, including explicit empty/false values; see [configuration precedence](#configuration) |
| A changed environment value has no effect | Recreate the container; restart alone keeps its existing environment |
| A fresh H2 connection is not verified | Initialize through review/apply/start, then retest; see [database connection tests](docs/DATABASE_CONNECTION_TESTS.md) |
| API Bearer requests need HTTPS | Check the actual HTTPS route and trusted forwarded-header configuration |
| API Scan/Reports cannot use the backend | Verify the external server's async/stats/jobs contract and shared-network URL in [REST deployment](docs/REST_API_DEPLOYMENT.md) |
| The Mac publisher cannot write its cache | Replace the script with the current version; see [publisher recovery](docs/PUBLISHING.md) |

## Documentation map

The **[Complete User & Administrator Guide](docs/COMPLETE_USER_GUIDE.md)** is the canonical single-document how-to for every feature and administration field, deployment, APIs, upgrades, diagrams, failure diagnostics and security guidance.

| Topic | Guide |
|---|---|
| Setup, recovery, encrypted backups and restarts | [Configuration Center](docs/CONFIGURATION_CENTER.md) |
| Every field, environment alias and activation rule | [Configuration reference](docs/CONFIGURATION_REFERENCE.md) |
| Field wiring, persistence and runtime evidence | [107-field audit](docs/CONFIGURATION_FIELD_AUDIT.md) |
| Retired forms and retained settings | [Consolidation audit](docs/CONFIGURATION_CONSOLIDATION.md) |
| Database probe results and first-run H2 | [Database connection tests](docs/DATABASE_CONNECTION_TESTS.md) |
| Complete server installation | [Server deployment](SERVER_DEPLOYMENT.md) |
| Existing installation update checks | [Update and verify](docs/UPDATE_AND_VERIFY.md) |
| Non-root ownership and mounted paths | [Container permissions](docs/NON_ROOT_CONTAINER.md) |
| TLS termination, self-signed HTTPS and token transport | [Reverse proxy and TLS](docs/REVERSE_PROXY_TLS.md) |
| Webhook delivery and address policy | [Webhook resilience](docs/WEBHOOK_RESILIENCE.md) |
| Built-in API and the external scanner | [API and REST deployment](docs/REST_API_DEPLOYMENT.md) |
| Container build and dependency security revision | [Docker build](docs/DOCKER_BUILD.md) · [Security revision](docs/SECURITY_DEPENDENCY_UPDATE.md) |
| GitHub publishing and authentication | [Publishing guide](docs/PUBLISHING.md) |
| Changes and validation scope | [Release notes](docs/RELEASE_NOTES_V2_1_0.md) · [Validation report](docs/CONFIGURATION_CENTER_VALIDATION.md) |

## Source and support

Project: [github.com/Kosztyk/clamv-webui](https://github.com/Kosztyk/clamv-webui).

For support, include sanitized container logs, the app version/image digest, database engine, ClamD connection mode and reproduction steps. Redact credentials, authorization headers, LDAP bind details, webhook URLs, tokens and file contents. Historical technical documents remain under `docs/`; use the Complete User Guide and current deployment guides for the present behavior.
