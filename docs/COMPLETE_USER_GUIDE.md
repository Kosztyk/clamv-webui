# ClamAV Web UI 2.0.0 — Complete User, Administrator & Deployment Guide

**Documentation edition:** 31 October 2026

**Source edition:** application 2.0.0 with the security dependency updates and issues #10, #11 and #12

**Example registry image:** `kosztyk/clamav-web-client:v2`  
**Current local build tag:** `clamav-web-client:2.0.0-local`

This is the **single, consolidated how-to** for people deploying, administering or using ClamAV Web UI. It covers all available browser pages and fields, deployment alternatives, environment variables and persisted settings, authentication, database and scan modes, native ClamdTop, the *separate* REST bridge, machine APIs, CLI, MCP, monitoring, upgrade and troubleshooting. Where the source explicitly leaves a feature unsupported, the limitation is documented rather than presented as an option.

> **Image provenance:** The current source includes Spring Boot 4.0.9, dependency security updates, the non-root runtime, default reverse-proxy HTTPS recognition and webhook resilience. Updating this repository does not publish a Docker Hub image. Verify the image digest before assuming the example `v2` tag includes these changes, or build the current source normally with `docker build --pull -t clamav-web-client:2.0.0-local .`.

For the current upgrade procedure and expected runtime test results, including
LAN access with a self-signed certificate, see [Update and verify](UPDATE_AND_VERIFY.md).
The [update notes](UPDATE_NOTES_2026_10_31.md) summarize defaults and compatibility;
[field validation](FIELD_VALIDATION.md) distinguishes reported deployments from
automated test coverage.

## Table of contents

