# ClamAV Web UI: home-lab setup and acceptance tests

These tests use your existing LDAP login and PostgreSQL deployment. They do not
replace the database, change Flyway history, delete volumes or require real
malware. Keep your working Compose file and the existing `conf` and `data`
mounts. Perform configuration-policy experiments on a lab endpoint or during a
maintenance window, because inheritance can change the default endpoint's limits.

## 1. What is configured where?

| Feature | Where to configure it | When it applies |
|---|---|---|
| LDAP / OIDC | Administration → Authentication | New sign-ins after Save; test before enabling |
| PostgreSQL | Your deployment's `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | Recreate the Web UI container; keep the same DB and volumes |
| Scan roots, upload limits, quarantine, webhooks | Administration → Settings | Scan policy is read for new work; storage/concurrency changes can require restart |
| ClamD addresses and enabled state | Administration → Endpoints | New requests/jobs use the selected endpoint |
| Prometheus export and scrape token | Administration → Monitoring & clients | Immediately after Save; no restart |
| Grafana dashboard / Prometheus server | Separate Grafana and Prometheus services | Import the provided JSON; configure a scrape job |
| File observation, inspection/inheritance mode, poll interval | Administration → Configuration | After Save; no daemon restart is performed |
| Paths to clamd.conf / freshclam.conf | Read-only container mounts plus `CLAMAV_CONFIG_PATH` / `FRESHCLAM_CONFIG_PATH` | Recreate the Web UI container after changing mounts/environment |
| CLI | Install `clamctl` on your Mac/Linux/Windows machine; configure its server and API token | Each command |
| MCP | Start `clamctl mcp serve` on the host with the artifacts | While that separate process runs; revoke its API token to block API access |
| API token scopes / expiry / revocation | Administration → API Tokens | Checked on API requests |
| Watch directories | Administration → Watch Directories plus the global switch in Settings | Next watcher cycle |

A browser cannot create a Docker mount, restart a process on your laptop or
replace a running datasource pool. Those settings are identified as deployment
or client setup, rather than controls that pretend to apply immediately.
UI-managed monitoring and observation settings are per Web UI instance, saved
in `conf/integrations.properties`. They survive container recreation when the
conf volume is retained. Use the page's **Use container defaults** button to
remove that feature's UI override. Prometheus stores only a hash of a token
saved through this UI. Keep the conf volume private and backed up.

## 2. Establish a baseline before changing anything

1. Sign in with your existing LDAP administrator. Open Dashboard and Endpoint
   Health. Record the endpoint ID/name, ClamD version, definition time and
   connectivity. The web application and database should be UP, and ClamD should
   be UP with a plausible definition timestamp.
2. Verify that a known local recovery administrator still signs in from a
   separate private browser window. LDAP need not be disabled to test this.
3. Check PostgreSQL persistence: submit one harmless clean scan and note its job
   ID. Recreate **only the Web UI service**, using your current Compose:

   ```bash
   docker compose up -d --no-deps --force-recreate clamav-web-client
   ```

   Sign in again and find that same job ID. Its continued presence verifies
   persistence from the application's point of view. For a direct database
   check, connect to your existing `clamv` database with your usual PostgreSQL
   client and run only these read-only queries:

   ```sql
   SELECT current_database(), current_user;
   SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
   SELECT id, status, verdict FROM scan_jobs ORDER BY submitted_at DESC LIMIT 5;
   ```

   Do not run `clean`, `DROP`, or turn on `baseline-on-migrate` as a test.
4. Visit every administration link in light and dark mode. Each should have the
   same icon space, title treatment and selected-state indicator. Open
   Configuration, type a different polling interval, then wait 35 seconds: the
   unsaved form value should remain. Read-only telemetry can refresh independently.

## 3. Prepare a scoped API token and trusted URL

After signing in with LDAP/OIDC, use Administration → API Tokens. Create a
temporary token labelled `home-lab-test`, expiry one or two hours, with:

```text
health:read  endpoints:read  jobs:read  scan:upload  scan:path  audit:read
```

Restrict endpoint IDs to the actual lab endpoint. For `scan:path`, set an
explicit allowed root, for example `/scandir/clamav-lab`; it must also be allowed
in the application settings and be readable by the daemon. Do not grant a broad
root such as `/`. Copy the token to a private file on your test machine:

```bash
umask 077
mkdir -p "$HOME/clamav-lab"
cd "$HOME/clamav-lab"
read -r -s -p 'Paste the temporary API token: ' CLAMAV_LAB_TOKEN
printf '\n'
printf '%s' "$CLAMAV_LAB_TOKEN" > api.token
unset CLAMAV_LAB_TOKEN
```

These examples use Bash. Choose your actual trusted HTTPS UI URL:

```bash
export CLAMAV_SERVER='https://YOUR_WEB_UI_HOST'
```

For a private CA, copy its **public CA certificate**, not its private key, and
set `CLAMAV_CA_FILE=/absolute/path/lab-ca.crt`. The hostname must match the server
certificate. If the OS already trusts the issuing CA, omit that variable.
Do not bypass certificate checks. A 401 from Nginx can mean the proxy is
requiring another login before the request reaches the application; check its
route configuration and preserve the Bearer Authorization header.

The API token, Prometheus scrape token and optional MCP HTTP token are three
different credentials. LDAP/OIDC passwords are not needed for these tests.

## 4. Install and test the CLI

Extract the full source package and copy the appropriate executable from
`cli/dist`: Linux `linux_amd64` or `linux_arm64`, Mac `darwin_arm64` for Apple
Silicon or `darwin_amd64` for Intel, Windows `windows_amd64` or `windows_arm64`.
Verify it against `cli/dist/SHA256SUMS`. Linux/Mac example:

```bash
chmod +x /PATH/TO/clamctl
export PATH='/PATH/TO/EXECUTABLE_DIRECTORY:'"$PATH"
clamctl --version
clamctl config set-server "$CLAMAV_SERVER"
clamctl login --token-stdin < "$HOME/clamav-lab/api.token"
clamctl status --json
clamctl endpoints list
clamctl jobs list --limit 5 --json
clamctl audit list --limit 5
```

Expected: version 2.0.0, the authorized endpoint list, connectivity UP, definition
timestamp/version, thread counts, queue and pool memory when supported by your
daemon. Unknown values are not evidence of zero use. CLI Basic authentication is
local account authentication; LDAP/OIDC browser users should use an API token.

Create and scan a harmless text file:

```bash
printf 'ClamAV clean home-lab check\n' > "$HOME/clamav-lab/clean.txt"
clamctl scan upload "$HOME/clamav-lab/clean.txt" --wait --json
```

Expected: `status: FINISHED`, `verdict: OK`, a job ID and execution duration.
Then use that actual ID:

```bash
clamctl jobs get JOB_ID --json
clamctl jobs watch JOB_ID --json
```

The watcher reports job state, not raw ClamD logs or a byte-level progress
percentage. `scan upload --wait` returns exit 0 for clean, 3 for malware found,
1 for a scan/API error, 4 for timeout. An accepted scan continues after the CLI
or MCP wait is cancelled.

If an upload finishes with ERROR and the administrator's server log says that a
path-based upload scan failed, check whether ClamD can read the Web UI's upload
directory at the same absolute path. For a remote ClamD without shared upload
storage, set `CLAMAV_UPLOAD_PATH_SCAN_ENABLED=false` in your deployment and
recreate the Web UI to use INSTREAM instead. Keep path scanning only where the
daemon actually sees the selected server path.

## 5. Harmless positive detection with EICAR

Use the official anti-malware test file, never execute it:

```bash
cd "$HOME/clamav-lab"
curl --fail --location --output eicar.txt https://secure.eicar.org/eicar.com.txt
CLAMAV_LAB_RESULT=0
clamctl scan upload ./eicar.txt --wait --json || CLAMAV_LAB_RESULT=$?
printf 'CLI exit code: %s\n' "$CLAMAV_LAB_RESULT"
```

Expected: exit 3, verdict VIRUS_FOUND, a signature containing EICAR, and a
duration. Check the same job in the Web UI. Local endpoint protection may block
the download before ClamAV sees it; that is a local detection, not proof that
the Web UI scan ran. Do not disable endpoint protection to force the download.
Download the file only into the isolated lab directory on a suitable test host.

