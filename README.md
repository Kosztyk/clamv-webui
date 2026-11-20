# ClamAV Web UI v2.1.0

> **Stable release tag:** `v2.1.0`  
> **Docker Hub image:** `kosztyk/clamav-web-client:v2.1.0`  
> **Release date:** 20 November 2026
>
> v2.1.0 consolidates the fixes raised in issues #25–#57, including ClamAV 1.4.7 security remediation, PostgreSQL legacy-upgrade auto-repair before Flyway V9, persistent Direct-HTTP session-cookie configuration, hardened CI/release gates, signed/scanned release artifacts, and digest-scoped OpenVEX handling for Alpine `CVE-2026-107891`. See [v2.1.0 release notes](docs/releases/v2.1.0.md), [CHANGELOG](CHANGELOG.md), and [upgrade/release procedure](RELEASE.md).

### Issue #55 — API v1 contract compatibility gate (included in v2.1.0)

Every API controller, OpenAPI or Go client change now activates **`api-contract`** in the single required GitHub Actions **`gate`** and also selects the real built-server `clamctl` integration job. The gate verifies Java controller/CLI routes against `openapi/clamav-v1.yaml`, lints it with **Spectral 6.17.1** and blocks ERR/WARN-level breaking changes using **oasdiff v1.11.8** against **the latest stable release’s OpenAPI spec and the PR/push base**. The Maven/JUnit **`Issue55OpenApiContractIntegrationTest`** additionally exercises actual Spring MockMvc responses and serialized DTO field contracts for success, validation and not-found cases. New submitted-job, progress, token and audit response schemas are documented. The tag-triggered release workflow repeats lint and compatibility checks before building release artifacts; no change to authentication, Configuration Center or production deployment is required. See [API contract gate](docs/ISSUE55_API_CONTRACT_GATE.md) and [validation evidence](ISSUE55_VALIDATION_RESULTS.md). Local Maven, Spectral and oasdiff checks passed. The GitHub workflow runs the checks on pull requests and release tags.

### Issue #54 — Reproducible, signed clamctl release binaries (included in v2.1.0)

The unified CI `gate` now includes separate **`cli-test`**, **`cli-build`** and **`cli-integration`** jobs. Every CLI change runs Go unit/vet/race tests, pinned **staticcheck 2026.1** and **govulncheck v1.1.4**; release builds compile all six platforms twice with `-trimpath`, no build ID, `CGO_ENABLED=0` and a fixed commit-derived `SOURCE_DATE_EPOCH`, and compare SHA-256 checksums before publishing. The version is inherited from `pom.xml` and checked using `clamctl --json --version`. The release process **keyless-signs a separate six-binary `clamctl-SHA256SUMS` manifest**, attaches a Sigstore bundle, verifies the exact GitHub release-workflow identity before drafting **and** after approval, and keeps the existing release-wide `SHA256SUMS`. See [CLI quality, signing and verification](docs/ISSUE54_CLI_QUALITY_SIGNED_RELEASE.md) and [validation limitations](ISSUE54_VALIDATION_RESULTS.md). The local Java and Go integration tests and six-target reproducibility build passed. GitHub releases generate the Cosign signatures.

### Issue #53 — Java quality gates (included in v2.1.0)