<!-- TOC START -->
1. [Table of contents](#table-of-contents)
2. [1. Architecture, capabilities and limitations](#1-architecture-capabilities-and-limitations)
3. [2. Installation choices and prerequisites](#2-installation-choices-and-prerequisites)
4. [3. Complete Compose A — new install, H2 and bundled ClamD](#3-complete-compose-a-new-install-h2-and-bundled-clamd)
5. [4. Complete Compose B — remote ClamD + existing PostgreSQL + optional REST bridge](#4-complete-compose-b-remote-clamd-existing-postgresql-optional-rest-bridge)
6. [5. Native path scans, uploads, ClamdTop filenames and watched folders](#5-native-path-scans-uploads-clamdtop-filenames-and-watched-folders)
7. [6. Build, verify or publish the current source](#6-build-verify-or-publish-the-current-source)
8. [7. First login, session and local user management](#7-first-login-session-and-local-user-management)
9. [8. Dashboard, Main and native ClamdTop](#8-dashboard-main-and-native-clamdtop)
10. [9. Scan page — every field and job behavior](#9-scan-page-every-field-and-job-behavior)
11. [10. Jobs, history, verdicts, retention and quarantine](#10-jobs-history-verdicts-retention-and-quarantine)
12. [11. Administration → Settings — complete field reference](#11-administration-settings-complete-field-reference)
13. [12. Administration → Endpoints — field-by-field](#12-administration-endpoints-field-by-field)
14. [13. API Scan and API Reports — optional **external** REST scanner](#13-api-scan-and-api-reports-optional-external-rest-scanner)
15. [14. Watch Directories — setup, controls and operating limits](#14-watch-directories-setup-controls-and-operating-limits)
16. [15. Authentication Management — provider tests, saves and recovery](#15-authentication-management-provider-tests-saves-and-recovery)
17. [16. API Tokens, `/api/v1`, legacy API, scope and curl examples](#16-api-tokens-apiv1-legacy-api-scope-and-curl-examples)
18. [17. Audit Log and accountability](#17-audit-log-and-accountability)
19. [18. Monitoring & clients — Prometheus, Grafana, alerting, downloads](#18-monitoring-clients-prometheus-grafana-alerting-downloads)
20. [19. Configuration — mounted clamd.conf / freshclam.conf inspection & inheritance](#19-configuration-mounted-clamdconf-freshclamconf-inspection-inheritance)
21. [20. Exhaustive deployment & configuration variable reference](#20-exhaustive-deployment-configuration-variable-reference)
22. [21. SQL migrations, upgrading, data integrity and PostgreSQL legacy OID](#21-sql-migrations-upgrading-data-integrity-and-postgresql-legacy-oid)
23. [22. Reverse proxy, certificates, secrets and network security](#22-reverse-proxy-certificates-secrets-and-network-security)
24. [23. `clamctl` CLI — installation, commands and exit codes](#23-clamctl-cli-installation-commands-and-exit-codes)
25. [24. MCP — assistant-facing tools, resources and deployment](#24-mcp-assistant-facing-tools-resources-and-deployment)
26. [25. End-to-end verification and EICAR detection](#25-end-to-end-verification-and-eicar-detection)
27. [26. Troubleshooting by symptom](#26-troubleshooting-by-symptom)
28. [27. Operational security checklist](#27-operational-security-checklist)
29. [28. Further reference inside the repository](#28-further-reference-inside-the-repository)
<!-- TOC END -->

---

## 1. Architecture, capabilities and limitations

ClamAV Web UI is a **Spring Boot/Thymeleaf Java application**. It controls one or more ClamAV `clamd` daemons (TCP or supported Unix socket), stores users/jobs/audit and endpoint records in SQL, and persists application policies and secrets in `conf`. An independent `clamctl` executable implements command-line and MCP workflows. Optional Grafana/Prometheus services are external. Optional **API Scan / API Reports** talk to a *different* compatible REST scanning service, not to the built-in `/api/v1` endpoints.

```text
Browser / HTTPS proxy  ---->  ClamAV Web UI :8080
                                  |  |  |\
                                  |  |  | +-- REST bridge -> optional external REST scanner
                                  |  |  +----> SQL (H2 file / PostgreSQL / MySQL / MariaDB)
                                  |  +------> conf + data mounts (policy, key, uploads, quarantine)
                                  +---------> clamd :3310 (private TCP or Unix socket)
                                              ^
                                              | same absolute file paths REQUIRED for native path scans
clamctl or MCP client ---- HTTPS + scoped token -> Web UI /api/v1
Prometheus ----------- Bearer scrape token --> /actuator/prometheus
Grafana -------------- queries Prometheus (not the Web UI directly)
```

**Supported:** browser UI and local roles; direct ClamD scans; asynchronous job lifecycle; native ClamdTop; allowed-root path scanning; configurable watcher; quarantine on the server; direct completion webhooks; audit; API tokens; external REST adapter; LDAP/AD and OIDC; vendor SQL migrations; metrics; daemon configuration observation; six languages; separate CLI/MCP binaries.

**Not offered:** an embedded ClamD daemon in the Web UI image; a browser-side local antivirus agent; scanning arbitrary client-machine directories via a server-path form; independent ClamD scan history import; multi-replica high availability; automatic H2-to-Postgres data transfer; browser quarantine restore; SMTP/email notification UI; a native MCP server inside the Java process; direct `clamd.conf` writing or daemon reload; an embedded Grafana/Prometheus server. A mail dependency may be present without a user-facing SMTP workflow.

### 1.1 Roles and menus

| Page/route | Role | Main functions |
|---|---|---|
| `/login` | Unauthenticated | Local username/password, LDAP sign-in (when enabled), SSO button (when configured) |
| `/dashboard` | VIEWER+ | Endpoint health and recent managed jobs; optional REST summary |
| `/main` | VIEWER+ | Select daemon and inspect PING, VERSION and STATS |
| `/clamdtop` | VIEWER+ | True interactive ClamdTop via WebSocket and PTY |
| `/scan` | OPERATOR+ | Multi-file upload and approved server-path scan |
| `/jobs`, `/jobs/{id}` | OPERATOR+ | Job list, status, verdict and details |
| `/api-scan`, `/api-reports` | OPERATOR+ | Separate upstream REST scanner upload, live jobs, analytics/export |
| `/admin/settings` | ADMIN | Scan policy, storage, workers, quarantine, watchers, direct webhook |
| `/admin/endpoints` | ADMIN | ClamD endpoint creation/update/delete/enable |
| `/admin/users` | ADMIN | Local user create/delete/password reset/role |
| `/admin/watch` | ADMIN | Watched directories and endpoint assignment |
| `/admin/api-tokens` | ADMIN | Scoped machine token issue/list/revoke |
| `/admin/settings/authentication` | ADMIN | LDAP and OIDC Test/Save, group-to-role mapping |
| `/admin/integrations` | ADMIN | Prometheus token, monitoring files, CLI/MCP instructions |
| `/admin/configuration` | ADMIN | clamd/freshclam observer and effective policy; live samples |
| `/admin/audit` | ADMIN | Security and administration audit events |

`ADMIN` includes `OPERATOR` and `VIEWER`; `OPERATOR` includes `VIEWER`. URLs are server-authorized, not merely hidden from the sidebar. The login/header language selector supports **English, Ukrainian, Romanian, French, Spanish and German**; selection persists via `clamav_lang` cookie. Appearance control provides light/dark mode.

## 2. Installation choices and prerequisites

Required: Docker Engine with Compose plugin (or Java 17+/Maven for source compilation); a reachable and properly configured ClamD daemon; persistent writable directories for `conf` and `data`; a browser. ClamD needs current signature data. Reserve at least the space required by uploaded files, quarantine, databases and signature downloads. A non-local UI requires HTTPS through a reverse proxy.

The container runs as **UID/GID `10001:10001`**. Prepare bind-mount ownership before first boot or upgrading a root-owned installation. See [non-root deployment and permission migration](NON_ROOT_CONTAINER.md) for Linux, Docker Desktop/named volumes, Kubernetes and native path/socket permissions.

**Choose one setup:**

1. **First installation:** bundled ClamD + local H2, **INSTREAM** scanning (Section 3).
2. **Home lab/production:** existing remote ClamD + external PostgreSQL, **INSTREAM** unless both containers share an identical absolute upload path (Section 4).
3. **Native file paths and directory watch:** share identical mount paths between UI and ClamD, add allowed roots and enable native path mode (Section 5).
4. **Custom security.1 build:** build a local image from the supplied source (Section 6). Do not confuse building locally with pulling the mutable `v2` image.

Never expose ClamD TCP 3310 on the public internet: its protocol does not provide web login or the application's API-token authorization. Create dedicated credentials for SQL and directory services. Avoid putting secrets in screenshots or public issue reports.

### 2.1 Which settings live where?

| Kind | Where to configure | Data location | When changes take effect |
|---|---|---|---|
| Container/image/ports/mounts/`TZ`/JDBC/proxy/Java trust | Compose / deployment | Host Compose, environment, secrets | Recreate/restart container |
| New endpoint seed, upload mode | `CLAMAV_HOST`/`PORT`, `CLAMAV_UPLOAD_PATH_SCAN_*` in Compose | Environment overrides selected persisted values on startup | Recreate; endpoint seed does not overwrite every existing DB endpoint |
| Scan roots/size/storage/worker count/quarantine/watch/webhook | **Admin → Settings** or `conf/clamav-web-client.properties` | `./conf` | Most live; concurrency changes need restart |
| Users, roles, endpoints, job history, audit, watches, API tokens | Respective Admin pages | SQL (H2 or external DB) | Live; persists across reboots |
| LDAP/OIDC providers | Authentication → Test/Save or bootstrap properties | SQL secrets encrypted using key in `./conf` | New logins updated live when saved; existing sessions may survive |
| Metrics enable/token | Monitoring & clients / bootstrap token | `./conf/integrations.properties` for UI overrides | Live; moving listener port requires restart |
| Daemon config observation/inheritance | Configuration page + read-only file mounts | Docker mounts + persisted observer preferences | File paths require restart; mode/polling live |
| CLI/MCP settings | `clamctl` config or its environment | On client machine | Separate process; unaffected by Java container restarts |

**Back up SQL + `conf` + `data` together.** Specifically, losing `conf/auth-settings.key` (or the explicit matching key) may make saved directory/SSO secrets unreadable even while their rows remain in SQL.

## 3. Complete Compose A — new install, H2 and bundled ClamD

Save the following as **`docker-compose.yaml`** in a *new, empty directory*. The same file is shipped as [`examples/compose/standalone-h2.yaml`](../examples/compose/standalone-h2.yaml).

```yaml
services:
  clamav-web-client:
    image: kosztyk/clamav-web-client:v2
    container_name: clamav-web-client
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    ports:
      - "127.0.0.1:8080:8080"
    environment:
      TZ: Europe/Bucharest
      CLAMAV_HOST: clamav-server
      CLAMAV_PORT: "3310"
      CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "false"
      CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM: "false"
    volumes:
      - ./conf:/app/conf
      - ./data:/app/data
    depends_on:
      - clamav-server
    restart: unless-stopped

  clamav-server:
    image: clamav/clamav:latest
    container_name: clamav-server
    volumes:
      - clamav-signatures:/var/lib/clamav
    restart: unless-stopped

volumes:
  clamav-signatures:
```

On the Docker host:

```bash
mkdir -p conf data
sudo chown -R 10001:10001 conf data
sudo chmod -R u+rwX conf data
docker compose config --quiet
docker compose pull
docker compose up -d
docker compose ps
docker compose logs --tail=100 clamav-web-client
docker compose logs --tail=100 clamav-server
```

Wait for ClamD to acquire usable signatures and become ready; daemon startup order alone does **not** guarantee readiness. Navigate to `http://127.0.0.1:8080/login` **on that host**. Get the generated administrator password after first startup:

```bash
docker compose exec -T clamav-web-client cat /app/data/bootstrap-admin-password
```

Username: `admin`. Go to **Administration → Users**, set a strong new password, verify it works, and remove `./data/bootstrap-admin-password` from the host with proper permissions. You may instead supply `CLAMAV_BOOTSTRAP_ADMIN_PASSWORD` (minimum 12 characters) **only on first creation of an empty database**; it does not reset an existing administrator. Do not place credentials in a public repository.

The example's loopback binding is intentionally **not externally accessible**. When an HTTPS Nginx proxy is on a different LAN host, replace `127.0.0.1` with the Docker host's specific trusted LAN IP and firewall it. For localhost-only access via SSH forwarding, retain loopback. Do not publish `3310` in this Compose.

## 4. Complete Compose B — remote ClamD + existing PostgreSQL + optional REST bridge

Use this when ClamD already runs on another server, including a home-lab setup with remote SQL. There is **no bundled ClamD service**. File uploads default to INSTREAM because the remote ClamD probably cannot see the Web UI's upload path. [`examples/compose/remote-clamd-postgres.yaml`](../examples/compose/remote-clamd-postgres.yaml) is the ready-to-copy form.

**`docker-compose.yaml`:**

```yaml
services:
  clamav-web-client:
    image: kosztyk/clamav-web-client:v2
    container_name: clamav-gui
    security_opt:
      - no-new-privileges:true
    cap_drop:
      - ALL
    ports:
      - "${CLAMAV_WEB_BIND:-127.0.0.1}:8082:8080"
    environment:
      TZ: Europe/Bucharest
      CLAMAV_HOST: "${CLAMAV_HOST:?Set CLAMAV_HOST in .env}"
      CLAMAV_PORT: "${CLAMAV_PORT:-3310}"
      CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "false"
      CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM: "false"
      SPRING_DATASOURCE_URL: "${SPRING_DATASOURCE_URL:?Set JDBC URL in .env}"
      SPRING_DATASOURCE_USERNAME: "${SPRING_DATASOURCE_USERNAME:?Set DB user in .env}"
      SPRING_DATASOURCE_PASSWORD: "${SPRING_DATASOURCE_PASSWORD:?Set DB password in .env}"
      SPRING_JPA_HIBERNATE_DDL_AUTO: validate
      SPRING_FLYWAY_ENABLED: "true"
      SPRING_FLYWAY_BASELINE_ON_MIGRATE: "false"
      CLAMAV_REST_URL: "${CLAMAV_REST_URL:-}"
      CLAMAV_REST_API_KEY: "${CLAMAV_REST_API_KEY:-}"
      CLAMAV_REST_FORM_KEY: FILES
      CLAMAV_REST_TIMEOUT_SECONDS: "10"
      CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS: "3600"
      CLAMAV_REST_REPORT_MAX_JOBS: "5000"
      SERVER_FORWARD_HEADERS_STRATEGY: "${SERVER_FORWARD_HEADERS_STRATEGY:-framework}"
    volumes:
      - ./conf:/app/conf
      - ./data:/app/data
    restart: unless-stopped
```

**Private `.env` file** alongside the Compose (do not commit):

```dotenv
CLAMAV_WEB_BIND=127.0.0.1
CLAMAV_HOST=192.168.68.73
CLAMAV_PORT=3310
SPRING_DATASOURCE_URL=jdbc:postgresql://192.168.68.152:5432/clamav
SPRING_DATASOURCE_USERNAME=clamav
SPRING_DATASOURCE_PASSWORD=YOUR_PRIVATE_PASSWORD
CLAMAV_REST_URL=
CLAMAV_REST_API_KEY=
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

These IP addresses illustrate a **private** LAN layout; substitute addresses actually reachable *from inside the Web UI container*. Create database `clamav` and a dedicated SQL account with appropriate schema DDL permissions **before** deploying. Use SQL TLS and protect DB port 5432. For an upstream compatible REST scanner, set `CLAMAV_REST_URL=http://REST_HOST:3000` and corresponding API key (or leave blank if none). Do **not** point `CLAMAV_REST_URL` at this GUI or its `/api/v1` endpoint.

```bash
mkdir -p conf data
sudo chown -R 10001:10001 conf data
sudo chmod -R u+rwX conf data
chmod 600 .env
docker compose config --quiet  # safe syntax validation; don't post the full rendered config
docker compose pull clamav-web-client
docker compose up -d
docker compose logs --tail=200 clamav-web-client
```

**For an existing, populated PostgreSQL database**, do **not** start with this new-install flow until reviewing Section 21 (Flyway history, schema, legacy OID columns and backups). A JDBC URL change does not migrate H2 data into PostgreSQL. `SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_LOB_NON_CONTEXTUAL_CREATION=true` is not normally needed in this revision: `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation=true` is already set in `application.properties`. The safe default is **`SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`**, never blindly enable it just to suppress startup errors.

For MySQL or MariaDB replace the JDBC URL and SQL credentials: `jdbc:mysql://DBHOST:3306/clamav?connectionTimeZone=UTC` or `jdbc:mariadb://DBHOST:3306/clamav`. Built-in migration folders exist for H2, PostgreSQL, MySQL and MariaDB, but confirm migrations and driver behavior with your own database version before production rollout.

## 5. Native path scans, uploads, ClamdTop filenames and watched folders

These modes are easy to confuse:

| Operation | What it sends to ClamD | Needs same filesystem path on both sides? | Filename shown by `clamdtop`? |
|---|---|---|---|
| **INSTREAM upload** | File bytes across ClamD protocol | **No** | Often reports a stream/connection, **not the upload's path** |
| **Native uploaded-path scan** | Path of saved upload under `/app/data/uploads` | **Yes**, at the same absolute path | Normally an absolute file path |
| **Server-path scan** | User-supplied path already on the server | **Yes** | Usually the server path |
| **Watcher-triggered path** | Path found by polling an allowed directory | **Yes** | Usually the watched path |

To use native uploaded-path scans with **bundled** ClamD, add to its service:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "true"
      CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM: "false"
    volumes:
      - ./data:/app/data
      - ./scandir:/scandir:ro
  clamav-server:
    volumes:
      - ./data:/app/data:ro
      - ./scandir:/scandir:ro
```

This fragment is shipped as [`shared-paths.override.yaml`](../examples/compose/shared-paths.override.yaml). To apply it to Compose A, create `./scandir` and run:

```bash
mkdir -p scandir
docker compose -f docker-compose.yaml -f examples/compose/shared-paths.override.yaml config --quiet
docker compose -f docker-compose.yaml -f examples/compose/shared-paths.override.yaml up -d
```

When composing volumes, inspect the merged configuration carefully; Compose merges mounts by target and multiple files can override existing entries. Prefer a single fully assembled Compose file for production. Path scans require `/scandir` to be an **allowed scan root**, configured under **Admin → Settings → `app.allowedScanRoots`** (default `/scandir`). Both **physical mount visibility** and **policy permission** are required. A file accessible only in the user's browser or only on the host will not be scannable by the Java container. If ClamD is remote, arrange compatible shared storage or use INSTREAM.

**Quarantine write implications:** `:ro` `/scandir` permits scans but prevents moving original server files into quarantine. An infected **uploaded copy** can be quarantined in the Web UI's writable `/app/data`. If you deliberately want infected original PATH/WATCH files moved, permit the necessary controlled writes on the source and quarantine filesystem and test permissions. Keep quarantine **outside all watched roots**. Do not silently grant broad host filesystem write access to achieve this.

**ClamdTop diagnostic:** If filenames show in development but not production, compare (1) upload mode env variables, (2) identical `/app/data` mounts in Web UI and ClamD, (3) both containers' actual storage paths, (4) whether a fallback to INSTREAM happened, and (5) whether the endpoint is a remote daemon without shared storage. `CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM=true` allows fallback when configured but can make ClamdTop show streams. It does **not** make arbitrary server paths remotely visible.

## 6. Build, verify or publish the current source

Run in the extracted application source folder or Git checkout (containing `pom.xml`, `src/`, `dashboards/` and `Dockerfile`):

```bash
mvn -B -ntp verify  # optional when host has Maven and JDK installed
docker build --pull -t clamav-web-client:2.0.0-local .
# Optional: scan local image if Docker Scout is installed:
docker scout cves local://clamav-web-client:2.0.0-local
```

The Dockerfile builds the application using Maven/Java 21, runs Maven verification, and places native `clamdtop`, `script` and ncurses runtime components in a Java 21 runtime image. To deploy **this locally built image** use:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:2.0.0-local
    pull_policy: never
    # Keep all the rest of your production ports/env/volumes as they were.
```

If you later publish your verified image as `kosztyk/clamav-web-client:v2`, that is a separate `docker login`, `docker tag` and `docker push` operation with permission to publish. Updating documentation on GitHub does **not** build or update Docker Hub images. The source security revision adjusts Spring Boot 4 and dependencies; see [`docs/SECURITY_DEPENDENCY_UPDATE.md`](SECURITY_DEPENDENCY_UPDATE.md) for the precise dependency list, limitations and verification report. Test against your database, LDAP/OIDC and ClamD before replacing a working deployment.

## 7. First login, session and local user management

1. Open `/login` through the appropriate origin, preferably HTTPS (e.g. `https://clamav.example.com/login`). Login form fields are **Username** and **Password**.
2. On the first empty database, retrieve `/app/data/bootstrap-admin-password` as in Section 3; sign in as `admin`.
3. In **Admin → Users** change the initial admin password. For each additional account, the **Add user** form has **Username**, **Password (12+ characters)**, **Role** (`VIEWER`, `OPERATOR`, `ADMIN`) and **Enabled**. Click **Create user**.
4. Existing user rows expose **New password (12+ characters) → Reset** and **Delete**. Treat destructive actions cautiously; retain a working local administrator for break-glass recovery.
5. Log out and verify the new credentials, then remove the bootstrap password file. `CLAMAV_BOOTSTRAP_ADMIN_PASSWORD` is not a post-install password reset mechanism.

Roles govern routes and API operations. The UI applies CSRF protection on browser forms. Session expiry/logout limits sessions, but saving a changed LDAP/OIDC provider does not forcibly revoke all previously issued sessions. Avoid disabling every local admin before validating alternative sign-in.

## 8. Dashboard, Main and native ClamdTop

**Dashboard:** Open `/dashboard` for recent jobs, detections, ClamD status and optional REST scanner summary. The data in *Jobs* is from scans submitted through the Web UI, its API, CLI or MCP; it is **not** a universal history of every other system's direct ClamD operations.

**Main:** At `/main`, select the endpoint then **Refresh** to fetch daemon PING, VERSION and parsed/raw STATS. Connection failures can be network issues, disabled endpoint, missing signature database or a wrong TCP/socket target.

**ClamdTop:** At `/clamdtop` choose **Endpoint**, then **Connect** for an interactive native `clamdtop` terminal; **Disconnect** closes it. The terminal uses WebSocket path `/ws/clamdtop` and a PTY helper. Proxies must allow HTTP/1.1 upgrades. It samples the actual daemon, so activity from independent ClamD clients may be visible even though that activity isn't imported into Web UI jobs. ClamdTop's daemon filename display depends on scan command mode (Section 5). Terminal layout is designed to fit the panel's full width.

## 9. Scan page — every field and job behavior

At `/scan` two independent forms are available:

| Form | Field | Meaning and action |
|---|---|---|
| **Upload file(s)** | **Files** | Select one or more files from the browser; the UI uploads them into server-managed storage |
|  | **Clamd endpoint** | Select the daemon that will scan these files |
|  | **Queue upload scan** | Creates asynchronous upload scan job(s); visit Jobs for results |
| **Server path** | **Absolute path within allowed roots** | E.g. `/scandir/folder/file.txt`, a path **inside both server containers** (not a Windows/Mac client path) |
|  | **Clamd endpoint** | Select backend; it must have path access |
|  | **Queue path scan** | Adds an asynchronous server-path job |
| **Recent jobs** | List/link | Open a submitted job, including result and error details |

Uploads are limited by the minimum of proxy body-size, Spring servlet multipart limit, `app.upload.maxBytes`, and ClamD stream/file size limits. Default application maximum is **2 GiB** but that is not proof your proxy, memory/disk or `clamd.conf` can actually scan a 2 GiB file. Input paths are checked against canonical allowed roots to block trivial `..`/symlink escape. ClamD exclusion rules may further restrict files if observed and inherited.

**Results:** Accepted requests enter `QUEUED`, then `RUNNING`, then `FINISHED` with a verdict such as `OK`, `VIRUS_FOUND`, or `ERROR`. A queued job may show no final duration. Job progress is **state polling**, not per-byte percent complete. Closing the browser does not cancel the accepted server job. When a client times out, look up the original job ID before resubmitting.

## 10. Jobs, history, verdicts, retention and quarantine

At `/jobs` browse the managed job history. Open `/jobs/{id}` to see the type (`UPLOAD`, `PATH`, `WATCH`), requested target, selected endpoint, submitter, submission/start/finish timestamps, status, verdict, detections and error details. A positive signature is different from an actual quarantine move. The app persists data in SQL; there is no claim of indefinite automated purging or a built-in legal hold/archive workflow.

**Quarantine setup:** Go to **Admin → Settings**. Set `app.quarantine.enabled=true` and `app.storage.quarantineDir=./data/quarantine` (or another **writable persisted path**) then Save. Protect host access to this folder. The operation may move a saved upload or writable server-side source on positive detection. An original file sitting on the uploading user's Mac is **not** moved. Read-only server path mounts cannot move their original files. No supported browser quarantine restore/download screen is provided; any restore must follow an operator-controlled filesystem/forensics procedure.

In the v1 API/MCP diagnostics, quarantine evidence may be `PENDING`, `RECORDED`, `NOT_RECORDED`, `NOT_APPLICABLE` or `UNKNOWN`. `RECORDED` means a path was recorded in job evidence, **not** that the file was independently reverified as present. Older PATH/WATCH jobs can have incomplete evidence. A `VIRUS_FOUND` verdict does **not** guarantee successful quarantine.

## 11. Administration → Settings — complete field reference

The page presents editable settings and a direct webhook panel. Values are persisted to **`/app/conf/clamav-web-client.properties`** (host `./conf/clamav-web-client.properties`). Do not mix application keys up with Spring environment variables.

| Exact settings key / UI field | Default | Purpose / guidance |
|---|---|---|
| `app.allowedScanRoots` | `/scandir` | Comma-separated absolute server roots allowed for PATH/WATCH scans. Docker mounts must independently expose them |
| `app.upload.maxBytes` | `2147483648` | Application max input in bytes, not a guarantee ClamD accepts the size |
| `app.concurrentScans` | `2` | Scan worker concurrency; **restart** to resize active pool |
| `app.storage.uploadDir` | `./data/uploads` | Server-side saved uploads; persist `/app/data` |
| `app.storage.quarantineDir` | `./data/quarantine` | Destination for infected server files; writable and outside watched input roots |
| `app.quarantine.enabled` | `false` | Whether an eligible positive scan triggers a quarantine attempt |
| `app.watch.enabled` | `false` | Global switch for watched-directory polling |
| `app.watch.pollSeconds` | `30` | Requested polling interval, while the scheduler wakes on a 30-second cadence |
| `app.webhook.enabled` | `false` | Direct managed scan completion notification on/off |
| `app.webhook.url` / **Webhook URL** | Empty | Write-only receiver URL; blank edit retains previously saved value |
| **Remove saved webhook URL** | Unchecked | Explicitly clears secret and disables webhook; Save to apply |
| `app.upload.pathScan.enabled` | `true` application default | Native upload path scanning; Docker `CLAMAV_UPLOAD_PATH_SCAN_ENABLED` overrides on startup |
| `app.upload.pathScan.fallbackToInstream` | `false` | Optional upload fallback; Docker `CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM` overrides |
| `clamav.service.host` / `port` / `platform` | `localhost` / `3310` / `UNIX` | Compatibility seed for initial endpoint; do not use as a live switch for existing DB endpoints |

Click **Save settings** after changes. The dynamic webhook form includes **Enable webhook notifications** (Enabled/Disabled), **Webhook URL** (new or replacement, write-only), and **Remove** checkbox. Saving a blank URL does *not* erase an existing credential. Enabling with no URL is invalid. The URL is stored in `conf`, not displayed in plaintext on subsequent visits.

### 11.1 Direct webhooks

Direct scan notifications may include job ID, type, target, endpoint, submitter, final verdict, found signatures, error and available quarantine path. Compatible generic endpoints receive JSON; Discord endpoints receive adapted content with mentions disabled. Delivery failures do not erase scan results. There is **no durable local retry queue or delivery-history UI**. Upstream REST service webhooks (Section 13) are configured on the **external** REST scanner; these are distinct from direct managed job notifications.

Delivery now uses two dedicated background workers with a queue of 100 pending
notifications. Defaults are 5-second connect, 10-second read, 5-second DNS and
20-second total delivery deadlines. A hung receiver or full webhook queue cannot
occupy the scan workers; a full queue drops the new notification with a sanitized
warning. Payloads are snapshotted before dispatch; failures do not change results.

Private/local destinations are blocked by default and checked against the DNS
addresses actually used for each new connection. Redirects and retries are
disabled, and receiver bodies are ignored. To deliberately use an internal
receiver, set `CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES: "true"` in the Web UI
container's deployment environment and recreate it. Metadata/link-local and
other prohibited destinations remain blocked. The UI cannot relax this server
policy. All behavior is built into normal Docker/JAR builds; no helper is needed.

Full configuration table, Compose/Kubernetes guidance, payload behavior and
best-effort delivery limits: [Webhook resilience](WEBHOOK_RESILIENCE.md).

### 11.2 Safely edit the file directly

If no UI is available, stop the app, back up `./conf`, edit `./conf/clamav-web-client.properties`, and recreate the app. Application-provided defaults are created if missing. Do **not** replace an existing file with `conf/clamav-web-client.properties.example` on an upgrade: it could overwrite allowlists, webhook URL and other persisted policy. Keep its permissions private.

## 12. Administration → Endpoints — field-by-field

At `/admin/endpoints` **Add endpoint** asks for:

| Field | Meaning |
|---|---|
| **Name** | Unique descriptive backend identifier, e.g. `default` or `av-secondary` |
| **Host or socket** | TCP hostname/IP reachable from UI container, or supported Unix socket *absolute path* |
| **Port** | TCP port (usually `3310`); `0` for supported Unix socket endpoints |
| **Platform** | `UNIX` or `WINDOWS` path convention, **not** a TLS selection |
| **Enabled** | Whether endpoint can be selected for scans |

Click **Create**; existing rows expose **Update**, **Delete** and **Enabled**. The app stores endpoint records in SQL. `CLAMAV_HOST`/`CLAMAV_PORT` seed initial `default`; changing them later is **not a guarantee** of overwriting an existing database endpoint. Edit it in the UI when necessary. Configuration inheritance (Section 19) can optionally synchronize `default`, but explicit host/port deployment overrides prevent connection replacement. A container's `localhost` means itself; use a Docker service name or remote reachable IP for ClamD in another container.

A Unix socket requires mounting the containing directory at the same path and granting read/write socket access to the Java process. Socket configuration discovery and actual Unix operations depend on the runtime/Linux host. Avoid public/native ClamD TCP access and firewall between services.

## 13. API Scan and API Reports — optional **external** REST scanner

These two pages become meaningful when a **separate compatible REST scanning service** exists and `CLAMAV_REST_URL` is configured. They are **not** alternate views of local `/api/v1` managed jobs. Their backend processes jobs, statistics and webhook delivery independently.

**To enable:** set the upstream host and credentials in the Web UI container (Section 20.5), recreate the Web UI, then open:

- **API Scan** `/api-scan`: **Files** multi-upload, **Webhook** (choose upstream default/available hook), **Queue API scan**; view **API service status**, **Most recent external API jobs**, Prev/Next pagination and individual live progress/results.
- **API Reports** `/api-reports`: **API activity**, **Jobs & history**; select **All jobs (live)** vs **Finished history**, **Status** (All/queued/running/completed/failed), **Mode** (All/Sync/Async), **Detection** (All/Detected/Clean), **From**, **To**, **Request ID** (correlation), **Apply filters**, **Previous/Next**, **Generate analytics**, **Export CSV**, **Export JSON**. Upstream webhook status/retry is displayed if supported by that external server.

Most integrations use `CLAMAV_REST_FORM_KEY=FILES`, API key header mode `x-api-key`, configurable endpoint paths and server-side requests. The REST URL is resolved **from within the Java container**, not from the user's browser. Credentials remain server-side. The upstream may implement a different contract than `/api/v1`; ensure compatibility. An unconfigured bridge remains unconfigured even when the UI navigation is visible. Do not expect upstream job history to appear in the built-in `/jobs` table or the reverse.

## 14. Watch Directories — setup, controls and operating limits

Use when incoming files already land **inside the Web UI's server-side filesystem**. First mount an approved path at the same absolute location in both UI and ClamD (Section 5), then set `app.allowedScanRoots`, and in **Admin → Settings** enable **`app.watch.enabled`**. Go to **Admin → Watch Directories** `/admin/watch`:

| Control | Action |
|---|---|
| **Watch new directory** path | Enter an approved absolute directory, e.g. `/scandir/inbox` |
| **Clamd endpoint** | Select the backend capable of scanning that path |
| **Add directory** | Save watched path and its endpoint in SQL |
| **Toggle** | Enable/disable an existing watched record without deleting it |
| **Delete** | Remove the watch registration; not a command to delete source files |

The implementation uses periodic file inspection (not OS inotify). It remembers file size/mtime to avoid repeatedly submitting unchanged entries. The scheduler wakes on a **30-second cadence**; configured `app.watch.pollSeconds` adjusts the intended interval. Each pass is bounded to around **depth five** and **1,000 regular files**, so a huge changing tree is not guaranteed to be scanned fully in one pass. Inherited `ExcludePath` policy can filter entries. New jobs have type `WATCH`. Test a small directory, verify the new job, then confirm an unchanged file does not flood the queue. Quarantine original files requires explicit write access and storage planning.

## 15. Authentication Management — provider tests, saves and recovery

Open **Admin → Settings → Authentication** or `/admin/settings/authentication`. Local authentication remains available for recovery. Both provider forms implement **Test** and **Save** independently: **Test** checks an unsaved draft; **Save** persists settings for subsequent logins without navigating away or discarding entered form values. Browser UI saved settings take precedence over bootstrap Docker variables. Existing saved secret values are masked/write-only; leave a secret field blank to retain the saved secret.

Directory and OIDC client secrets saved through this UI are **encrypted before being stored in SQL**, using the persistent symmetric key (default `./conf/auth-settings.key`, mounted as host `./conf/auth-settings.key`). Use `CLAMAV_AUTH_SETTINGS_KEY_FILE` for an alternate key path or `CLAMAV_AUTH_SETTINGS_KEY` for a base64-encoded 32-byte key. Supply one stable key source and back it up with SQL. Do not casually rotate/delete the key: it is required to decrypt existing saved secrets.

### 15.1 LDAP / Active Directory — every UI field

| Authentication → LDAP field | Example / meaning |
|---|---|
| **Enable LDAP login** | Toggle to show/allow directory sign-in |
| **LDAP server URL** | `ldaps://ldap.example.com:636`; or `ldap://...` with StartTLS enabled |
| **Base DN** | `dc=example,dc=com` |
| **Bind / manager DN** | `cn=svc-clamav,ou=services,dc=example,dc=com` |
| **User search base** | `ou=users` (relative to base) |
| **User search filter** | `(uid={0})` OpenLDAP, often `(sAMAccountName={0})` for AD |
| **Group search base** | `ou=groups` |
| **Group search filter** | Optional; e.g. `(member={0})`, with documented placeholders |
| **Group member attribute** | `member`, or directory-specific value such as `uniqueMember` |
| **Group name attribute** | `cn` |
| **Viewer groups** | Comma-separated allowed directory group names |
| **Operator groups** | Comma-separated names granting scan operations |
| **Admin groups** | Comma-separated **explicitly trusted** directory groups granting admin |
| **Maximum nested-group depth** | Bounded nested lookup, normally `3`, UI 1–8 |
| **Connect timeout (ms)** | Normally `5000`, UI 1000–30000 |
| **Read timeout (ms)** | Normally `5000`, UI 1000–30000 |
| **Referral policy** | Ignore / Follow / Throw; directory-specific |
| **Bind password** | Write-only; blank preserves saved value |
| **StartTLS** | Required when using plain `ldap://` URL without LDAPS |
| **Resolve nested groups** | Use if roles are inherited through nested memberships |
| **Connection pooling** | Optional; restrict to appropriate LDAPS configuration |
| **Test LDAP connection** | Validate draft connection/filter/bind/TLS without saving |
| **Save LDAP settings** | Persist validated settings and activate for new sign-ins |

**LDAP security:** Java's truststore must trust the LDAPS/StartTLS certificate **and its hostname must match**. Trusting a CA does not fix a mismatched SAN. The application rejects insecure plain LDAP without TLS. Login is by a distinct **Sign in with LDAP** form; successful bind alone is not proof group mapping is correct. Test a member of each role and a member of no role before deploying broadly. Explicit role mapping and nested-group resolution are essential. Existing local accounts remain independent from directory principals.

#### LDAP Docker Compose bootstrap alternative

When you prefer environment configuration rather than the form, add **to your existing Web UI service**, merging rather than replacing other settings:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_IDENTITY_LDAP_ENABLED: "true"
      CLAMAV_IDENTITY_LDAP_URL: "ldaps://ldap.example.com:636"
      CLAMAV_IDENTITY_LDAP_BASE_DN: "dc=example,dc=com"
      CLAMAV_IDENTITY_LDAP_MANAGER_DN: "cn=svc-clamav,ou=services,dc=example,dc=com"
      CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE: "/run/secrets/clamav_ldap_bind"
      CLAMAV_IDENTITY_LDAP_USER_SEARCH_BASE: "ou=users"
      CLAMAV_IDENTITY_LDAP_USER_SEARCH_FILTER: "(uid={0})"
      CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_BASE: "ou=groups"
      CLAMAV_IDENTITY_LDAP_GROUP_MEMBER_ATTRIBUTE: "member"
      CLAMAV_IDENTITY_LDAP_GROUP_NAME_ATTRIBUTE: "cn"
      CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER: "ClamAV Viewers"
      CLAMAV_IDENTITY_LDAP_GROUPS_OPERATOR: "ClamAV Operators"
      CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN: "ClamAV Administrators"
      JAVA_TOOL_OPTIONS: >-
        -Djavax.net.ssl.trustStore=/app/trust/ldap-truststore.p12
        -Djavax.net.ssl.trustStorePassword=changeit
        -Djavax.net.ssl.trustStoreType=PKCS12
    secrets:
      - clamav_ldap_bind
    volumes:
      - ./trust/ldap-truststore.p12:/app/trust/ldap-truststore.p12:ro
secrets:
  clamav_ldap_bind:
    file: ./secrets/ldap-bind-password.txt
```

This illustrates **Compose secret mounting** for the application's explicit LDAP `*_PASSWORD_FILE` reader. Prepare `./secrets/ldap-bind-password.txt` privately and include it in backups but not Git. The `changeit` truststore example password is for a certificate truststore, not an LDAP credential; replace as required. If using Active Directory, adapt search filter, group membership and referrals. **Never set both an inline manager password and `MANAGER_PASSWORD_FILE`**. Compatibility environment names `SECURITY_LDAP_*` also exist, but prefer `CLAMAV_IDENTITY_LDAP_*` (higher bootstrap precedence). If your environment already sets `JAVA_TOOL_OPTIONS`, merge the existing flags instead of overwriting them.

### 15.2 OIDC / Authentik / Keycloak — every UI field

Register an **OIDC confidential authorization-code** application in your identity provider. Set the callback/redirect URI to the exact public HTTPS origin followed by `/login/oauth2/code/clamav` (for example `https://clamav.example.com/login/oauth2/code/clamav`). The issuer is the actual provider's **issuer URL**, not its administrative webpage. The user must reach the same HTTPS public origin during the redirect.

| Authentication → OIDC field | Purpose |
|---|---|
| **Enable OIDC login** | Display/enable the SSO option |
| **HTTPS issuer URL** | E.g. `https://auth.example.com/application/o/clamav/` (provider-specific) |
| **Client ID** | Confidential client identifier from IdP |
| **Client secret** | Write-only; blank retains encrypted prior value |
| **Requested scopes** | Comma-separated, normally `openid,profile,email,groups` |
| **Group claim path** | JWT/userinfo claim containing groups, normally `groups` |
| **Viewer groups** | Claim values mapped to VIEWER |
| **Operator groups** | Claim values mapped to OPERATOR |
| **Admin groups** | Claim values mapped to ADMIN; keep tightly controlled |
| **Login button label** | E.g. `Sign in with Authentik` |
| **Logout behavior** | Local application logout or IdP provider logout (if supported) |
| **Test OIDC discovery** | Test issuer metadata/client setup draft, no save |
| **Save OIDC settings** | Persist and activate for new logins |

**OIDC Compose bootstrap** using standard Spring security registration support:

```yaml
services:
  clamav-web-client:
    environment:
      SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI: "https://auth.example.com/application/o/clamav/"
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID: "clamav-webui"
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET: "${OIDC_CLIENT_SECRET:?Set in private .env}"
      SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE: "openid,profile,email,groups"
      SERVER_FORWARD_HEADERS_STRATEGY: framework
```

Or use application namespace `CLAMAV_IDENTITY_OIDC_*` including `CLIENT_SECRET_FILE`, issuer, scopes, group roles, claim and logout controls. Do not configure two conflicting secret sources. The standard Spring registration name `oidc` is bridged internally to the actual callback registration name `clamav`. **Behind a reverse proxy**, the packaged `framework` default recognizes HTTPS automatically. Ensure the trusted proxy **overwrites** any externally supplied Forwarded / X-Forwarded-* headers and direct backend access is restricted. Wrong forwarded scheme/host causes invalid callback redirects or HTTP/HTTPS loops. Claims must actually include the mapped group names—requesting the `groups` scope does not magically create a group claim in every IdP (especially Google). Test with a low-privilege account before mapping administrators.

### 15.3 Provider precedence, identity separation, recovery

- Authentication's saved UI configuration overrides Docker bootstrap identity fields for subsequent logins; **Test** never saves.
- Explicit `CLAMAV_IDENTITY_LDAP_*` bootstrap variables override `SECURITY_LDAP_*` compatibility aliases. The OIDC compatibility bridge supports standard Spring client settings.
- A successful LDAP bind/OIDC discovery is **not** an authorization-mapping validation; login and group claims must still be exercised end-to-end.
- The system keeps local accounts for break-glass access and stores external identities separately; it does not store LDAP/OIDC passwords as local password hashes.
- Login lockout and browser sessions are per application instance; there is no supported distributed identity/session cluster.

## 16. API Tokens, `/api/v1`, legacy API, scope and curl examples

Under **Admin → API Tokens** `/admin/api-tokens` use:

| Create API token field | Meaning |
|---|---|
| **Label** | Human-friendly purpose: `ci-runner`, `monitor`, `mcp-lab` |
| **Expires in hours** | TTL, UI range **1–8760** |
| **Scopes** | Choose minimum capabilities from the six v1 scopes below |
| **Allowed endpoint IDs** | Comma-separated numeric IDs, optional; empty means any endpoint allowed by policy |
| **Allowed server path roots** | Comma-separated permitted server roots; **required for `scan:path`** |
| **Create API token** | Issues one-time secret; copy securely, it will not be displayed again |
| **Issued credentials / Revoke** | Review metadata and explicitly invalidate a leaked/obsolete credential |

Only the token **hash** persists. Use the returned secret as `Authorization: Bearer TOKEN` over HTTPS. A scoped token cannot log into Admin pages or mint new tokens, even if it can scan or read jobs. Endpoint/path restrictions intersect with global policy.

| Scope | Grants |
|---|---|
| `health:read` | Web liveness, DB readiness, ClamD health and safe daemon metric snapshot |
| `endpoints:read` | Endpoint list and versioned OpenAPI file |
| `scan:upload` | File upload scans |
| `scan:path` | Approved server path scans (with token path-root restriction) |
| `jobs:read` | List, inspect, poll jobs |
| `audit:read` | Authorized audit records |

**Versioned REST endpoints**:

| Verb and path | Function |
|---|---|
| `GET /api/v1/auth` | Authentication identity/capability helper |
| `GET /api/v1/csrf` | Session/Basic CSRF helper |
| `GET /api/v1/health/live` | App process liveness |
| `GET /api/v1/health/ready` | Database readiness (returns 503 when down) |
| `GET /api/v1/health/clamd?endpointId=N` | Daemon connectivity/signature freshness (503 for down/stale) |
| `GET /api/v1/metrics/clamd?endpointId=N` | Safe, parsed daemon STATS; no filenames/raw queue strings |
| `GET /api/v1/endpoints` | Available daemon endpoints |
| `GET /api/v1/jobs?page=0&size=25` | Paged job list; optional `endpointId`, `status`, `verdict` |
| `GET /api/v1/jobs/{id}` | Persisted job details |
| `GET /api/v1/jobs/{id}/progress` | Current status/completion and verdict |
| `POST /api/v1/scans/uploads` | Multipart `files`, optional `endpointId`; returns 202 with job IDs |
| `POST /api/v1/scans/paths` | JSON `{"path":"/scandir/file.txt","endpointId":1}`; returns accepted job ID |
| `GET /api/v1/audit` | Authorized audit events |
| `GET /api/v1/openapi.yaml` | Download versioned OpenAPI contract (`endpoints:read`) |
| `/api/v1/tokens` admin endpoints | Token issue/list/revoke require ADMIN/HTTPS/CSRF as applicable, not ordinary scoped token admin |

**Example with an issued token:**

```bash
export WEBUI="https://clamav.example.com"
# Prefer a private secret manager; this shell variable is illustrative.
export CLAMAV_API_TOKEN="REPLACE_WITH_TOKEN"
curl --fail-with-body -H "Authorization: Bearer $CLAMAV_API_TOKEN" "$WEBUI/api/v1/health/ready"
curl --fail-with-body -H "Authorization: Bearer $CLAMAV_API_TOKEN" "$WEBUI/api/v1/endpoints"
curl --fail-with-body -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  -F 'files=@./sample.txt' "$WEBUI/api/v1/scans/uploads"
curl --fail-with-body -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  -H 'Content-Type: application/json' -d '{"path":"/scandir/sample.txt","endpointId":1}' \
  "$WEBUI/api/v1/scans/paths"
curl --fail-with-body -H "Authorization: Bearer $CLAMAV_API_TOKEN" "$WEBUI/api/v1/jobs?page=0&size=25"
```

Tokens are **not** a CSRF bypass for browser sessions. Legacy routes under `/api` remain for compatibility: `GET /api/health`, `/api/endpoints`, `/api/clamdtop`, `/api/jobs`, `/api/jobs/{id}`, and legacy `POST /api/scan/upload` / `/api/scan/path`. Legacy Basic credentials need appropriate TLS and CSRF handling; the supported `clamctl` manages this flow when used without a token. Prefer `/api/v1` and scoped tokens for new automations. OpenAPI source is `src/main/resources/openapi/clamav-v1.yaml`.

### 16.1 API behavior and safety

`api.v1.scan-rate-limit-per-minute` defaults **30 per instance**; `api.v1.max-page-size` defaults **100**. Database health is independent of ClamD health; `health/live` is not proof the daemon works. ClamD health reports signature freshness based on configured database timezone/age threshold; invalid timestamps are UNKNOWN, not fabricated fresh. Expired/revoked tokens cease to authenticate. API return values and error codes should be checked explicitly rather than assuming HTTP 200 on queued scan submission.

## 17. Audit Log and accountability

At `/admin/audit` review time-stamped scan, endpoint, token, user, authentication, settings, watch and administrative actions. The audit data is in **SQL**, separate from Prometheus counters. Audit text omits stored secret values, but can include operational identifiers/paths; restrict database and log access. There is no supported standalone archive/retention scheduler or full SIEM-export interface. Plan SQL retention and off-host backups according to your environment. The versioned `/api/v1/audit` needs `audit:read` or appropriate browser role.

## 18. Monitoring & clients — Prometheus, Grafana, alerting, downloads

Open **Admin → Monitoring & clients** `/admin/integrations`:

| UI control | Purpose |
|---|---|
| **Allow Prometheus scrapes** | Live enable/disable independent from browser/API auth |
| **Replace scrape token** | Write-only replacement; blank leaves prior token |
| **Save Prometheus settings** | Apply current on/off/token without container restart |
| **Generate new token & enable** | Create random token, show **once**, activate immediately |
| **Use container defaults** | Remove UI-managed settings and use environment/bootstrap behavior |
| **Grafana dashboard** download | Retrieve packaged `grafana-clamav-webui.json` |
| **Alert rules** download | Retrieve packaged `clamav-alerts.yml` |
| **Home-lab testing guide** download | Retrieve packaged lab markdown |
| **Command line · clamctl** and **AI assistants · MCP** | Instructions/examples for independent client binaries |

You may bootstrap with `CLAMAV_METRICS_SCRAPE_TOKEN` of at least **24 characters**, or generate in UI (replacement UI tokens accept 32–4096 printable non-space characters). `GET /actuator/prometheus` requires its **separate Bearer scrape token**, not a login cookie or API token. A missing/invalid/disabled token returns **403**. The management listener shares `8080` by default; optionally isolate it on internal `9091`:

```yaml
services:
  clamav-web-client:
    environment:
      MANAGEMENT_SERVER_PORT: "9091"
      MANAGEMENT_SERVER_ADDRESS: "0.0.0.0"
      # Optional bootstrap; the UI can generate a token and manage it instead.
      CLAMAV_METRICS_SCRAPE_TOKEN: "${CLAMAV_METRICS_SCRAPE_TOKEN:-}"
    # For a *host-local* Prometheus only:
    ports:
      - "127.0.0.1:9091:9091"
```

For **Prometheus in another container**, do **not publish** `9091` publicly. Put both on a restricted Docker network, point scrape target to `clamav-web-client:9091`, and store the Bearer credential in a private file. **Important:** if the UI already has its own port mapping, merge this snippet with the existing ports; do not accidentally remove the Web UI port or bind the management listener to a public interface. `MANAGEMENT_SERVER_PORT` changes need recreation; enable/disable/rotation in the UI are live.

**Prometheus example** (an external Prometheus instance):

```yaml
scrape_configs:
  - job_name: clamav-webui
    scheme: http
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ['clamav-web-client:9091']
    authorization:
      type: Bearer
      credentials_file: /etc/prometheus/secrets/clamav_metrics_token
```

The target must be network-reachable from the Prometheus container, and the credentials file must contain the exact UI-generated token. If scraping over an untrusted network, use a TLS proxy and restrict it by address/identity. To verify from inside a trusted network:

```bash
curl -i http://127.0.0.1:9091/actuator/prometheus
# Expected without a token: 403
curl -i -H "Authorization: Bearer YOUR_PRIVATE_SCRAPE_TOKEN" \
  http://127.0.0.1:9091/actuator/prometheus
# Expected with valid token: 200 Prometheus exposition
```

**Key metrics:**

| Metric | Meaning / interpretation |
|---|---|
| `clamav_scans_total{type,verdict}` | Managed completed scans by upload/path/watch and ok/virus_found/error; resets on app restart |
| `clamav_scan_duration_seconds` | Execution histogram **excluding queue waiting** |
| `clamav_viruses_detected_total{signature}` | Unique file/signature detections, not simply count of positive jobs |
| `clamav_scan_database_available` | Whether SQL database is accessible (0 on outage) |
| `clamav_queue_items` | Current daemon queue depth, not queue capacity |
| `clamav_threads_live`, `_idle`, `_busy`, `_max` | Daemon worker utilization |
| `clamav_pools_used_bytes`, `_total_bytes` | Daemon pool memory |
| `clamav_database_version`, `clamav_database_timestamp` | Signature database version/epoch timestamp |

Daemon metrics poll up to **12 enabled endpoints** by stable-capacity but position-dependent `slot` labels; map slot to backend via **Administration → Configuration**. Unavailable readings may be unknown/NaN rather than zero. Prometheus counters do **not replay SQL history** after restart. Import `dashboards/grafana-clamav-webui.json` into Grafana after connecting a Prometheus datasource (the dashboard is roughly 18 panels). Add `dashboards/clamav-alerts.yml` to Prometheus rule files and configure Alertmanager separately; the UI does not deploy Prometheus, Grafana or Alertmanager. Example alerts include daemon/signature freshness, backlog, saturation, memory and positive scan activity; tune thresholds. Separate lab files are under `examples/home-lab/`.

## 19. Configuration — mounted clamd.conf / freshclam.conf inspection & inheritance

At **Admin → Configuration** `/admin/configuration` you can inspect read-only daemon files and live daemon samples. This is most useful when the UI has access to the real configuration directory; for a remote daemon with no shared config, leave paths blank and manage endpoints directly.

**Docker setup:**

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_CONFIG_PATH: /app/config-observer/clamd.conf
      FRESHCLAM_CONFIG_PATH: /app/config-observer/freshclam.conf
      CLAMAV_CONFIG_DISCOVERY_ENABLED: "false"  # inspection only, safest initial choice
      CLAMAV_CONFIG_POLL_MS: "30000"
    volumes:
      - ./clamd-config:/app/config-observer:ro
```

Mount the *directory*, not just a single file, so bounded relative `Include` files can be read. Omit `FRESHCLAM_CONFIG_PATH` if absent. The UI does **not** edit files or reload ClamD/FreshClam; it reports the disk configuration and the sampled running state as **different sources**.

**Configuration page fields:**

| Field/control | Purpose |
|---|---|
| **Observe mounted configuration files** | Enable or pause observation |
| **Inherit daemon connection, scan limits and exclusions** | Synchronize eligible `default` endpoint and inherited policy when valid files mounted |
| **Polling interval (seconds)** | UI range **5–3600**; saved overrides take effect live |
| **clamd.conf path in Web UI container** | Read-only deployment path; change Docker mount/env to change it |
| **freshclam.conf path in Web UI container** | Optional read-only deployment path |
| **Save observation settings** | Persist controls (not file content) |
| **Use container defaults** | Discard saved observer overrides, use env/bootstrap |
| **Connections used for scans / Application connection defaults** | Effective connection and precedence |
| **Effective daemon scan policy** | Effective `StreamMaxLength`, `MaxScanSize`, `MaxFileSize`, exclusions |
| **Live ClamD telemetry · Cached snapshot** | VERSION/STATS sample with age and last status |
| **clamd.conf · Desired configuration** | Parsed disk directives/errors and redactions |
| **freshclam.conf · Desired configuration** | Parsed optional definitions |
| **Discovery and precedence** | Explanation of who owns default endpoint and scan restrictions |

**Key precedence:** `CLAMAV_CONFIG_PATH` must exist and be valid to activate observation. `CLAMAV_CONFIG_DISCOVERY_ENABLED=false` leaves this in **inspection-only** mode unless overridden in UI. Enabling inheritance updates only the endpoint named `default`, preserving the endpoint record ID and enabled state; other endpoint records remain untouched. If `CLAMAV_HOST` or `CLAMAV_PORT` is explicitly set, file-driven **connection** replacement is blocked, although inherited restrictive daemon policy may still apply. When a `LocalSocket` directive is discovered it takes precedence over TCP settings, but a socket must truly exist at the accessible path inside the Java container. Explicit Docker endpoints are generally simpler when ClamD is remote.

**Scope of inheritance:** size constraints `StreamMaxLength`, `MaxScanSize`, `MaxFileSize` and `ExcludePath` can further **restrict** scan/watch/upload targets; they cannot expand allowed scan roots or relax application limits. Changed disk files do not imply the ClamD process has actually reloaded them. Parser errors retain last valid observation in memory and surface a warning; invalid first configuration can block managed scans through `default` until valid. The observer refuses unbounded include traversal outside the mounted configuration directory, and sensitive directives are redacted. The live snapshot may be temporarily older than on-disk settings; use timestamps when investigating apparent mismatches.

## 20. Exhaustive deployment & configuration variable reference

The reference below distinguishes **actual Docker environment variables** from `conf` property keys, JVM properties and client-specific settings. There is no magic environment variable for every UI control. Use Docker `environment:` for startup configuration and Administration UI for persisted admin settings.

### 20.1 Core server, upload and storage

| Variable | Default / recommended usage |
|---|---|
| `CLAMAV_HOST` | `localhost` fallback; override to Docker service name or reachable remote IP for initial default endpoint |
| `CLAMAV_PORT` | `3310`; initial daemon TCP port |
| `CLAMAV_UPLOAD_PATH_SCAN_ENABLED` | `true` default; set `false` for INSTREAM, `true` only when ClamD sees saved uploads by same absolute path |
| `CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM` | `false`; optional fallback for upload path mode |
| `CLAMAV_BOOTSTRAP_ADMIN_PASSWORD` | Unset generates password file; first-run-only, minimum 12 chars |
| `TZ` | Container timezone; examples `Europe/Bucharest`; independently check daemon signature timestamp timezone |
| `SERVER_PORT` | `8080` application listener; host `ports:` mapping controls external port |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` by default in the application and Compose; `none` explicitly disables forwarded-header trust; `native` remains available for deliberate container-specific configuration |
| `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `2048MB` default |
| `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `2048MB` default |
| `SERVER_TOMCAT_MAX_SWALLOW_SIZE` | `2048MB` default |
| `JAVA_TOOL_OPTIONS` | JVM flags such as Java CA truststore or advanced `-D...` properties; preserve other flags |

Scan roots, storage/quarantine paths, scan concurrency and webhook URL/enable belong to `conf/clamav-web-client.properties` as listed in Section 11. Webhook timeout/queue/address policy instead uses startup `CLAMAV_WEBHOOK_*` environment variables; see [Webhook resilience](WEBHOOK_RESILIENCE.md). If you set `/scandir` but the Web UI container has no matching mount, scanning will fail. Docker service `image:`, `pull_policy:`, `ports:`, `volumes:`, `depends_on:` are Compose instructions, **not Java environment settings**.

### 20.2 SQL and Flyway

| Variable | Default / guidance |
|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` if omitted |
| `SPRING_DATASOURCE_USERNAME` | `sa` for H2; SQL username for external databases |
| `SPRING_DATASOURCE_PASSWORD` | Empty for H2; use secure external SQL password |
| `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | Driver auto-inferred; optional if correct for URL |
| `SPRING_JPA_HIBERNATE_DDL_AUTO` | `validate`; do not use `update`/`create` as a migration workaround |
| `SPRING_FLYWAY_ENABLED` | `true` |
| `SPRING_FLYWAY_LOCATIONS` | `classpath:db/migration/{vendor}` (H2, PostgreSQL, MariaDB, MySQL) |
| `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false`; manually controlled one-time legacy-clone process only |
| `SPRING_FLYWAY_BASELINE_VERSION` | `1` |
| `SPRING_FLYWAY_VALIDATE_ON_MIGRATE` | `true`; keep validation on |
| `APP_DB_POSTGRES_LOB_AUTO_MIGRATE` | `false`; historical OID conversion is high risk, opt-in only after DBA review |
| `SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_LOB_NON_CONTEXTUAL_CREATION` | Already `true` from packaged application settings; ordinarily **do not need** to duplicate in Compose |
| `SPRING_H2_CONSOLE_ENABLED` | `false`; keep off in production |

**No built-in `SPRING_DATASOURCE_PASSWORD_FILE` resolver** is implemented by the app. Do not assume a fictional Docker secrets path works automatically for JDBC. Use supported Spring config-tree injection or securely supply an environment value. Avoid publishing DB credentials in Compose examples or stdout. DB schema versioning uses Flyway; an existing SQL database requires separate baseline planning (Section 21).

### 20.3 LDAP, OIDC and encryption

Bootstrap LDAP variables (all prefixed as shown):

`CLAMAV_IDENTITY_LDAP_ENABLED`, `CLAMAV_IDENTITY_LDAP_URL`, `CLAMAV_IDENTITY_LDAP_BASE_DN`, `CLAMAV_IDENTITY_LDAP_USER_SEARCH_BASE`, `CLAMAV_IDENTITY_LDAP_USER_SEARCH_FILTER`, `CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_BASE`, `CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_FILTER`, `CLAMAV_IDENTITY_LDAP_MANAGER_DN`, `CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD`, `CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE`, `CLAMAV_IDENTITY_LDAP_GROUP_MEMBER_ATTRIBUTE`, `CLAMAV_IDENTITY_LDAP_GROUP_NAME_ATTRIBUTE`, `CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER`, `CLAMAV_IDENTITY_LDAP_GROUPS_OPERATOR`, `CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN`, `CLAMAV_IDENTITY_LDAP_NESTED_GROUPS`, `CLAMAV_IDENTITY_LDAP_MAX_NESTED_DEPTH`, `CLAMAV_IDENTITY_LDAP_START_TLS`, `CLAMAV_IDENTITY_LDAP_POOLED`, `CLAMAV_IDENTITY_LDAP_REFERRALS`, `CLAMAV_IDENTITY_LDAP_CONNECT_TIMEOUT_MILLIS`, `CLAMAV_IDENTITY_LDAP_READ_TIMEOUT_MILLIS`.

LDAP defaults: disabled, `(uid={0})` user filter, `member` member attribute, `cn` group name, nested groups off, max depth 3, StartTLS off, pooled off, referrals ignored, timeout 5000ms. The `SECURITY_LDAP_ENABLED`, `SECURITY_LDAP_URL`, `SECURITY_LDAP_BASE_DN`, `SECURITY_LDAP_BIND_DN`, `SECURITY_LDAP_BIND_PASSWORD`, `SECURITY_LDAP_USER_SEARCH_BASE`, `SECURITY_LDAP_USER_SEARCH_FILTER`, `SECURITY_LDAP_GROUP_SEARCH_BASE`, `SECURITY_LDAP_GROUP_SEARCH_FILTER` compatibility names are aliases at bootstrap with lower precedence.

OIDC variables: `CLAMAV_IDENTITY_OIDC_ENABLED`, `CLAMAV_IDENTITY_OIDC_ISSUER`, `CLAMAV_IDENTITY_OIDC_CLIENT_ID`, `CLAMAV_IDENTITY_OIDC_CLIENT_SECRET`, `CLAMAV_IDENTITY_OIDC_CLIENT_SECRET_FILE`, `CLAMAV_IDENTITY_OIDC_SCOPES`, `CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM`, `CLAMAV_IDENTITY_OIDC_GROUPS_VIEWER`, `CLAMAV_IDENTITY_OIDC_GROUPS_OPERATOR`, `CLAMAV_IDENTITY_OIDC_GROUPS_ADMIN`, `CLAMAV_IDENTITY_OIDC_BUTTON_LABEL`, `CLAMAV_IDENTITY_OIDC_LOGOUT_MODE`. Standard Spring aliases: `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI`, `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID`, `_CLIENT_SECRET`, `_SCOPE` with their complete full prefix. The callback remains `/login/oauth2/code/clamav`.

Secret encryption bootstrap: `CLAMAV_AUTH_SETTINGS_KEY_FILE` (persistent raw key path) **or** `CLAMAV_AUTH_SETTINGS_KEY` (base64 of exactly 32 bytes). The app otherwise keeps `conf/auth-settings.key`. UI-managed providers override bootstrap after being saved. Clear the saved provider appropriately through UI rather than expecting an environment edit to override it.

### 20.4 Metrics, Actuator, API behavior, daemon observer

| Environment/Spring property | Default / effect |
|---|---|
| `CLAMAV_METRICS_SCRAPE_TOKEN` | Empty; bootstrap token >=24 characters for scraping |
| `MANAGEMENT_SERVER_PORT` | Unset, shares UI listener 8080; set 9091 for isolated management context |
| `MANAGEMENT_SERVER_ADDRESS` | Bind address for management listener (e.g. `0.0.0.0` inside container with network isolation) |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health,info,metrics,prometheus`; auth gates still apply |
| `clamav.metrics.enabled` | Derived from token unless explicitly set or overridden by UI |
| `clamav.metrics.clamd-poll-ms` | `30000` ms |
| `clamav.metrics.clamd-max-endpoints` | `12` (clamped to 1–12) |
| `clamav.metrics.clamd-initial-delay-ms` | `5000` ms |
| `api.v1.scan-rate-limit-per-minute` | `30` per instance |
| `api.v1.max-page-size` | `100` |
| `api.v1.clamd.database-timezone` | `UTC` by default; change if daemon VERSION timestamp is from another timezone |
| `api.v1.clamd.max-database-age-hours` | `168` |
| `CLAMAV_CONFIG_PATH` | Empty; explicit `clamd.conf` path, with read-only mount |
| `FRESHCLAM_CONFIG_PATH` | Empty; optional `freshclam.conf` path |
| `CLAMAV_CONFIG_DISCOVERY_ENABLED` | `true` with a configured ClamD path; UI can switch to inspection-only |
| `CLAMAV_CONFIG_POLL_MS` | `30000` ms |
| `clamav.config.initial-delay-ms` | `1000` ms |
| `clamav.integrations.settings-file` | `conf/integrations.properties` |

For keys shown as **lowercase dotted Spring properties**, use exact JVM system properties when environment binding is ambiguous:

```yaml
services:
  clamav-web-client:
    environment:
      JAVA_TOOL_OPTIONS: >-
        -Dapi.v1.clamd.database-timezone=Europe/Bucharest
        -Dapi.v1.scan-rate-limit-per-minute=30
        -Dclamav.metrics.clamd-poll-ms=30000
```

Do not blindly replace `JAVA_TOOL_OPTIONS` if it already contains LDAP CA truststore settings. Use one merged string. `TZ=Europe/Bucharest` controls the container process timezone; `api.v1.clamd.database-timezone` separately controls parsing a ClamD VERSION timestamp which may be UTC on another machine.

### 20.5 External REST scanner adapter — full set

| Environment variable | Default | Purpose |
|---|---|---|
| `CLAMAV_REST_URL` | Empty | Origin of **external compatible** REST scanner, e.g. `http://192.168.68.144:3000` |
| `CLAMAV_REST_API_KEY` | Empty | Upstream secret (when used) |
| `CLAMAV_REST_AUTH_MODE` | `x-api-key` | Upstream auth mode |
| `CLAMAV_REST_FORM_KEY` | `FILES` | Multipart field for REST uploads |
| `CLAMAV_REST_TIMEOUT_SECONDS` | `10` | Regular upstream request timeout |
| `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` | `3600` | Long-running upload timeout |
| `CLAMAV_REST_REPORT_MAX_JOBS` | `5000` | Upper bound when aggregating reports |
| `CLAMAV_REST_STATS_PATH` | `/api/v1/stats` | Upstream stats endpoint |
| `CLAMAV_REST_JOBS_PATH` | `/api/v1/jobs` | Upstream live job endpoint |
| `CLAMAV_REST_HISTORY_PATH` | `/api/v1/history` | Upstream history |
| `CLAMAV_REST_SCAN_HISTORY_PATH` | `/api/v1/scan-history` | Upstream scan history |
| `CLAMAV_REST_WEBHOOKS_PATH` | `/api/v1/webhooks` | Available upstream webhook definitions |
| `CLAMAV_REST_SCAN_PATH` | `/api/v1/asyncscan` | Upstream scan submission path |
| `CLAMAV_REST_WEBHOOK_FIELD` | `webhookId` | Field name for upstream webhook choice |

The URL must not contain userinfo, query or fragment; paths are validated and redirects are not blindly followed. Startup changes require app recreation. This adapter does **not** control native ClamD scan mode, local quarantine or local API credentials. If the upstream has no suitable endpoint, the corresponding UI action will not work; inspect its own API spec.

### 20.6 `clamctl` and MCP client-side environment (not Java Compose)

| Name | Role |
|---|---|
| `CLAMAV_SERVER` | HTTPS server base URL for `clamctl` |
| `CLAMAV_API_TOKEN` | Preferred Bearer token |
| `CLAMAV_API_KEY` | Alias after API_TOKEN |
| `CLAMAV_USERNAME` / `CLAMAV_PASSWORD` | Basic credentials fallback, subject to CSRF for writes |
| `CLAMAV_CONFIG` | Client's private JSON config path |
| `CLAMAV_CA_FILE` | Client CA bundle for internal TLS certificate |
| `CLAMAV_MCP_HTTP_TOKEN` | Separate HTTP MCP bridge token |

The Java Web UI does **not** read these CLI environment variables as an embedded MCP server. They belong to the process that launches `clamctl`.

## 21. SQL migrations, upgrading, data integrity and PostgreSQL legacy OID

**New empty DB:** Flyway applies vendor-specific V1, Java V2 reconciliation, V3 external identity and V4 authentication settings migrations; Hibernate uses **`ddl-auto=validate`**. **Populated old DB:** inventory and clone first; the migration cannot safely guess an arbitrary pre-Flyway schema.

Recommended upgrade checklist:

1. Record running image tag and **digest**, current Compose and application version, JDBC URL/DB engine, mount paths and known healthy login.
2. Stop writers for consistent H2 backup; copy entire `./data` and `./conf` including `auth-settings.key` and any `integrations.properties`. For PostgreSQL take a consistent **`pg_dump -Fc`** of app DB in addition to mounts. Protect backups from exposure.
3. Restore a staging clone and test the exact new image/migration there. Inventory `app_users`, `app_user_roles`, `clamd_endpoints`, `scan_jobs`, `audit_events`, `watched_directories`, `processed_files`, and later `api_credentials`, `external_identities`, authentication settings.
4. Check for `flyway_schema_history` before any baseline. If **legacy populated** schema has no Flyway history and you have verified it matches V1, follow the guarded [Phase 2 database migration guide](PHASE2_DATABASE_PORTABILITY.md). Only on a **restored clone** consider one-time `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`, then return it to `false`. Do not apply indiscriminately to unrecognized schemas.
5. For legacy PostgreSQL `scan_jobs.found_viruses_json` and `scan_jobs.error_message` columns, check whether they are `oid` Large Objects rather than `text`:

```sql
SELECT column_name, data_type
FROM information_schema.columns
WHERE table_schema = current_schema()
  AND table_name = 'scan_jobs'
  AND column_name IN ('found_viruses_json','error_message');
```

6. Historical OID columns require a separate verified conversion with DBA planning and backups. The application intentionally does **not** silently rewrite them. Avoid enabling `APP_DB_POSTGRES_LOB_AUTO_MIGRATE=true` in production without first studying exactly what the opt-in migration does and its locking/rollback consequences. `SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_LOB_NON_CONTEXTUAL_CREATION=true` alone does not convert historical OID data.
7. Update image **without changing SQL/conf/data mounts**. Validate `docker compose config --quiet`, pull or select tested local image, then recreate **only** the Web UI:

```bash
docker compose pull clamav-web-client            # when using the registry image
docker compose up -d --no-deps --no-build clamav-web-client
docker compose logs --tail=200 clamav-web-client
```

8. Verify startup, absence of Flyway mismatch errors, SQL job/user counts, local admin recovery, any LDAP/OIDC provider, endpoint reachability, scan results and audit, persistence after restart, metrics and REST bridge if enabled. Hard-refresh browser assets if necessary.
9. Roll back **application image and matching SQL/data/conf snapshot together** if migration made irreversible changes. Restoring only an older image may be incompatible with a newer schema. Never delete `flyway_schema_history` or call `flyway repair` as a generic fix.

**Database limitations:** a standalone H2 database cannot be shared among running replicas. Even with PostgreSQL, queue ownership, watches, uploads and per-instance rate limits are not coordinated as a supported multi-replica HA product. Do **not** scale the Web UI service beyond one active instance against the same DB. A SQL outage may need an application restart/reconciliation to recover interrupted jobs; inspect jobs before resubmitting.

## 22. Reverse proxy, certificates, secrets and network security

### 22.1 Built-in HTTPS recognition for Bearer tokens behind a proxy

The application packages
`server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:framework}`.
A normal Docker build enables HTTPS recognition for TLS-terminating reverse
proxies and Kubernetes Ingress:

```bash
docker build --pull -t clamav-web-client:2.0.0-local .
```

The image also runs as UID/GID `10001:10001`. Both fixes are in the source;
no helper script or `--trusted-proxy` flag is required. Compose examples use the
same `framework` default. Remove an old explicit
`SERVER_FORWARD_HEADERS_STRATEGY=none` override to inherit it. To deliberately
ignore forwarded headers for direct access, set that variable to `none` and
use direct HTTPS for machine credentials.

The proxy must replace incoming `Forwarded` and `X-Forwarded-*` headers and be
the only permitted backend entry point. Use the external HTTPS URL in
`clamctl`, MCP and API clients. See [Reverse proxy TLS](REVERSE_PROXY_TLS.md).

A `403 tls_required` / `Bearer tokens require HTTPS` behind a TLS-terminating
proxy means the original HTTPS scheme is missing or a runtime setting disabled
forwarding. The Bearer filter still requires HTTPS, valid credentials and the
necessary scopes. Client certificate flags do not change server scheme recognition.

### 22.2 Nginx and network configuration

Run the Web UI behind HTTPS on a trusted hostname; don't publish username/password or machine Bearer tokens over plain HTTP. A minimal Nginx configuration (adjust certificate paths, DNS, host proxy reachability and body limit) is:

```nginx
map $http_upgrade $clamav_ws_connection {
    default upgrade;
    ''      close;
}
server {
    listen 443 ssl;
    server_name clamav.example.com;
    ssl_certificate /etc/nginx/certs/clamav.crt;
    ssl_certificate_key /etc/nginx/certs/clamav.key;
    client_max_body_size 2048m;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header Forwarded "";
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-Host $host;
        proxy_set_header X-Forwarded-Port 443;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Forwarded-Prefix "";
        proxy_set_header X-Forwarded-Ssl "";
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $clamav_ws_connection;
        proxy_read_timeout 3600s;
    }
}
```

`proxy_pass` addresses **the Docker host reachable from Nginx**, not blindly `localhost` when proxy and UI run on different machines. The packaged `framework` default requires the proxy to be the exclusive inbound path and replace untrusted headers. This Nginx example assumes it is the TLS edge; configure an explicit trust chain for multiple proxies. The proxy must support `/ws/clamdtop` upgrades. Set upload limit appropriately for real capacity, not just the application's 2 GiB default.

**Network hardening:** restrict `clamd:3310` to trusted internal clients only; restrict SQL 5432/3306 and management 9091 similarly. When using remote ClamD, verify firewall/ACL, correct host and storage policy. Make `./conf`, `.env`, LDAP bind secret files, OIDC client secrets and token files owner-readable only. Back up `conf/auth-settings.key` safely. Trust private CAs in the **Java JVM** when it connects to LDAPS/OIDC/HTTPS REST, and separately trust the Web UI certificate CA in `clamctl`/MCP clients. A trusted CA alone is not sufficient: hostname/SAN verification must also succeed. Avoid global `verify=false` or insecure HTTP redirects.

**Security revision:** the uploaded source changed Java dependencies to address findings observed in an earlier container scan. It has a documented local Maven/HTTP smoke check, but not a universal production sign-off or proof that the published `v2` tag already contains it. Scan your **actual** running image, including OS/JRE layers, and pin deployment by verified digest where appropriate.

## 23. `clamctl` CLI — installation, commands and exit codes

`clamctl` is a **separate** portable executable available for Linux, macOS and Windows on AMD64/ARM64 in the uploaded full-source archive's `cli/dist`. Choose the binary matching your CPU and OS. The archive includes `cli/dist/SHA256SUMS`; verify the artifact checksum before use. A Git checkout may not include compiled release binaries; obtain a published release or build from `cli/` with Go as documented in [`cli/README.md`](../cli/README.md).

**macOS Apple Silicon example** when the full-source ZIP is unpacked:

```bash
chmod +x cli/dist/darwin_arm64/clamctl
./cli/dist/darwin_arm64/clamctl --help
# Optional install, after checking SHA256SUMS:
sudo install -m 0755 cli/dist/darwin_arm64/clamctl /usr/local/bin/clamctl
```

**Configure the HTTPS server, CA and credentials** (one-time):

```bash
clamctl config set-server https://clamav.example.com --ca-file /secure/web-ca.pem
clamctl login --token-stdin < /secure/clamav-api-token
clamctl status --json
clamctl endpoints list
clamctl scan upload ./sample.txt --wait --json
clamctl scan path /scandir/incoming/sample.txt --wait
clamctl jobs list --status RUNNING
clamctl jobs get YOUR_JOB_ID
clamctl jobs watch YOUR_JOB_ID
clamctl audit list --limit 20
clamctl logout
```

You need corresponding API scopes (`health:read`, `endpoints:read`, `scan:upload`, `scan:path` with permitted roots, `jobs:read`, `audit:read`). Waiting for completion also needs `jobs:read`. Output defaults to readable tables; `--json` is machine-friendly, job watches emit NDJSON. File upload with `clamctl` sends bytes from the **CLI host**; server path scans operate on the **server filesystem**, not on the CLI machine. HTTPS certificate verification is mandatory; do not expect an insecure-verify flag.

| Exit status | Interpretation |
|---|---|
| `0` | Success / accepted / clean result |
| `1` | Error / daemon unavailable / operation failed |
| `2` | Usage or configuration error |
| `3` | Malware-positive scan verdict |
| `4` | Wait timeout |
| `130` | Locally interrupted operation |

Interrupting a wait does **not** retract an already accepted server job. Default saved client config contains sensitive credentials; protect the file (POSIX mode `0600`) and use environment/secret managers in CI. The CLI can also authenticate with local Basic credentials but the official client handles the necessary CSRF flow.

## 24. MCP — assistant-facing tools, resources and deployment

MCP is provided by the **separate `clamctl` executable**, not by a listening endpoint automatically enabled in the Java Web UI. Install/configure the CLI's HTTPS server and a least-privilege `/api/v1` Bearer token, then configure an MCP client to launch **stdio**:

```bash
clamctl mcp serve --upload-root /srv/artifacts
```

Use absolute allowlisted **MCP-host** upload roots; they restrict which files the assistant may send. MCP server-path tools are different: their input path is a **Web UI/ClamD server** path subject to Admin allowed roots and token path-root restrictions.

**Five exposed tools:** `clamav_scan_file` (MCP-host upload), `clamav_scan_path` (server-side scan), `clamav_get_job` (inspect result/progress), `clamav_check_health` (reachability/readiness), `clamav_list_endpoints`. **Three resources:** `clamav://stats`, `clamav://jobs/recent`, `clamav://audit/recent`. Token scopes determine which each can access. Example client JSON configurations are in `examples/mcp/stdio.json` and `examples/mcp/vscode.json`; update binary location, server CA, root and token credentials.

**Optional Streamable HTTP:** launch `clamctl mcp serve --transport http ...` (see `docs/MCP.md` for exact flags) with a separate 32+ character bridge token, **loopback-only listener** and Origin restrictions. Use a same-host TLS gateway if remote assistants must reach that bridge; do **not** bind to public `0.0.0.0`. The HTTP bridge token is distinct from the API Bearer token used to contact Web UI. Default local upload limit is about 100 MiB and operations have bounded waits/timeouts; adjust safely. There is no embedded OAuth MCP authorization server, old SSE transport or subscription feed. Closing an assistant connection does not automatically cancel an accepted server scan.

**Safe MCP rollout:** limit token scopes and endpoint IDs, limit path roots and upload roots, start read-only health/list endpoints tests, then test scan on harmless files, and audit every machine action. See [`docs/MCP.md`](MCP.md) for full stdio/HTTP configs, tool arguments, return fields and diagnostics.

## 25. End-to-end verification and EICAR detection

For the non-root, reverse-proxy HTTPS and webhook changes, start with
[the dedicated upgrade verification guide](UPDATE_AND_VERIFY.md). It includes
expected HTTP statuses, local CA handling and focused timeout/address-policy tests.

Run through the checks after install or upgrade:

1. **Containers:** `docker compose ps`; `docker compose logs --tail=100 clamav-web-client`; verify `clamav-server` is started, reachable and has current signatures.
2. **Login:** generated admin password is usable; rotate it; test local recovery plus one low-privilege account.
3. **Daemon:** Main PING/VERSION/STATS, ClamdTop Connect and endpoint selection all function. For a remote ClamD test network reachability from **inside the Java container**.
4. **Simple scan:** Upload a small harmless file; verify accepted job, `FINISHED` + `OK`, timestamps, one audit entry.
5. **EICAR:** Use the standard **harmless EICAR antivirus test string** to validate detection; never execute it as code. Follow [`scripts/smoke-eicar.sh`](../scripts/smoke-eicar.sh) or [`docs/HOME_LAB_TESTING.md`](HOME_LAB_TESTING.md) in a test environment. Expect `VIRUS_FOUND`, signature, audit and optional webhook. Avoid putting EICAR in watched production directories accidentally.
6. **Path mode:** Verify `/scandir` mount on **both** app and ClamD, Admin allowed root and correct native upload setting; ensure ClamdTop displays a filename when the native path command is used.
7. **Watch:** Enable a narrow root, add one file, look for a single WATCH job; test unchanged-file suppression.
8. **Quarantine:** Use a controlled test destination and verify file existence separately from the recorded job verdict/evidence; check source permissions.
9. **REST:** If enabled, check upstream status and reports with its own service online; do not conflate external and local jobs.
10. **Database:** Restart only Web UI, confirm users/job history/endpoint records persist, no Flyway/LOB validation exceptions. Use a restored clone for schema changes.
11. **Identity:** Test local and configured LDAP/OIDC with user/group examples; verify viewer cannot access Administration routes. Test OIDC issuer and callback through real HTTPS hostname.
12. **Monitoring:** Unknown/no token returns 403, saved token returns metrics 200 on the appropriate listener; Grafana graphs real samples and not assumed zero data.
13. **CLI/MCP:** `clamctl status --json`; upload a safe fixture; confirm job and token scope restrictions; MCP health and safe file scan.
14. **Settings and backup:** Verify `conf` and `data` ownership, saved UI options after restart, decryptable LDAP/OIDC secrets and a recoverable complete database + mount backup.

The provided source has tests and security verification notes, but a test report **does not establish** that your particular LDAP/AD servers, proxy, ClamD storage, PostgreSQL schema and Docker Hub tag are working. Perform these live checks before production rollout.

## 26. Troubleshooting by symptom

| Symptom | Most likely investigation / fix |
|---|---|
| Nginx `502` and `conf/integrations.properties: Operation not permitted` | Backend startup failed while setting owner-only permissions; migrate existing `conf`/`data` ownership to UID/GID 10001. `writable=true` alone does not prove ownership. See `NON_ROOT_CONTAINER.md` |
| curl error 60 with a local/self-signed HTTPS certificate | Trust the correct local CA/server certificate using `--cacert` and match the URL to its SAN; public DNS is not required. See `UPDATE_AND_VERIFY.md` |
| Login page loops or SSO redirect wrong | Check HTTPS hostname, trusted proxy headers (`SERVER_FORWARD_HEADERS_STRATEGY`), exact OIDC `/login/oauth2/code/clamav` callback |
| `403 tls_required` for Bearer tokens behind HTTPS proxy | Use the packaged `framework` default, remove old `none` overrides, and verify the trusted edge sends HTTPS scheme and blocks direct backend access; see Section 22.1 |
| Cannot log in as `admin` | Check persistent H2/SQL database, use actual bootstrap file for *new DB only*, local account status. Do not delete database to reinitialize |
| Web UI starts but cannot scan | Check endpoint Enabled, ClamD host/port **from inside container**, daemon signatures, SQL readiness and policy |
| ClamdTop black/blank or Connect fails | WebSocket `/ws/clamdtop` upgrade, proxy timeouts, native binary availability in image, daemon network/socket permissions |
| ClamdTop shows stream instead of path | INSTREAM mode or fallback; for filenames use native path command plus **identical absolute mounts** across UI and ClamD |
| `Can't access file`/path rejected | `/scandir` not mounted on UI or ClamD, mount is different absolute path, `app.allowedScanRoots`, permissions/symlink, inherited `ExcludePath` |
| File too large | Proxy limit, Spring multipart ceiling, `app.upload.maxBytes`, ClamD `StreamMaxLength`/`MaxFileSize`/`MaxScanSize`, available disk |
| Jobs persist after client timeout | Expected asynchronous behavior; query job ID before retry, `--wait` needs jobs:read |
| Watch sends no jobs | `app.watch.enabled`, watched row Enabled, allowed mount, no change in mtime/size, 30s polling/depth/1,000-entry bound |
| `VIRUS_FOUND` but no quarantine file | Disabled quarantine, read-only original source, directory permission, recorded evidence not proof of filesystem move |
| REST monitor unconfigured/unavailable | `CLAMAV_REST_URL` points to missing/non-compatible external scanner, wrong API key/path, upstream connectivity |
| Webhook doesn't arrive | Direct vs upstream REST webhook, address policy/private receiver opt-in, timeout or queue-drop warnings, receiver status; see `WEBHOOK_RESILIENCE.md`; no durable retry queue |
| LDAP TLS bind fails | Java trusts CA, certificate SAN matches LDAP hostname, correct LDAPS/StartTLS choice, bind DN/filter/group schema |
| LDAP logs in but wrong role | Group search base/filter/member attribute, nested group depth, explicit VIEWER/OPERATOR/ADMIN mappings |
| OIDC logs in but no privilege | IdP must actually emit groups claim; confirm path/values, do not rely only on requested scope |
| `Authentication` settings lost after restart | Check `./conf` persistence, SQL persistence, unchanged auth-settings encryption key, Save (not only Test) |
| PostgreSQL Flyway validation error | Inspect existing schema/history, migrations and Hibernate validation; never force `baseline=true` on unknown production DB |
| PostgreSQL `oid`/large object mismatch | Inspect `scan_jobs` data type, follow guarded migration on a restored clone; JDBC LOB flag is not schema conversion |
| SQL driver error | Match JDBC prefix, credentials and driver; remove stale H2-only URL/driver/dialect overrides |
| Prometheus always 403 | No/incorrect separate scrape Bearer token, disabled UI setting, wrong management listener port |
| Metrics empty or NaN | ClamD unreachable/unsupported sample or old timestamp; unknown values are not zeros; inspect Configuration mapping |
| UI shows `clamd.conf` but daemon ignores changes | Observer reads disk; it does not reload ClamD; check daemon own active configuration and service restart/reload |
| CLI certificate error | Correct hostname/SAN and trusted client `--ca-file`, TLS origin; no insecure TLS switch |
| API 401/403 | Token expired/revoked/missing scope or endpoint/path restriction; legacy Basic+CSRF restrictions; HTTPS needed |
| API Scan jobs not in Jobs | External REST bridge and managed `/api/v1` store **separate** job histories |
| Upload/Clamd service healthy but first scans fail | Wait for FreshClam signature availability and ClamD readiness; `depends_on: service_started` alone is not health check |

Useful diagnostics (**redact secrets**):

```bash
docker compose ps
docker compose logs --tail=250 clamav-web-client
docker compose exec -T clamav-web-client sh -lc 'ls -ld /app/conf /app/data; env | grep -E "^(CLAMAV_HOST|CLAMAV_PORT|TZ|SPRING_DATASOURCE_URL)="'
docker compose exec -T clamav-web-client sh -lc 'command -v clamdtop; command -v script'
# With API token:
curl -H 'Authorization: Bearer YOUR_TOKEN' https://clamav.example.com/api/v1/health/clamd
```

**Don't run `docker compose config` into public issue comments** without checking interpolation and redacting sensitive values. Never share original complete server logs if they contain credentials, filenames, cookies or database error details that identify systems.

## 27. Operational security checklist

- [ ] TLS reverse proxy for public login/API; WebSocket upgrade and correct trusted forwarded headers.
- [ ] Local administrator rotated; break-glass account working, accounts minimum privilege.
- [ ] ClamD TCP/private socket and database/management ports unreachable from untrusted clients.
- [ ] Single active Web UI instance; consistent SQL + `conf` + `data` off-host backups, recovery test.
- [ ] File roots restricted; native path mode **only** when shared storage is correct, quarantine outside watches.
- [ ] LDAP/AD uses trusted LDAPS/StartTLS; OIDC issuer/callback/claims and roles verified.
- [ ] API tokens scoped, expiring, restricted to endpoints/roots, revoked after use.
- [ ] Webhook receivers use trusted protocols/permissions and do not unintentionally leak sensitive target paths.
- [ ] Metrics token separate from API/admin tokens; monitor labels/signatures and restrict 9091.
- [ ] Real ClamD signatures fresh; positive EICAR smoke test on safe fixtures; ClamdTop operational.
- [ ] Image tag/digest and security revision verified; current runtime image scanned, not just source dependency versions.
- [ ] Flyway baseline disabled by default; migration tested on restored clone; no ad hoc Hibernate update.

## 28. Further reference inside the repository

This guide deliberately consolidates the complete deployment and user workflow. Source details and validation transcripts for advanced audits still exist:

| File | Topic |
|---|---|
| [`docs/SECURITY_DEPENDENCY_UPDATE.md`](SECURITY_DEPENDENCY_UPDATE.md) | Security.1 Spring Boot 4 upgrade, vulnerability and verification notes |
| [`docs/PHASE1_API_SECURITY.md`](PHASE1_API_SECURITY.md) | Token authorization, API contract and security tests |
| [`docs/PHASE2_DATABASE_PORTABILITY.md`](PHASE2_DATABASE_PORTABILITY.md) | Flyway baseline, legacy PostgreSQL OID handling and rollback |
| [`docs/PHASE3_ENTERPRISE_IDENTITY.md`](PHASE3_ENTERPRISE_IDENTITY.md) | LDAP/OIDC design and validation matrix |
| [`docs/PHASE31_AUTH_MANAGEMENT.md`](PHASE31_AUTH_MANAGEMENT.md) | UI-managed auth persistence and controls |
| [`docs/DAEMON_CONFIGURATION_SYNC.md`](DAEMON_CONFIGURATION_SYNC.md) | Detailed clamd/freshclam inheritance and precedence |
| [`docs/PHASE4_OPERATIONS.md`](PHASE4_OPERATIONS.md) | Daemon metrics and config observer architecture |
| [`docs/HOME_LAB_TESTING.md`](HOME_LAB_TESTING.md) | Operator acceptance checklist and lab scenarios |
| [`docs/MCP.md`](MCP.md) | MCP implementation, tools, resources, Streamable HTTP setup |
| [`cli/README.md`](../cli/README.md) | Every CLI command, flag, installation and exit behavior |
| [`dashboards/`](../dashboards/) | Grafana JSON, Prometheus job example, alert YAML |
| [`examples/mcp/`](../examples/mcp/) | MCP client JSON configurations |
| [`examples/home-lab/`](../examples/home-lab/) | Prometheus/Grafana lab Compose |
| [`src/main/resources/openapi/clamav-v1.yaml`](../src/main/resources/openapi/clamav-v1.yaml) | Machine-readable REST API specification |
| [`conf/clamav-web-client.properties.example`](../conf/clamav-web-client.properties.example) | Initial persisted application setting keys |

**Maintenance rule:** If the code changes, validate the feature and update this guide (plus the relevant example) in the same commit. Do not present staged or unverified runtime behavior as completed deployment validation.