For quarantine, enable it under Settings only if you intend to retain detected
lab files. Upload scans affect the server's uploaded copy; the local original
on your Mac is not removed by the CLI. `RECORDED` means a quarantine location
was recorded. `NOT_RECORDED` can mean quarantine was disabled. `UNKNOWN` for
some PATH/WATCH history does not prove that no file was quarantined.

## 6. Prometheus: enable, authenticate and verify counters

Open Monitoring & clients. Select **Generate new token & enable**, copy the
one-time scrape token to a private `metrics.token` file, and keep the API token
file separate. Set its value in the test shell without printing it:

```bash
export CLAMAV_METRICS_TOKEN="$(cat "$HOME/clamav-lab/metrics.token")"
curl --fail --cacert /path/to/lab-ca.crt \
  -H "Authorization: Bearer $CLAMAV_METRICS_TOKEN" \
  "$CLAMAV_SERVER/actuator/prometheus" > "$HOME/clamav-lab/metrics.txt"
rg 'clamav_(scans_total|viruses_detected_total|queue_items|threads_live|database_timestamp)' \
  "$HOME/clamav-lab/metrics.txt"
```

Omit `--cacert` only when your system already trusts the CA. Without `rg`, use
`grep -E`. Expect Prometheus text and canonical names. Scan counters are created
after their first corresponding scan and reset when the application restarts.
Run a clean and EICAR scan while scraping and compare subsequent samples. Expect
`type="upload"`, `verdict="ok"` / `verdict="virus_found"` and an EICAR signature.
Histogram metrics use suffixes `_bucket`, `_count`, `_sum`.

Negative checks:

- An unsigned request, an API token, and a browser login without the scrape token
  should not grant scraping. The application responds 403 to a bad scrape token.
- Turn **Allow Prometheus scrapes** off and Save. The same scrape token must now
  fail immediately. Turn it on with the replacement field blank: it should work.
- Generate a replacement token. The old token must fail and the new token work.
- Recreate the Web UI container with the existing conf volume. The saved control
  and token remain active. Existing API tokens should still work independently.

If the proxy returns 404, ensure that it routes this exact endpoint. If using a
separate management port, protect and route that listener too. Do not broadly
expose all `/actuator/**` routes. A 403 after reaching the application is distinct
from a reverse proxy's 401.

## 7. Optional lab Prometheus and Grafana

Copy `examples/home-lab/monitoring.compose.yml` and `prometheus.yml` into a new
lab directory on a Docker host. Create its private `metrics.token`, copy the
public issuing CA to `lab-ca.crt`, and edit the Prometheus target to your real
HTTPS hostname and port. That hostname must resolve from the Prometheus
container and match the certificate. For a publicly trusted CA, remove the
`tls_config.ca_file` entry and CA mount rather than disabling TLS verification.

Prometheus runs as its own container user: make the mounted token file readable
to that user using a specific group/ACL or a root-owned secrets directory. The
example mounts the token read-only and runs the lab Prometheus
process as your current UID/GID. Do not make real credentials world-readable.

```bash
export CLAMAV_LAB_UID="$(id -u)"
export CLAMAV_LAB_GID="$(id -g)"
read -r -s -p 'Choose a lab Grafana admin password: ' GRAFANA_ADMIN_PASSWORD
printf '\n'
export GRAFANA_ADMIN_PASSWORD
docker compose -f monitoring.compose.yml config --quiet
docker compose -f monitoring.compose.yml up -d
```

The lab example binds Prometheus at `127.0.0.1:9090` and Grafana at
`127.0.0.1:3001`. From your Mac, tunnel these ports if Docker is on your server:

```bash
ssh -L 9090:127.0.0.1:9090 -L 3001:127.0.0.1:3001 YOUR_SSH_USER@192.168.68.112
```

Open http://127.0.0.1:9090/targets: the `clamav-webui` target should be UP. Query:

```promql
up{job="clamav-webui"}
sum(rate(clamav_scans_total[5m]))
sum by (verdict) (increase(clamav_scans_total[15m]))
sum by (signature) (increase(clamav_viruses_detected_total[15m]))
clamav_queue_items
clamav_threads_busy / clamav_threads_max
time() - clamav_database_timestamp
```