The unified GitHub `gate` now includes a JDK **21/25** test matrix with JaCoCo coverage enforcement (82% minimum), and a `java-quality` job with **Spotless formatting, SpotBugs/FindSecBugs, PMD and Maven Enforcer** dependency convergence. New static-analysis findings are compared against the exact PR/push base; existing reports and coverage evidence are uploaded. The application targets Java 21, so JDK 17 is intentionally not claimed as supported. The tag-triggered release repeats quality validation. The local build measured 83.37% line coverage; see [issue #53 quality guide](docs/ISSUE53_JAVA_QUALITY_GATES.md) and [validation report](ISSUE53_VALIDATION_RESULTS.md).

> **Current application and CLI version:** Defined only by the top-level `pom.xml` project version. Maven filters this value into the running application, setup/login/footer and OpenAPI; the CLI builder reads the same POM. Local Compose tags are intentionally version-neutral (`local`). Release tags use `v` plus the exact POM version. Historical beta/issue headings below are documentation, not active version declarations. Run `python3 scripts/check-versions.py` to verify all active references.

### Issue #41: CSV spreadsheet safety without data loss

REST report CSV exports preserve the original value when protecting against spreadsheet formula injection. Dangerous prefixes (`=`, `+`, `-`, `@`, `|`, `%` and leading line breaks/tabs) are **prefixed** with an apostrophe, never replaced: `-5` exports as `'-5`, `==x` as `'==x`. Standard CSV quote escaping and ordinary values are unchanged; `format=json` remains lossless. The job-history and detailed-report CSV exporters share one encoder. See [issue #41 documentation](docs/ISSUE41_CSV_EXPORT_INTEGRITY.md) and [validation](ISSUE41_VALIDATION_RESULTS.md).

### Issue #39: viewer-safe monitoring and signed private webhooks

VIEWER accounts receive numeric ClamD health/queue summaries (not raw STATS, queued filenames, hostnames or the native PTY). OPERATOR/ADMIN retain the interactive monitor. **Webhook payloads omit server paths and raw error text by default**, and all managed-scan webhook deliveries require an HMAC-SHA256 `X-Signature` header with a write-only signing key. Configure the key and optional sensitive fields in **Administration → Configuration Center → Notifications → Webhook notifications**. Enabled webhooks without a signing key fail closed after upgrade. See [the issue #39 guide](docs/ISSUE39_VIEWER_WEBHOOK_SECURITY.md) for migration, Compose variables, receiver verification and tests.

> **Post-audit cumulative fixes (v2.1.0beta2, issues #13–#35):** Unchanged `clamd.conf` polling no longer interrupts directory scans or begins an unnecessary endpoint transaction; `/rest-monitor/upload` receives early HTTP 413; `/api/v1/jobs` uses scoped scalar projections; multipart defaults reserve framing overhead; retention runs on indexed age-first V7 keysets. See [cumulative audit remediation and validation](docs/CUMULATIVE_AUDIT_REMEDIATION_13-35.md). Existing V1–V6 Flyway migrations remain unchanged.

> **Issue #33 (v2.1.0beta2):** The ClamAV configuration observer now polls at its actual configurable interval instead of opening a one-second transactional heartbeat. Mounted configuration files are parsed outside DB transactions, default-endpoint synchronization uses only a short write transaction, and immutable ClamAV scan clients are cached by endpoint ID/connection settings. Probe deadlines remain fresh. See [issue #33 performance notes](docs/ISSUE_33_CONFIGURATION_POLL_PERFORMANCE.md). All earlier fixes #13–#32 remain present.

> **Issue #30 (beta2):** Upload processing now supports a same-filesystem servlet multipart move, early authenticated 413 checks from request length, and an owner-only Docker multipart spool. Multi-file and chunked bodies remain subject to full server-side per-file checks. See [upload performance and limitations](docs/ISSUE_30_UPLOAD_PERFORMANCE.md). All prior #13–#29 fixes remain present.

> **v2.1.0 — release source:** This archive contains the complete 2.1 source snapshot and deployment updates, including the security fixes for issues #13–#29. The default for fresh installations is secure INSTREAM upload scanning; native ClamD filename scanning is optional and requires an explicitly isolated, tested filesystem share. Review [final v2.1.0 release notes](docs/releases/v2.1.0.md), [deployment examples](docs/BETA_DEPLOYMENT_EXAMPLES.md), and [production scanning guidance](docs/PRODUCTION_SCANNING.md). Release verification includes hosted CI and live Docker scans.

> **Scanning deployment update (v2.1.0):** Fresh installs now default to INSTREAM (native path OFF, fallback ON), because ClamAV daemons cannot read private `0700`/`0600` uploads unless filesystem access is explicitly configured. See [secure production scanning and upgrade guidance](docs/PRODUCTION_SCANNING.md). Existing saved GUI settings do not change automatically. The root `docker-compose.yml` is a sanitized fresh-install example, **not a replacement for an existing PostgreSQL/NGINX deployment Compose**.

> **Issue #32 (beta2):** External REST job/history reports use a shared five-second single-flight snapshot cache, bounded 10 MiB upstream responses (oversize → HTTP 502), generic upstream error JSON, and reduced polling for idle/hidden tabs. The new REST cache TTL is editable in Configuration Center. See [issue #32 performance and security notes](docs/ISSUE_32_REST_MONITOR_PERFORMANCE.md). Previous fixes #13–#31 are retained.

### Historical v2.1.0beta2 — Issue #29 directory-scan throughput and partial-error reporting

Eligible, exclusion-free directories use one guarded native `MULTISCAN` after checking the tree for symlinks, allowed-root boundaries, file types and size limits. Trees with exclusions or daemon errors use a shared four-worker scanner with 16 bounded outstanding tasks per job. Excluded subtrees are pruned; configured roots are resolved once per scan while file paths are still checked individually. A single unreadable file no longer discards detections: clean plus errors yields `ERROR` (not false `OK`), while malware plus errors yields `VIRUS_FOUND` with partial-scan details in the job view. No changes to database schema, Docker requirements or existing security controls. See [issue #29 scan policy and regression tests](docs/ISSUE_29_DIRECTORY_SCAN_PERFORMANCE.md).

### v2.1.0beta2 — Issue #28 job-history performance

Dashboard/scan previews and `/jobs` use summary-only SQL without large TEXT diagnostics. `/jobs` has page/size navigation and the live table refresh retains its selected page. Startup recovery performs an indexed bulk status reset followed by bounded (100-ID) queue batches instead of reading the entire job table. Full details remain accessible per job; the legacy `/api/jobs` list returns summary-compatible DTOs while `/api/jobs/{id}` retains full diagnostic details. New Flyway V5 indexes support all four database engines. See [issue #28 performance and migration notes](docs/ISSUE_28_JOB_HISTORY_PERFORMANCE.md).




### Issue #31: efficient clamdtop and REST metrics polling

The `/api/clamdtop` page, `/api/v1/metrics/clamd`, and scheduled Prometheus sampling share
a per-endpoint 2-second snapshot cache with a single in-flight refresh. Snapshot probes use
VERSION and STATS (no redundant PING), and hidden browser tabs pause the legacy STATS poller.
Adjust **ClamdTop snapshot cache TTL (ms)** under **Administration → Configuration Center → Monitoring → Prometheus & Grafana** (1000–3000 ms, default 2000; restart required). Alternatively set `CLAMAV_CLAMDTOP_CACHE_TTL_MS` in Docker Compose; an environment override locks the GUI field.
The v1 API returns the actual sample time as `checkedAt`.
The real interactive native `clamdtop` WebSocket remains live and unchanged.
See [issue #31 implementation and tests](docs/ISSUE_31_CLAMDTOP_POLLING.md).


## v2.1.0beta2 — database index improvements (issue #34)

Flyway **V6** adds endpoint/verdict-filtered job indexes, endpoint/username audit indexes, and an explicit user-role lookup index on PostgreSQL/H2. Existing V5 history and queue indexes from issue #28 are unchanged; InnoDB already indexes the user-role foreign key. These additions preserve all existing data and Flyway checksums, but large databases may require a maintenance window for index builds. See [issue #34 index reference and PostgreSQL EXPLAIN examples](docs/ISSUE_34_DATABASE_INDEXES.md).

## v2.1.0beta2 — admin input validation (issue #26)

New local users are lowercase and use `[a-z0-9._-]{3,64}`; usernames are checked case-insensitively and passwords are limited to 12+ characters and **72 UTF-8 bytes** for BCrypt. Additional TCP endpoints validate names, hostnames/IPs, ports and platforms, and invalid entries display form errors instead of HTTP 500. Configuration Center enforces numeric limits (1–64 workers, positive upload sizes, 5–3600s polling) and requires existing writable absolute custom storage directories; built-in relative paths are preserved for upgrades. The audit fix and all prior security safeguards remain unchanged. See [issue #26 validation documentation](docs/ISSUE_26_INPUT_VALIDATION.md).

## v2.1.0beta2 — audit logging reliability (issue #25)

All audit payloads are bounded to the existing database schema: `username`, `action`, `outcome`, `job_id`, and `ip` (64 characters); `details` (1024); `transport` (32); and `user_agent` (255). Audit writes use a separate `REQUIRES_NEW` transaction with a flush and best-effort error handling; audit failures are logged without failing already-completed scan submissions, endpoint management, login, or other actions. A 10 KiB User-Agent regression test is included. No audit table migration or retention-policy change is required. UI footers show **ClamAV Web UI v2.1.0** (without the former marketing tagline).

See [final v2.1.0 release notes](docs/releases/v2.1.0.md); the beta2 document is retained as historical validation evidence.

**Complete application source with Configuration Center and fixes for issues #10–#31 built in:** build normally:

```bash
docker build --pull -t clamav-web-client:local .
```

**Configuration Center:** configure connections, scanning, identity/API, notifications, network, monitoring and preferences from the GUI. Open `/setup/` directly on the published HTTP port; nginx, a domain and a certificate are optional. Confirm HTTP configuration on a trusted network before submitting credentials. Compose overrides remain supported and read-only; protected setup/recovery works before the database starts. See [setup, minimal Compose and recovery](docs/CONFIGURATION_CENTER.md), the [complete field reference](docs/CONFIGURATION_REFERENCE.md) and [validation](docs/CONFIGURATION_CENTER_VALIDATION.md).

**One settings editor:** duplicate Settings/Authentication forms and monitoring/observer controls are consolidated in Configuration Center. Existing values and credentials are preserved, and retired writes cannot bypass review. Scan Jobs now updates final status automatically. See [the consolidation audit and tests](docs/CONFIGURATION_CONSOLIDATION.md).

**Complete configuration audit:** the Configuration Center controls have persistence, deployment-override and activation checks. Runtime/browser tests cover the non-identity settings too. See [every field, its consumer and validation evidence](docs/CONFIGURATION_FIELD_AUDIT.md).

**Database connection tests:** PostgreSQL/MySQL/MariaDB tests now use a bounded budget based on the configured pool timeout, with driver-specific units and useful, secret-safe failures. A new H2 database shows an informational initialization message rather than a false connection error. See [database test behavior and regressions](docs/DATABASE_CONNECTION_TESTS.md).

The Dockerfile runs the application as **UID/GID `10001:10001`** (issue #10).
**Forwarded headers are ignored by default** (issue #13): direct clients cannot
forge the IP address or make plain HTTP count as HTTPS. To use TLS termination
(issue #11), explicitly select `native` and allowlist only your proxy's actual
backend address; the proxy must overwrite forwarded headers and restrict backend
access. Native mode without an allowlist trusts nobody. Existing explicit
`framework` overrides need migration. See [server deployment](SERVER_DEPLOYMENT.md),
[volume permissions](docs/NON_ROOT_CONTAINER.md) and
[proxy configuration and upgrade steps](docs/REVERSE_PROXY_TLS.md).

**Password lockout (issue #14):** HTTP Basic now shares the local form login's account-wide attempt budget. Five failures within 15 minutes lock that account for 15 minutes, even across client IPs; Basic requests then return `429 login_locked` with `Retry-After`, including with the correct password. Successful authentication before lockout resets failures. LDAP uses its own account-wide counter. See [password lockout behavior and tests](docs/BASIC_AUTH_LOCKOUT.md).

**Scanner isolation (issue #15):** the bundled default ClamD has no published host port and no application-data mount. The daemon image must be set to a verified digest in `.env`; the separate REST URL is optional. An optional **native filename** overlay shares only `data/uploads` read-only with a UID-matched clamd process (validate this before using it). See [directory preparation, safe networking and CI checks](docs/CLAMD_ISOLATION.md).

**Stable LDAP identity (issue #44):** LDAP principals now use a database-persisted directory identifier instead of the current LDAP URL/base DN. On the first upgrade startup, the application pins the legacy issuer exactly so existing principal hashes and audit attribution survive later hostname failover. Start with the old LDAP URL/base DN for that first startup. An optional **Stable LDAP directory ID** is available under **Administration → Configuration Center → Identity & API → LDAP → Advanced** for new deployments; an explicit ID must be chosen before first enabling/configuring LDAP. Docker may override it using `CLAMAV_IDENTITY_LDAP_DIRECTORY_ID`, but **existing deployments should leave this unset**. A pinned ID cannot be rotated without a planned identity migration. Flyway V8 applies to H2, PostgreSQL, MySQL and MariaDB. StartTLS retains the JDK's strict certificate hostname checks. See [upgrade, UI, migration and StartTLS tests](docs/ISSUE44_LDAP_STABLE_DIRECTORY_ID.md).

**Provider secret protection (issue #16):** changing an LDAP destination/bind identity or an OIDC issuer/client ID requires re-entering the credential before Test or Save. An unchanged destination still supports a blank secret field. This applies to Configuration Center, setup/recovery and legacy settings validation. See [protected fields, secret files and upgrade behavior](docs/PROVIDER_SECRET_BINDING.md).

**Watcher coverage (issue #17):** incremental scan cycles eventually traverse the full watched tree without the old 1,000-file or five-level limits. Each one-second continuation tick inspects up to 256 entries and batch-fetches at most 128 indexed fingerprints at a time. Each file's WATCH job and fingerprint commit together; failures are logged and uncommitted files remain eligible for retry. Hourly cleanup removes markers for deleted paths. See [watcher behavior, recovery and tests](docs/WATCHER_RELIABILITY.md).

**PATH/WATCH source quarantine hardening (beta2):** ClamD detection filenames are never treated as permission to move arbitrary host files. Quarantine rechecks each reported source against configured allowed roots and the originally authorized scanned file or directory. Rejections retain the virus verdict with a quarantine failure diagnostic. Scan-finalization metadata writes run through a dedicated Spring transaction bean, keeping scanning and filesystem work outside DB transactions. See [final v2.1.0 release notes](docs/releases/v2.1.0.md).

**Upload cleanup (issue #18):** clean uploads are deleted after completion by default; infected copies are quarantined or deleted. Failed batches roll back their files as well as their jobs. An hourly retention task removes expired orphan/completed copies after 24 hours, protects active work and offers separate opt-in job/audit history retention. Uploads use owner-only POSIX permissions; the standard scanner uses INSTREAM without needing access to upload storage. See [settings, scanner migration and verification](docs/UPLOAD_RETENTION.md).

**Long filenames and upload transactions (issue #19):** long submitted filenames remain readable job labels while bounded ASCII storage names avoid filesystem limits. Copying and SHA-256 hashing happen in one pass outside the database transaction; a separate short transaction commits the complete job batch. Quarantine uses short opaque filenames and reports failed attempts in the job page and API without losing the malware verdict. See [bounds, transaction behavior and tests](docs/UPLOAD_FILENAMES.md).

**Bounded daemon calls and scheduling (issue #20):** the scheduler defaults to four threads. TCP and Unix clients use bounded connect/read/write work and a 4 MiB response limit. Health snapshots share a five-second budget across PING, VERSION and STATS; scans have a separate configurable long timeout. DNS has bounded workers and joins the connection budget. Native upload scans use the same client. See [timeout settings, error behavior and verification](docs/CLAMD_TIMEOUTS.md).

**Token path-root privacy (issue #21):** tokens with roots can read only in-root PATH/WATCH jobs and their own uploaded jobs, within their permitted endpoints. Details, progress, lists, pagination totals and linked audit events enforce the same policy. Issuance resolves directory aliases to canonical roots, and path submissions retain the canonical authorization result. See [visibility rules, symlink behavior and upgrade notes](docs/TOKEN_PATH_SCOPES.md).

**Native ClamdTop process isolation (issue #38):** interactive `clamdtop` now runs with only `TERM`, `COLUMNS`, `LINES` and `PATH` in its child environment (no inherited JVM/LDAP/OIDC/API secrets). At most **two** native monitors per logged-in username and **eight** per Web UI instance are allowed with atomic admission. Visible tabs send heartbeats; hidden/abandoned tabs release their process after **180 seconds idle** (up to 30 seconds additional reaper delay). Configure **Native ClamdTop idle timeout (seconds)** in **Administration → Configuration Center → Monitoring → Native ClamdTop sessions** (1–86400, default 180; restart required). Alternatively, a Compose `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS: "180"` override takes precedence and locks that GUI setting. See [native monitor hardening and tests](docs/ISSUE38_NATIVE_CLAMDTOP_SECURITY.md).

**Session revocation (issue #22):** deleting, disabling, changing roles or resetting a local account ends its existing sessions. Every authenticated browser request checks current account state; new Users controls also revoke local/external sessions explicitly. Sessions have a one-hour absolute lifetime and a 30-minute idle timeout by default, and native ClamdTop connections respect revocation. LDAP/OIDC groups refresh on sign-in; remove IdP access and revoke existing sessions for prompt removal. See [administration, settings and upgrade behavior](docs/SESSION_REVOCATION.md).

**Bounded throttling (issue #23):** login failure and scan rate-limit state now expires within strict per-instance capacities. Live budgets survive random-key churn; new keys are temporarily rejected at capacity. Scan throttling runs after authentication and uses token/account identity or an anonymous IP fallback, so raw header changes cannot reset it. The existing account-wide five-failure lockout remains. See [limits, overload behavior and verification](docs/BOUNDED_LIMITERS.md).

**Scan path privacy (issue #24):** untrusted targets are checked lexically against configured/canonical roots before filesystem resolution, then checked again for symlink escapes. Missing and forbidden paths share a generic error without path/cause details. Restricted Bearer path denials use a uniform 403, closing the corresponding token-root oracle. See [response behavior, alias compatibility and tests](docs/SCAN_PATH_PRIVACY.md).

**Webhook resilience (issue #12):** delivery runs on a dedicated bounded queue
with finite connect/read/DNS/total timeouts. Private destinations are denied by
default; operators can explicitly allow internal receivers. See
[webhook policy and deployment settings](docs/WEBHOOK_RESILIENCE.md).

For the complete upgrade procedure, local/self-signed HTTPS checks and expected
test results, follow [Update and verify](docs/UPDATE_AND_VERIFY.md). See also
the [31 October update notes](docs/UPDATE_NOTES_2026_10_31.md) and
[reported field validation](docs/FIELD_VALIDATION.md).

**Self-hosted ClamAV management, scanning, monitoring and integrations.** Manage ClamD from a browser: upload and server-path scans, asynchronous jobs, quarantine, watched directories, audit history, native interactive ClamdTop, a separate REST scanner bridge, local/LDAP/OIDC sign-in, scoped API tokens, Prometheus/Grafana, CLI and MCP.

**Application version:** set only in [`pom.xml`](pom.xml); all released binaries use that value. See [v2.1.0 release notes](docs/RELEASE_NOTES_V2_1_0.md). **Registry image used in older examples:** [`kosztyk/clamav-web-client:v2`](https://hub.docker.com/r/kosztyk/clamav-web-client).

## Legacy API deprecation and request errors (issue #43)

Legacy endpoints under `/api/**` (including `/api/scan/upload`, `/api/scan/path`, `/api/jobs`,
`/api/endpoints`, `/api/health`, and `/api/clamdtop`) are **deprecated**. The planned removal
is **2027-12-31**, no earlier than a major-version release, subject to final release notes.
Use the versioned `/api/v1` endpoints for new integrations, such as
`POST /api/v1/scans/uploads`, `POST /api/v1/scans/paths`, and `GET /api/v1/jobs`.
Current legacy clients remain supported during the migration period.

Legacy scan submissions now validate the selected endpoint **before** queuing: an unknown
`endpointId` produces HTTP **404** JSON (`error: not_found`), a disabled endpoint produces
HTTP **400** JSON (`error: invalid_request`). Malformed inputs also receive generic JSON
400 errors instead of 500s or raw Java messages. The legacy success payload is unchanged;
`/api/v1` returns HTTP **202** and has stricter token scopes and pagination.
For migration examples and route mapping, see [the legacy API guide](docs/ISSUE43_LEGACY_API_PARITY.md).
Basic-auth TLS requirements, lockout and CSRF behavior remain enforced.

## Configure the app with Configuration Center

Use this method to keep application settings in the GUI. The minimum Compose starts the Web UI with persistent configuration and data; it does not start a ClamD daemon or provision an external database. Connect an existing ClamD in setup, or use the bundled-scanner example linked below. No nginx, public DNS name, certificate or build helper is required for direct GUI setup.

### Minimum docker-compose.yaml

For a **fresh installation**, copy [docker-compose.minimal.yaml](docker-compose.minimal.yaml) to `docker-compose.yaml`, or save the following file:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:local
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
docker build --pull -t clamav-web-client:local .
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

1. For direct HTTP, select **Allow sign-in and configuration over HTTP on this trusted network (saved)**. The choice persists `server.servlet.session.cookie.secure=false`, requires restart, and allows browser sessions to survive on the same HTTP origin. HTTP remains unencrypted, so use this mode only on a trusted network.
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

All 128 fields, their aliases and activation rules are documented in [the field reference](docs/CONFIGURATION_REFERENCE.md). See [the Configuration Center guide](docs/CONFIGURATION_CENTER.md) for recovery, encrypted backups, bind mounts, provider credentials and restart behavior. CLI/MCP API Bearer tokens still require HTTPS.

## Embedded H2 shutdown and production database choice (issue #36)

The included regression suite checks Hikari pool closure, persisted rows across restart, and resetting an already-open legacy `DB_CLOSE_DELAY=-1` database to `0` without an explicit SQL `SHUTDOWN`; `scripts/verify-issue36-h2-shutdown.sh` runs a disposable Docker smoke test against the **packaged** H2 default without any JDBC URL override. It also rejects SIGKILL/OOM shutdown results. See [issue #36 validation](docs/ISSUE_36_H2_SHUTDOWN.md).


The embedded H2 **file** default uses `jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0`. This explicitly resets the previous persistent `DB_CLOSE_DELAY=-1` setting, and leaves `DB_CLOSE_ON_EXIT` enabled. Configuration Center's H2 preset uses the same URL. An exact copy of the old shipped H2 URL saved in `conf/configuration-center.properties` is normalized *in memory* on startup; its config file is not silently rewritten. Explicit `SPRING_DATASOURCE_URL` overrides still take precedence: remove both unsafe flags from any custom Compose URL and use `DB_CLOSE_DELAY=0` for H2 file databases.

Run only **one** Web UI container against the same embedded H2 database. Use **PostgreSQL in production**, especially when job history is large. The default blank H2 `sa` password is kept for compatibility; for a **new** H2 database you may set a non-empty `SPRING_DATASOURCE_PASSWORD` via a private Compose environment variable. Setting it on an existing database without changing the H2 user password will prevent login to that DB. Never publish the H2 database files or database console.

Use `docker compose stop -t 60 clamav-web-client` (or configure `stop_grace_period: 60s`) to let the JVM terminate normally. `docker kill` and host power loss are not graceful shutdowns. After the Web UI stops, no `.lock.db` file should remain; the next start should open without recovery. Run `scripts/verify-issue36-h2-shutdown.sh IMAGE_TAG` for a disposable-volume smoke test instead of stopping production for verification.

## Configure the app with docker-compose.yaml

Compose/environment configuration remains fully supported. Put only the settings you want Compose to control under the service's `environment` mapping. They override saved GUI values and are shown as **Deployment configuration**, read-only in Configuration Center. Explicit `false` and empty environment values are overrides too. The rest remains editable through the GUI.

[docker-compose.environment.yaml](docker-compose.environment.yaml) is a complete example with Compose-managed ClamD and SQL connections and GUI-managed remaining settings:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:local
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
docker build --pull -t clamav-web-client:local .
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

## Additional deployment examples

The [standalone H2 and bundled ClamD example](examples/compose/standalone-h2.yaml) includes a scanner service. Its Web UI is bound to loopback by default; complete `/setup/` using the owner credential above, then sign in after selecting **Start application**. For a separate scanner and PostgreSQL, use [the remote example](examples/compose/remote-clamd-postgres.yaml). For identical shared scan paths use [the shared-paths override](examples/compose/shared-paths.override.yaml). Prepare bind-mounted directories for UID/GID 10001 as explained in [non-root deployment](docs/NON_ROOT_CONTAINER.md).

## Persistence and security essentials

- The Web UI image runs as **UID/GID `10001:10001`**. Existing bind-mounted `conf` and `data` folders must be writable by that identity before upgrading. See [non-root deployment and permission migration](docs/NON_ROOT_CONTAINER.md), including Docker Desktop, named volumes, Unix sockets and Kubernetes examples.
- Preserve `./conf` (settings and encrypted identity key), `./data` (uploads, quarantine and default H2 database), and your external SQL backups together. Do **not** run `docker compose down -v` as an upgrade procedure.
- H2 is for **one** instance. PostgreSQL/MySQL/MariaDB are supported as database backends, **not** as multi-replica HA for this app. Flyway runs versioned migrations and Hibernate validates; **never enable auto-baselining for an unreviewed existing DB**.
- ClamD TCP 3310 is normally unauthenticated; never publish it to the internet. Browser authentication and API tokens do **not** secure ClamD's raw TCP port.
- The native ClamdTop page needs WebSocket proxy upgrades on `/ws/clamdtop`. External API Scan/Reports requires a separately deployed compatible REST scanner and `CLAMAV_REST_URL`; the built-in `/api/v1` is **not** that scanner.
- **Issue #40 security change:** an external REST scanner using `http://` and a nonempty `CLAMAV_REST_API_KEY` fails application startup unless `CLAMAV_REST_ALLOW_INSECURE=true` is explicitly configured (also available in Configuration Center → Connections → External REST scanner → Advanced). Use HTTPS instead. Both REST clients now enforce a 10 MiB response cap and only return generic upstream errors. See [REST transport security and migration](docs/ISSUE40_REST_TRANSPORT_SECURITY.md).
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
mvn -B -ntp verify                       # Java 21 and Maven, when installed
docker build --pull -t clamav-web-client:local .
# To use the local image, replace image: in your deployment Compose with
# clamav-web-client:local and set pull_policy: never.
```

The Dockerfile builds with Maven/Java 21 and includes the native `clamdtop` binary. For development, the repository root `docker-compose.yml` is a **local-build** Compose, whereas the examples above use the **published image**. See the [Docker build guide](docs/DOCKER_BUILD.md) and [security revision details](docs/SECURITY_DEPENDENCY_UPDATE.md). The complete source includes `cli/`; generated platform binaries are separate build/release artifacts. Build or obtain the client executable for your platform as described in [the CLI guide](docs/CLI.md).

## Push this complete source to GitHub

The archive includes [publish-clamav-webui.sh](publish-clamav-webui.sh) and a SHA-256 manifest. The script verifies every source file, prepares an isolated clone, commits the complete source snapshot and pushes normally. It retains Git history and never force-pushes. If the default branch requires review, it publishes an update branch and opens a pull request. Git's pager is disabled, so file summaries do not pause at `:`.

```bash
bash publish-clamav-webui.sh /path/to/clamav-webui-v2.1.0-issues-13-57-full-source.zip OWNER/REPOSITORY
```

Replace `OWNER/REPOSITORY` with the repository you intend to update. On macOS and Linux, the script uses an installed GitHub CLI or downloads a pinned official binary and verifies its SHA-256 digest. It prompts for browser authentication only if needed. `--check` validates the archive offline; `--dry-run` prepares the update without pushing. Read [the publishing guide](docs/PUBLISHING.md) for exact commands, required workflow permissions, protected branches and what the full-source update replaces. This publishes source only; Docker images and GitHub releases are separate operations.

## Source and support

The project is published at [github.com/Kosztyk/clamv-webui](https://github.com/Kosztyk/clamv-webui). For problems, attach sanitized container logs, the app version/image digest, database engine, ClamD connection mode and the steps to reproduce. **Redact** credentials, authorization headers, LDAP bind details, webhook URLs, tokens and file contents. Historical technical documents remain under `docs/`; the single [Complete User Guide](docs/COMPLETE_USER_GUIDE.md) is the current user entry point.

### Session and locale cookie hardening (issue #27)

By default, both the authenticated session and language-preference cookie are `Secure; HttpOnly; SameSite=Lax`, and session idle timeout is 30 minutes. For an intentionally direct-HTTP deployment on a trusted network, **Setup & recovery → Direct HTTP configuration** can persist `server.servlet.session.cookie.secure=false`; restart is required. The equivalent environment override remains `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`. HTTP provides no transport encryption. See [issue #27 deployment notes](docs/ISSUE_27_COOKIE_SECURITY.md).

### Multipart spool location (Configuration Center)

**Administration → Configuration Center → Scanning → Advanced → Multipart temporary directory** manages `spring.servlet.multipart.location` (restart required). The Docker image defaults to `/app/data/tmp/multipart` via a non-locking fallback. To manage it in the GUI, omit `SPRING_SERVLET_MULTIPART_LOCATION` from Docker Compose; an explicit Compose environment value takes precedence and locks the UI field. A custom directory must already exist inside the Web UI container, be private (`0700`) and writable, and must not overlap uploads or quarantine. See [configuration details](docs/ISSUE_30_MULTIPART_CONFIGURATION_CENTER.md).

**Issue #35 (beta2 local source):** Prometheus detection signature labels are capped (default 100 exact names plus `other`/`unknown`, configurable in Configuration Center), the duplicate `clamav_scan_completed_total` is retired in favor of `clamav_scans_total`, and scan duration histograms cover 24 hours. See [docs/ISSUE_35_METRIC_CARDINALITY.md](docs/ISSUE_35_METRIC_CARDINALITY.md).


### Cumulative beta2 audit compatibility patch

The current beta2 source preserves API v1 history diagnostics (signatures, quarantine state and stable error codes) without restoring full-entity history queries; fixes the Configuration page's successful-observation timestamp while keeping active scan policy stable; and adds low-frequency cross-instance default-endpoint drift detection without per-poll write transactions. See [compatibility fixes](docs/CUMULATIVE_AUDIT_COMPATIBILITY_FIXES.md). These changes require no additional database migration or Compose settings.

### Issue #37 — lighter image, liveness, Docker HEALTHCHECK (v2.1.0beta2)

The Dockerfile now extracts just `clamdtop` from the distribution package, leaving clamd/freshclam out of the **web** image; the separate scanner is unchanged. Java builder/runtime digests are pinned and Java 21 is required, while unused H2 console and mail starters are removed. Database support for H2, PostgreSQL, MySQL and MariaDB is preserved. A Docker `HEALTHCHECK` probes anonymous **GET `/actuator/health/liveness`** on loopback (status only); other health/actuator routes still require authentication, and Prometheus still requires its scrape token. Docker startup sets `JAVA_MAX_RAM_PERCENTAGE=70.0` by default, with `JAVA_TOOL_OPTIONS` supported. See [issue #37 details, smoke tests and real image-size measurement](docs/ISSUE37_CONTAINER_MAINTENANCE.md). Run `./scripts/issue37-smoke.sh clamav-web-client:issue37` after building the image. A running container's `healthy` status and actual image-size difference require verification on your Docker host; they are not asserted by the offline ZIP build.


### Issue #42 — runtime configuration is not source code

`conf/clamav-web-client.properties` and `conf/integrations.properties` are generated/runtime files and are ignored by Git. The repository includes only `conf/clamav-web-client.properties.example`. Existing webhook URLs are migrated on startup from the plaintext legacy file to encrypted Configuration Center storage, then removed from plaintext after successful migration. Back up your entire mounted `conf` directory including `configuration-center.key`. See [Issue #42 configuration migration](docs/ISSUE42_RUNTIME_CONFIGURATION_SECURITY.md).


### Supply-chain and CI security (issue #45)

GitHub Actions use reviewed full commit SHAs; weekly Dependabot checks Maven, Docker and GitHub Actions. The repository's issue #45 CI workflow builds the digest-pinned web image, fails on Trivy CRITICAL OS/library vulnerabilities, and runs Java CodeQL SAST. The locally served full jQuery 3.7.1 file is checksum-verified; Bootstrap and legacy xterm have an asset inventory and manual-update procedure in `src/main/resources/static/js/VENDORED.md`. Trivy/CodeQL are release gates; consult CI results before describing a build as vulnerability-free.

### Issue #46 — scan-service transaction parity and cleanup (v2.1.0)

Browser and API submissions now use the same explicit transport-aware service methods. File copies stay outside database transactions, while all upload-job metadata for a batch is committed together with rollback and after-commit dispatch. Browser path scans now use the proxied transactional method. Obsolete scan-state code has been removed and quarantine/notification diagnostics improved. See [`docs/maintenance/ISSUE46_SERVICE_LAYER_CLEANUP.md`](docs/maintenance/ISSUE46_SERVICE_LAYER_CLEANUP.md). No new Configuration Center field or environment variable is required.


## Required CI gate (issue #48)

The repository uses **one always-reporting required check**, `gate`, from
`.github/workflows/ci.yml`. The workflow runs on every pull request and every
main-branch push, without event-level path filters. Change detection skips
expensive database, Docker, browser, Go, and CodeQL jobs only when the diff
does not affect them; an unexpectedly skipped, failed, or cancelled selected job
fails `gate`. Documentation-only PRs run the lightweight policy check and gate.
Flyway migration changes run the PostgreSQL/MySQL/MariaDB Testcontainers matrix.

Repository maintainers should configure **Settings → Branches / Rulesets →
Require status checks → `gate`** for `main` and remove old issue-specific
required status checks to prevent indefinitely pending PRs. See
[CI required gate and artifact guide](docs/ISSUE48_CI_REQUIRED_GATE.md).
The tag-triggered verified release workflow remains separate and requires the
full Maven, security, image-signature and artifact-verification checks.


### Issue #49 — verified GitHub release workflow

The new `.github/workflows/release.yml` creates a **draft** only for a version
tag on a `main` commit with a successful `CI` **gate**, after the complete
Maven, Docker, three-database, Trivy, browser and cross-platform Go checks
pass. Release assets include the JAR, six `clamctl` binaries, complete source
ZIP and `SHA256SUMS`. `workflow_dispatch` is dry-run only. Public publication
is **disabled by default**; configure the protected `release` environment
with required reviewers before enabling the
`RELEASE_PUBLICATION_ENABLED=true` repository Actions variable. See
[issue #49 release instructions](docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md).

Release tags must be `v` plus the version read from `pom.xml`. For this release the tag is **`v2.1.0`**. The release gate rejects nonmatching tags; future releases bump only the POM version, merge to `main`, wait for a green `gate`, then create the matching tag.

### Issue #51 — signed, scanned GitHub Container Registry images

Versioned releases now publish an **immutable multi-arch** Web UI image to
`ghcr.io/kosztyk/clamav-web-client` after Maven, DB/Go, pushed-image Trivy
HIGH/CRITICAL scans, Sigstore keyless signing and **published-digest**
non-root/EICAR smoke tests succeed. Stable releases carry `X.Y.Z`, `X.Y`,
and `latest`; prereleases carry **only** their exact version. All signatures,
CycloneDX SBOMs, BuildKit provenance, the digest-scoped OpenVEX assessment for
Alpine's `CVE-2026-107891` backport, and release artifact checksums are checked
before a GitHub release can be drafted. No production upgrade is performed
automatically.

See [the complete signed-image guide](docs/ISSUE51_SIGNED_GHCR_RELEASE.md),
[the CVE-2026-107891 VEX guide](docs/VEX_CVE_2026_107891.md), and
[the GHCR Compose example](docker-compose.ghcr.yml) for verification, package
visibility, rollout and rollback instructions. The existing local Compose
development build remains supported. Docker Hub remains a separate publication
path; use `scripts/attach-release-vex.sh` after pushing the corresponding Docker
Hub image if that registry is used. Docker Scout trusts only Docker-authored VEX
by default, so verification of this publisher-authored statement must explicitly
accept `https://github.com/Kosztyk/clamv-webui`; see the VEX guide.

### Issue #52 — CI security scanning (Java/JS/Go, dependencies, image, secrets, lint)

The single required GitHub Actions **`gate`** now incorporates Gitleaks source
and commit-history secret scanning, OSV-Scanner dependency vulnerability checks,
Trivy filesystem and Docker image scanning, actionlint/hadolint, and CodeQL for
Java, JavaScript/TypeScript and Go. The existing release pipeline repeats
security scanning before publishing the signed, attested GHCR image. Weekly
Dependabot updates cover Maven, Docker, GitHub Actions and Go (`cli/`).
Docs-only PRs skip the expensive scans but keep the same required gate.
See [security scanning gates, exception policy and acceptance tests](docs/ISSUE52_SECURITY_SCANNING_GATES.md).

## Releases, changelog and private security reporting

For **every release**, follow [RELEASE.md](RELEASE.md) (full CI gate, protected
tag, signed GHCR image, verified assets, approval, rollback). See
[CHANGELOG.md](CHANGELOG.md) for user-visible changes, the GitHub PR
template for review requirements, and [SECURITY.md](SECURITY.md) for
**private** vulnerability disclosure. **Do not post credentials or LAN
addresses in public issues.** Source archives are audited for embedded
private IPv4 addresses before publishing; intentional RFC1918 test fixtures
are explicitly allowlisted. These documents cannot enable GitHub repository
security settings; a maintainer must turn on private vulnerability reporting.

### Upgrade-path release protection (issue #57)

The unified CI and tag-triggered release workflow now test upgrades of the **latest published ancestor release** (originally `v2.1beta1`) to the candidate against persisted H2 and PostgreSQL state, including retained local users/roles, API tokens, endpoints, scan jobs, LDAP/OIDC ciphertext and `conf/` encryption keys. The release will not publish its GHCR image tags or draft GitHub artifacts when either upgrade fails. A separate frozen V4 H2 fixture exercises the Flyway V5–V8 path during fast Java tests. See [the documented migration gate](docs/ISSUE57_UPGRADE_PATH_GATE.md) and [release runbook](RELEASE.md). No private network addresses or credentials are embedded in these fixtures.


### Full-source review (November 2026)

The cross-issue audit and its severity-ranked findings, confirmed fixes and
remaining release risks are documented in
[`docs/COMPREHENSIVE_SOURCE_REVIEW_2026-11-14.md`](docs/COMPREHENSIVE_SOURCE_REVIEW_2026-11-14.md).
The audit regression checks run in the required CI gate and the release gate.

### Post-audit reliability safeguards

Scan dispatch now uses a bounded queue (default `APP_SCAN_QUEUE_CAPACITY=256`); overflow leaves a committed scan `QUEUED` for scheduled DB retry and increments `clamav.scan.dispatch.deferred`. Java CI rejects drops in measured JaCoCo line coverage relative to the Git baseline. Upgrade gates resolve the latest published ancestor release instead of manually pinning a predecessor. See [post-audit hardening](docs/QUEUE_COVERAGE_UPGRADE_HARDENING.md). Historical Java formatting cleanup and a measured increase of the provisional 5% JaCoCo floor remain explicit follow-up work.

**Scan shutdown/restart:** On SIGTERM, the scan executor stops accepting new dispatches, leaves unstarted tasks `QUEUED`, and allows active scans 20 seconds to finish. It then interrupts active workers and waits up to 5 more seconds, without writing an `ERROR` verdict for shutdown cancellation. Persisted `RUNNING` jobs are requeued on the next single-instance startup; this retries an interrupted scan from the beginning. Configure `APP_SCAN_SHUTDOWN_GRACE_SECONDS` (0–300) and `APP_SCAN_SHUTDOWN_FORCE_SECONDS` (0–60), keeping their combined value below Spring's shutdown-phase timeout and the container's stop grace. An uninterruptible ClamAV socket can survive both waits: daemon worker threads prevent indefinite JVM shutdown, and restart recovery handles the outstanding job. See [queue hardening](docs/QUEUE_COVERAGE_UPGRADE_HARDENING.md).