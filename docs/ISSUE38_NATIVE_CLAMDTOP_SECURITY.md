# Issue #38 — Native clamdtop subprocess isolation and monitor resource limits

**Change status:** Implemented in the source candidate, not a published image/release. Runtime verification on a JDK 21 + Maven + Linux/Docker machine is required before closing the issue.

## Risk and remediation

- The only registered terminal is `NativeClamdtopWebSocket` on `/ws/clamdtop` and continues to use the existing authenticated `SessionWebSocketGuard`. The unused older `NativeClamdtopWebSocketHandler` component has been deleted to avoid maintaining a second process launcher.
- `newClamdtopProcessBuilder` clears the entire inherited parent environment **before** introducing four fixed settings: `TERM=xterm-256color`, `COLUMNS=<bounded terminal width>`, `LINES=<bounded terminal height>`, and `PATH=/usr/bin:/bin`. No `CLAMAV_*`, LDAP/OIDC secrets, Java options, or arbitrary inherited keys are copied. A test changes the command to `/usr/bin/env` and checks a real child process against the exact whitelist.
- A single in-process `ClamdtopSessionBudget` synchronizes admissions, so its global cap **8** and **per-authenticated-user cap 2** are atomic even under parallel upgrades. All browser tabs belonging to the same username share the per-user quota; a different user still has room while a single user has two processes.
- Reservations are taken before native process creation. On connection refusal, endpoint failure, process startup failure, WebSocket close, session revocation, PTY exit, idle timeout, and JVM shutdown, cleanup stops the `script` parent and any clamdtop descendants and releases the reservation **once**.
- The browser issues JSON `{ "type": "heartbeat" }` every 30 seconds **only while the page is visible**. The server refreshes activity on heartbeats or valid terminal input, not on ClamD's periodic output, so abandoned/hidden terminals expire. A scheduler reaps expired sessions every 30 seconds. Default inactivity limit **180 seconds**.
- The native monitor remains read-only apart from its existing normal ncurses keyboard controls; the server still chooses the configured endpoint and safely quotes the connection spec.

## Configuration Center (GUI)

Open **Administration → Configuration Center → Monitoring → Native ClamdTop sessions**.
Set **Native ClamdTop idle timeout (seconds)** to an integer from **1 to 86400** (default **180**), select **Review changes → Apply**, and **restart the Web UI**. Values are persisted in the existing Configuration Center store under `conf` and take effect when the WebSocket handler is reconstructed at application startup. No Compose setting is needed if the GUI manages this value.

The section remains accessible when **Prometheus & Grafana** is disabled: native sessions do not depend on metrics scraping. This setting changes only the inactivity threshold; the **2 per user / 8 global** process caps and **30-second** reaper cadence remain fixed. Prefer **90 seconds or longer** for reliable visible-tab heartbeat operation. Do not confuse this setting with the separate ClamdTop **snapshot cache TTL**.

If the Compose service supplies `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS`, Configuration Center shows the effective deployment value and marks the field **read-only**. Remove the variable and **recreate** the container to return control to the GUI. An older saved value may then take effect. The environment value overrides the saved GUI value without deleting it.

## Deployment configuration

`application.properties`:

```properties
clamav.clamdtop.idle-timeout-seconds=${CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS:180}
```

Optional Compose override under `clamav-web-client.environment` (not needed when configured in the GUI):

```yaml
CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS: "180"
```

The inactivity interval accepts 1–86400 seconds. Use at least 90 seconds for browser timer throttling and network delays; the default is 180. The quotas are intentionally fixed at 8 global / 2 per user for the beta release. The server-side idle timer applies **per WebSocket** and is independent from the login session idle timeout. After a hidden tab has expired, click **Connect** to start a new monitor.

## Verification

```sh
python3 scripts/check-issue38-clamdtop-security.py
node --check src/main/resources/static/js/native-clamdtop.js
mvn -B '-Dtest=ClamdtopSessionBudgetTest,Issue38NativeClamdtopSecurityTest,NativeClamdtopConnectionTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue38 .
```

Manual interactive smoke test with a configured ClamD endpoint:

1. Sign in as one VIEWER and open `/clamdtop` in two browser tabs; both must connect. Open a third tab under **the same account**: the server must reject it with a per-user-cap message and must not start a third process.
2. Sign in as a different account in a separate browser profile and connect. This must work while the first account uses its full allowance.
3. Disconnect or close one of the first account's tabs. Reconnect its third tab; the freed slot must be immediately available.
4. Keep a visible tab connected for longer than three minutes without typing; heartbeats must keep it alive. Leave a tab hidden for longer than three minutes plus up to 30 seconds reaper delay: it must be closed and its slot available to other users.
5. Revoke a login session or stop the container gracefully: its monitor process and descendants must terminate.
6. With permission to inspect your test container, check the `/proc/<pid>/environ` of the spawned `script` process. It must not contain `CLAMAV_AUTH_SETTINGS_KEY`, REST tokens, LDAP bind passwords, or OIDC client secrets. The command's environment is intentionally limited to the four documented keys.

### Compatibility and limitations

- No changes to the HTTP ClamdTop `/api/clamdtop` snapshots, ClamD metrics, endpoints, scan jobs, database, WebSocket URL, permission roles, reverse-proxy configuration, or H2/PostgreSQL/MySQL/MariaDB support.
- `TERMINFO` overrides from the legacy, unused handler are not needed: the issue #37 image supplies `ncurses-term` on the default system search path.
- Capacities are **per Web UI JVM instance**, not a distributed cluster-wide budget. Do not present them as a multi-replica global limit; production multi-node capacity enforcement requires a shared coordinator or per-node ingress constraints.
- A deliberately connected user can maintain up to two native processes indefinitely while their page is visible; the limit prevents that user from exhausting all eight. Idle expiry is not a hard total session duration.
- Source-only checks do not prove the live child environment, browser timer behavior, or image startup. Confirm the Maven tests and manual smoke test before closing GitHub issue #38.