For `increase`/`rate`, scrape at least twice before generating test scans and
leave it running for several minutes. An instant value of one counter is not
the same as an increase observed over a time window.

Open Grafana at http://127.0.0.1:3001, sign in as `admin` with your chosen lab
password. Add a Prometheus data source with URL `http://prometheus:9090`. Download
the dashboard from Monitoring & clients, import its JSON and select that data
source and job. Expect scan-rate/verdict panels, signatures after a positive
scan, thread/queue panels and database freshness. `slot` 0–11 identifies a
bounded telemetry slot; Configuration maps slots to endpoint names.

The provided alert rules are examples, not automatic notifications. To evaluate
them, copy `dashboards/clamav-alerts.yml` as `clamav-alerts.yml` alongside the lab
Compose file, mount it read-only into Prometheus and add its path to `rule_files`.
Alertmanager/receivers must be configured separately for actual notifications.
Do not stop the production daemon to create an alert. Use a separate disabled or
unreachable lab endpoint and confirm unavailable telemetry is not shown as zero.

## 8. Test MCP with your assistant or the supplied protocol checker

Use the same private CLI configuration/API token. For Claude Desktop/Cursor,
adapt `examples/mcp/stdio.json`: set the real absolute `clamctl` path, private CLI
configuration path and upload root. Start/reload that server in the MCP client.
Expect five tools and three resources. Try these tool calls with real paths/IDs:

| Call | Expected |
|---|---|
| `clamav_check_health` with `{}` | ClamD status, definition timestamp, threads and queue |
| `clamav_list_endpoints` with `{}` | Only authorized endpoints |
| `clamav_scan_file` with `{"file_path":"/ABSOLUTE/clamav-lab/clean.txt"}` | FINISHED / OK, signature list empty, duration |
| `clamav_scan_file` with the EICAR file path | VIRUS_FOUND, EICAR signature, duration |
| `clamav_get_job` with `{"job_id":"ACTUAL_ID"}` | Same verdict, recorded quarantine status, safe error details if ERROR |
| `clamav_scan_path` with `{"path":"/scandir/clamav-lab"}` | Job ID and ACCEPTED; then poll get_job |
| Read `clamav://stats` | Safe live statistics |
| Read `clamav://jobs/recent` | Up to 20 authorized recent jobs |
| Read `clamav://audit/recent` | Up to 20 authorized security events |

`scan_file` reads a file on the MCP process host. `scan_path` reads the
Web UI/ClamD host. These are different machines unless deliberately co-located.
Never ask the assistant to execute EICAR or a suspicious artifact.

For a reproducible stdio protocol test without an AI subscription, run the
provided Python 3 checker from the full source root:

```bash
python3 scripts/home-lab-mcp-test.py \
  --clamctl /absolute/path/clamctl \
  --upload-root "$HOME/clamav-lab"
```

It checks initialization, discovery, health, endpoints, a clean streamed upload,
get_job, all resources and rejection of an outside-root file. To include a
positive detection and an authorized server path scan, explicitly add:

```bash
python3 scripts/home-lab-mcp-test.py \
  --clamctl /absolute/path/clamctl --upload-root "$HOME/clamav-lab" \
  --eicar-file "$HOME/clamav-lab/eicar.txt" \
  --server-path /scandir/clamav-lab
```

Optional HTTP MCP: start with `--transport http`, a literal loopback listener and
`--http-token-file` containing a different random token. Verify unauthenticated
HTTP requests fail and a client with that HTTP token can discover/call tools.
Put a TLS proxy in front for remote access. Static Bearer headers are supported;
hosted clients requiring OAuth need a compatible gateway. See `docs/MCP.md`.

## 9. Understand and test the Configuration page

The page now has three levels:

1. **Live overview:** what ClamD actually reported. Available Yes, a recent
   sample, definitions that continue updating, queue returning toward zero,
   and busy threads below max most of the time are healthy signs. Brief queue
   spikes are normal. Unknown means unavailable, not zero.
2. **Effective daemon scan policy:** extra limits/exclusions that the Web UI
   inherited for the endpoint named `default`. Limits are in bytes. This does
   not prove that ClamD itself reloaded its configuration.
