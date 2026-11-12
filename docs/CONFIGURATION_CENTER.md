# Configuration Center

This source edition implements the proposed Configuration Center inside ClamAV Web UI 2.1.0. It is part of the ordinary JAR and Docker build. No helper flag or image patch is required.

The administration page is **Administration → Configuration center**, `/admin/configuration-center`. The independent first-run/recovery page is `/setup/`. All seven categories use the same validated catalogue and persistence layer.

## Minimum Compose and first-run setup

The complete, current steps are also in [README: Configuration Center](../README.md#configure-the-app-with-configuration-center). Copy [docker-compose.minimal.yaml](../docker-compose.minimal.yaml) to `docker-compose.yaml` for a fresh installation:

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

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
docker compose -f docker-compose.yaml up -d
docker exec clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

This example uses named volumes and requires no application environment variables. Docker initializes their permissions from the non-root image. The Web UI starts before any database connection is opened; the scanner itself is a separate service. Keep existing deployments' original conf/data mounts and SQL connection. The older [bind-mount example](../docker-compose.configuration-center.yaml) is also supported; prepare its host directories for UID/GID 10001 according to [non-root deployment](NON_ROOT_CONTAINER.md). The optional `deploy-configuration-center.sh` uses that bind-mount example; neither it nor `build-and-start.sh` is required for these application features.

Open `http://YOUR_DOCKER_HOST:8080/setup/` (or `http://localhost:8080/setup/` on that host). The container name in the token command must identify this same instance. To address the service regardless of its container name, use:

```bash
docker compose -f docker-compose.yaml exec -T clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
```

1. Paste the separate owner credential into **Recovery credential** and unlock configuration. Keep it private. If the file does not exist yet, wait for startup and inspect logs/volume permissions. Do not create a token manually.
2. On direct HTTP, confirm **Allow configuration over HTTP on this trusted network** before entering passwords or other credentials. This does not encrypt HTTP. A reverse proxy, DNS name and certificate are optional for this GUI workflow.
3. Enter the ClamD connection and select built-in H2 or an existing external database. Enable optional services and complete their fields. Use **Test connection** where available; testing does not save the draft. A test does not create an empty H2 file: a fresh database shows an amber “not initialized yet” explanation. Review/apply/start initializes it, and testing afterward verifies the saved credentials. See [database test behavior](DATABASE_CONNECTION_TESTS.md).
4. Select **Review changes** or **Review initial configuration**, acknowledge any database selection, apply, then select **Start application**. Wait for the main application to initialize.
5. For a newly created database with no supplied bootstrap password, obtain the generated administrator password:

   ```bash
   docker exec clamav-web-client sh -c 'cat /app/data/bootstrap-admin-password; echo'
   ```

   Sign in as `admin`, set a new password under Users, and remove that one-time bootstrap file after verifying access. Existing accounts are retained. The setup/recovery credential is not an administrator login password or an API token.
6. After login, manage all global settings under **Administration → Configuration center**. Restart saved startup-bound fields using the same Compose file. Normal sensitive edits require an enabled local administrator's password as well as the authenticated admin session.

## Configure through Compose, the GUI, or both

[README: docker-compose.yaml](../README.md#configure-the-app-with-docker-composeyaml) and [docker-compose.environment.yaml](../docker-compose.environment.yaml) explain the environment-managed alternative. Supply your own connection values via the private `.env`; no real deployment is preconfigured. Only declared `environment`/`env_file` entries become container settings.

Deployment overrides win over GUI values and appear read-only, including explicit `false` and empty strings. Saved GUI settings override built-in defaults. Unchanged legacy properties, identity settings and scrape-token hashes remain compatible. To move a field to GUI management, remove its deployment entry, recreate the container and inspect the selected value; a prior saved GUI value may become effective. To update Compose environment, recreate instead of merely restarting:

```bash
docker compose -f docker-compose.yaml up -d --force-recreate clamav-web-client
```

Keep the same persistent volumes/database during an upgrade. Selecting another JDBC URL changes the connection, not the stored users/jobs/history; it does not migrate them. An unavailable external database does not silently fall back to H2. Recovery itself is independent of the database and does not bypass deployment locks.

## One global configuration editor

Configuration Center is the **only editor for global application settings**. The duplicate Settings and Authentication menu entries are removed. Old GET bookmarks redirect to the relevant central category. Old settings, provider Test/Save, Prometheus and observer POST routes return **409** without changing configuration; they cannot bypass review or administrator verification.

Daemon status & files and Monitoring & clients remain useful read-only views with diagnostics, downloads and setup instructions. The default ClamD engine is edited only in Configuration Center. Additional named endpoints, users, API tokens and individual watched-directory records remain separate resources on their management pages; their operational fields are not duplicate global settings. Existing Compose values, legacy properties, encrypted identity rows and hashed scrape tokens are still read for compatibility. They are not reset on upgrade.

LDAP contains every runtime option formerly shown on Authentication: both search filters, member/name attributes, StartTLS, pooling, nested groups/depth, referrals, both timeouts, role maps and the retained bind credential. These options are visible together when LDAP is enabled; depth appears when nesting is enabled. Mounted secret files remain under Advanced. OIDC also exposes scopes, group claim, role maps, button label, logout mode and context-aware callback guidance. All identity changes use the same validated runtime option mapping and require restart. LDAP depth is 1–8 and timeouts are 1000–30000 ms. OIDC scopes are canonicalized so commas versus spaces do not create a false pending restart.

Prometheus token generation now stages a random token locally in Configuration Center. Copy it before leaving; it becomes active only after review and apply. **Stage application defaults** in Monitoring stages built-in values and explicit credential clearing for review; it keeps deployment locks and does not silently restore an older legacy token. Observer mode/polling changes refresh the active snapshot immediately. See [the consolidation audit](CONFIGURATION_CONSOLIDATION.md).

## Categories and activation

| Category | Settings and related management | Activation |
|---|---|---|
| Connections | Default ClamD TCP/socket/name/platform/enabled; REST bridge and paths; JDBC, driver, LOB setting and reviewed Flyway baseline | ClamD live; REST/database restart |
| Scanning | Allowed roots, upload limits/path scan/fallback, workers/storage, quarantine, watched-folder policy | Policies live; worker capacity and storage paths restart |
| Identity & API | Local recovery links, LDAP/AD, OIDC, roles, scopes, secret files, API limits and daemon database freshness | Central identity/API settings restart |
| Notifications | Webhook enable/URL; connect/read/DNS/total timeout; bounded workers/queue; internal-address policy | URL/enable live; transport/address policy restart |
| Network | Framework/native/none forwarded handling, canonical HTTPS base URL, context path, secure/SameSite cookies, Java truststore | Restart |
| Monitoring | Prometheus gate/token/polling/management port; daemon config mount paths, inspection/inheritance/polling | Gate/token and observer controls live; clients/paths/management listener restart |
| System | Theme/language defaults, daemon timestamp timezone, protected recovery link | Restart |

Disabled optional features stay collapsed. Enable one to see its inputs. Opening one section collapses the previous section in that category. Advanced options start collapsed. Drafts survive switching tabs and sections. Only explicitly edited keys are submitted; a connection test tests its section's draft without saving it.

The canonical HTTPS application URL, when set, determines OIDC callback base URLs and appears in client instructions. Include a configured context path in it. Leaving it blank uses the address of the current request, including trusted forwarded headers when enabled.

**Apply live** means the running service supports re-reading those settings. **Restart required** means the saved value is staged, while the current running value remains active. Restart with the same Compose file you normally use, for example:

```bash
docker compose -f docker-compose.yaml restart clamav-web-client
```

Changing Compose environment requires **recreation**, not just restart. Changing image code requires rebuilding/recreating; changing GUI configuration requires neither rebuilding nor a helper flag. The page never terminates a healthy application itself.

Webhook private-address policy is privileged, default-deny and startup-bound. When it changes, a newly submitted webhook destination/enable is held until that restart as well. Existing bounded delivery, DNS pinning, redirect restrictions and metadata safeguards remain in force. Enable internal webhooks only for intentional receivers reachable from this container.

## Optional reverse proxy and API transport

A reverse proxy is optional. Direct HTTP GUI setup and administration work using the published application port. If an operator chooses HTTPS termination at a proxy, that proxy must replace forwarded headers and route to the operator's own application instance; the application never knows or assumes a particular proxy, server address or domain.

The GUI's HTTP confirmation is scoped to authenticated configuration operations. It does not alter `request.isSecure()`, API token checks, scopes or revocation. API Bearer tokens used by CLI/MCP still require HTTPS. For a direct-access deployment that ignores forwarded headers, select `none` under Network; configure TLS separately when using those machine clients. Outbound LDAP/OIDC/Discord transport requirements and webhook SSRF filtering remain unchanged.

## Upload cleanup and history

**Scanning → Upload cleanup and history** controls immediate clean deletion, the 24-hour upload retention and optional job/audit history durations. Upload retention protects active work; history pruning is disabled by default and requires an explicit positive duration. The upload-cleanup edition added four fields; the current catalogue has 117 fields, including the session durations below. See [behavior, bounds and native scanner migration](UPLOAD_RETENTION.md).

## Persistence, encryption and audit

| Path under `/app/conf` | Purpose |
|---|---|
| `configuration-center.properties` | Versioned GUI values; credentials stored as AES-256-GCM ciphertext |
| `configuration-center.key` | Independent 256-bit encryption key; must survive container recreation |
| `configuration-recovery-token` | Separate random owner credential for setup/recovery |
| `configuration-audit.jsonl` | Independent audit of configuration save requests/completion and start requests; records key names, never values |
| `configuration-database-state.properties` | Previous selected database kind/fingerprint; prevents accidental silent fallback |
| `configuration-database-choice.properties` | Explicitly reviewed database selection fingerprint |
| `auth-settings.key` | Existing encryption key for legacy database-managed identity credentials; retain it too |

Back up the **whole conf directory together**, plus the database and data volume. Keep backups encrypted and access restricted. Restoring the properties file without its matching key cannot decrypt credentials; the application fails closed and never generates a substitute key for existing ciphertext. It cannot safely repair a lost key from the GUI. Restore the matched backup or deliberately rebuild configuration from known credentials. If credentials are solely in deployment secret files, preserve those files independently as well.

Blank secret inputs retain the current value. For LDAP/OIDC, this requires an unchanged destination and bind/client identity: changing the LDAP URL, StartTLS, base DN, manager DN or referral policy, or the OIDC issuer or client ID, requires re-entering the credential before Test or Save. The same rule applies in setup/recovery and while a provider is disabled. See [provider secret protection](PROVIDER_SECRET_BINDING.md). Use the explicit **Clear** control to remove a credential; an enabled provider still requires valid credentials. Secret-bearing webhook URLs are also private/write-only. Legacy plaintext webhook destinations are migrated into encrypted central storage on normal startup; deployment values are never copied into the GUI file. Scrape tokens saved by the older integration page remain hashed; newly central-managed tokens are encrypted so both setup and the live gate can use the shared source.

LDAP/OIDC secret files remain supported. A deployment secret-file source locks the matching inline credential too, preventing GUI bypass. Selecting a GUI secret file clears its saved inline credential; entering a new inline credential clears its GUI file path. Re-submitting a file path does not authorize sending the existing credential to a changed provider destination. If a deployment-controlled credential prevents GUI re-entry, update the provider destination and credential together through deployment configuration and recreate the service. Never configure conflicting deployment credential sources.

Optional deployment-only controls:

| Variable / property | Purpose |
|---|---|
| `CLAMAV_CONFIGURATION_FILE` / `clamav.configuration.file` | Alternate configuration file path |
| `CLAMAV_CONFIGURATION_KEY_FILE` / `clamav.configuration.key-file` | Alternate matching encryption key path |
| `CLAMAV_CONFIGURATION_RECOVERY=true` / `clamav.configuration.recovery=true` | Enter protected recovery before attempting the main application |

These bootstrap paths are intentionally not editable inside their own GUI.

## Database-independent recovery

If DataSource, Flyway or another startup dependency fails, the failed Spring context is closed and the protected listener opens `/setup/` on the application port. It retains the selected database, does not migrate data to H2 and reports application health as DOWN. Correct unlocked configuration, review/apply and choose Start. A database locked by deployment configuration must be corrected in that deployment, followed by recreation.

`/setup/` also remains available alongside a running application, independently of SQL and external identity. Saves from that route use the separate owner credential and require a manual container restart for startup settings. It does not impersonate an application user or bypass the local-admin retention rules. When the database is unavailable, legacy settings stored only in it cannot be displayed by recovery; only explicit edits are saved, leaving those stored rows intact for the next successful startup.

To force recovery after stopping normal startup, add `CLAMAV_CONFIGURATION_RECOVERY: "true"` to the existing service and recreate it. Remove the flag when finished so subsequent restarts follow the normal path. The recovery token has bounded authentication attempts, same-origin checks and no cookie-based authentication. A mismatched token returns **401** with an instance-specific explanation. Eight failed attempts trigger **429 Too Many Requests** with `Retry-After: 60`; the limit is temporary and the credential is retained across restarts. Mount/key permission failures still require host-volume repair; the GUI cannot grant itself filesystem access.

## Deployment responsibilities and limits

Docker image tags, host listeners, volumes, socket mounts, secret mounts, ownership, networking, ingress certificates and database provisioning stay in Compose/Kubernetes/host configuration. The GUI cannot create those resources. Java truststore settings select an already mounted readable file and keep certificate/hostname validation enabled. JVM `JAVA_TOOL_OPTIONS` truststore overrides remain authoritative.

A different JDBC URL does **not** copy users, jobs, tokens, audit records or schema history. Use a planned database migration and backups. Legacy Flyway baselining remains deliberate and version 1; validation and Hibernate schema checking are not exposed as unsafe GUI toggles. Existing insecure `admin/admin` databases still require controlled account recovery before startup.

Configuration is per instance. This is not a distributed configuration bus. Coordinate file/key backups and restarts explicitly; do not assume changes fan out across replicas. H2 is for single-instance use.

See [every supported field and environment alias](CONFIGURATION_REFERENCE.md), [validation results](CONFIGURATION_CENTER_VALIDATION.md), [non-root volumes](NON_ROOT_CONTAINER.md), [proxy TLS](REVERSE_PROXY_TLS.md) and [webhook policy](WEBHOOK_RESILIENCE.md).

## Session limits and revocation (issue #22)

**Network → Reverse proxy & session security → Advanced** includes **Session
idle timeout** (`30m`) and **Absolute session lifetime** (`1h`). The idle timer
slides; the absolute timer always starts at successful login. These work for
local, LDAP and OIDC browser authentication and require a restart after saving.
The GUI accepts 1m–24h idle and 1s–24h absolute age; the application also validates
absolute age at startup. Both settings remain available for direct deployment
with forwarded-header trust disabled.

Local account controls and explicit local/external session revocation are on
**Admin → Users**. See [account changes, IdP limitations and upgrade details](SESSION_REVOCATION.md).

## Limiter capacities (issue #23)

**Identity & API → Local recovery administrator → Advanced** exposes **Maximum tracked login
accounts**. The API section exposes **Maximum tracked scan subjects**. Both
default to 10,000, accept 1–100000 and require restart. At capacity, new keys are
throttled; live budgets remain intact. Scheduled indexed expiry frees unused
state. The existing API requests-per-minute control applies per authenticated
subject, with an anonymous IP fallback. See [bounded limiter behavior](BOUNDED_LIMITERS.md).