3. **Advanced sections:** raw daemon output, file directives and precedence for
   troubleshooting. Credentials such as freshclam proxy passwords are redacted.

For a remote daemon without shared files, **Not mounted** is normal. Endpoint
Health, scanning, CLI, MCP and Prometheus do not require mounted configuration.

For a controlled inheritance test, mount a dedicated lab configuration directory
read-only into the Web UI. Start in inspection-only mode. First use a copy of
your real clamd.conf so you do not accidentally redirect the default connection.
In your existing Web UI Compose service add, adjusting the host directory:

```yaml
environment:
  CLAMAV_CONFIG_PATH: /daemon-config/clamd.conf
  FRESHCLAM_CONFIG_PATH: /daemon-config/freshclam.conf
  CLAMAV_CONFIG_DISCOVERY_ENABLED: 'false'
volumes:
  - /YOUR/LAB/CONFIG:/daemon-config:ro
```

Recreate only the Web UI service and inspect Configuration. Verify LOADED and
the paths. Change a harmless directive in the copied file, wait one polling
interval and verify the new value appears. This changes the observed file only,
not the running daemon. Included files must remain within the mounted directory.

To test policy inheritance, use a lab default endpoint and enable inheritance in
the UI. Keep its TCP settings pointed at the correct lab daemon. If CLAMAV_HOST
or CLAMAV_PORT is explicitly set in Compose, it keeps control of the connection;
file-based size limits/exclusions can still apply. Otherwise file discovery can
change the default endpoint address. LocalSocket takes priority over TCP and
requires an actual mounted socket; do not select one reachable only on another
host.

Set `StreamMaxLength 1M` in the copied lab clamd.conf. Upload a 2 MiB harmless
file:

```bash
python3 - <<'PY'
from pathlib import Path
Path.home().joinpath('clamav-lab/two-mib.txt').write_bytes(b'A' * (2 * 1024 * 1024))
PY
clamctl scan upload "$HOME/clamav-lab/two-mib.txt" --wait --json
```

Expected: the application rejects it under the inherited limit. The effective
policy shows 1,048,576 bytes. Disable inheritance: the normal application upload
limit applies again, but the real ClamD can still enforce its own stream limit.
Do not conclude that disabling a Web UI limit disables the daemon's limit.

For last-known-good handling, with inheritance active change the copied value
to `StreamMaxLength invalid`. Expect a warning and retention of the last valid
policy. Restore a valid value. A bad file on initial startup, with no valid
previous policy, pauses managed-default scanning until corrected. For exclusions,
add `ExcludePath \.skip$` and test a small lab directory with both a `.skip`
file and a normal file. All-excluded must not become a fabricated clean result.

Pause observation and save: expect PAUSED. Polling controls change the Web UI's
behavior only; they do not stop ClamD, freshclam, scans, metrics or PostgreSQL.
Restore your original observation mode and test configuration when finished.

## 10. Permissions, revocation and cleanup

- Sign in as a VIEWER/OPERATOR: admin pages, integration settings and downloads
  must be forbidden. Repeat with ADMIN: they should work.
- Create a health-only API token: status should work, scan/audit should be denied.
- For an endpoint-restricted token, attempt an unlisted endpoint ID: denied.
- Call MCP scan_file with a real file outside the configured upload root: denied.
- Revoke the temporary API token in the UI while clamctl/MCP are configured with
  it. Their next API request must fail. Existing accepted jobs can still finish.
- CLI logout removes saved credentials but does not revoke the server token.
- Stop only the optional lab monitoring stack using its specific Compose file:
  `docker compose -f monitoring.compose.yml down`. Keep production services intact.
- Delete only the lab fixture files you created. Restore any changed default
  endpoint policy. Remove test tokens, and unset API/metrics secret shell variables.

Record each test's job ID, time, expected/actual verdict, CLI exit code and any
error. Logs and screenshots should omit passwords and tokens.

Reference: [EICAR test file](https://www.eicar.org/download-anti-malware-testfile/),
[Prometheus configuration](https://prometheus.io/docs/prometheus/latest/configuration/configuration/),
and the project `docs/CLI.md`, `docs/MCP.md`, `docs/PROMETHEUS_GRAFANA_METRICS.md`.
