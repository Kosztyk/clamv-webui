# One configuration editor and live Scan Jobs

This v2.1.0 source revision consolidates global application configuration in
**Administration → Configuration center**. The ordinary Docker build includes
the changes; no build helper, homelab URL or reverse proxy is required.

## Audit of the former editors

| Former location | Fields audited | Sole editing location now |
|---|---|---|
| Settings | Allowed roots, upload size, worker concurrency, upload/quarantine storage, quarantine enable, global watcher enable/interval | Configuration Center → Scanning |
| Settings / webhook panel | Enabled receiver and private webhook URL, including explicit clearing | Configuration Center → Notifications; clearing also disables delivery |
| Authentication / LDAP | Enabled, URL, base/bind DNs, user and group search bases/filters, member/name attributes, role maps, nested groups/depth, connect/read timeouts, referrals, pooling, StartTLS and bind credential | Configuration Center → Identity & API → LDAP |
| Authentication / OIDC | Enabled, issuer, client ID/secret, scopes, group claim, role maps, button label and logout behavior | Configuration Center → Identity & API → OpenID Connect / SSO |
| Monitoring & clients | Scrape enable/token, generation and defaults | Configuration Center → Monitoring → Prometheus & Grafana |
| Configuration | Observation enable, inheritance and polling, plus existing file paths | Configuration Center → Monitoring → Mounted daemon configuration |
| Endpoints / default row | Default engine name, host/socket, port, platform and enable state | Configuration Center → Connections → Managed ClamAV scans |

The shared catalogue still covers all **107 global fields**: Connections 28,
Scanning 12, Identity & API 37, Notifications 9, Network 8, Monitoring 10 and
System 3. The [field reference](CONFIGURATION_REFERENCE.md) lists every key,
input alias, default and activation rule from that same catalogue.

Settings and Authentication are removed from the sidebar. Old GET bookmarks
redirect to the appropriate central category. Their old POST writers, the old
provider tests, Prometheus writes and observation writes return **409** with a
central link and do not modify either storage layer. They cannot bypass review,
CSRF, deployment locks or local administrator verification.

**Daemon status & files** retains read-only mounted-file inspection, effective
policy and live telemetry. **Monitoring & clients** retains status, Grafana and
alert downloads, and CLI/MCP instructions. Additional named engines, local
users, tokens and individual watched-directory records remain separate
operational resources on their management pages. The default engine has no
second edit/delete form there, and additional engines cannot take its reserved
name. Its selected record remains stable across renames and process recreation.
Job submission controls and personal theme/language controls also remain in
their operational locations.

## Provider parity and retained state

LDAP exposes all **20 runtime options** and both credential-source fields.
The former basic attributes, timeouts, referrals, nesting and pooling options
are visible together. Nesting depth is conditional on nesting; mounted secret
files remain advanced. OIDC exposes all **10 runtime options**, both credential
sources and callback guidance derived from the actual application context.
An automated parity check compares runtime option names with central mappings.

Both providers use the same backend validation used by the runtime. LDAP depth
is 1–8 and timeouts are 1000–30000 ms. OIDC scopes accept commas/spaces and are
saved canonically, preventing a false pending-restart warning. Disabled
provider drafts bind only the application's own configuration namespace;
standard Spring OAuth aliases remain deployment inputs and are not synthesized
from partial GUI drafts. This prevents an incomplete disabled OIDC draft from
blocking startup.

Existing provider rows, matching encryption keys, legacy application settings,
hashed scrape credentials and explicit deployment configuration remain intact.
Editing one central LDAP option retains the other legacy values and an
untouched bind credential. Nothing resets credentials merely because a form
moved. New central secrets are encrypted, remain write-only, and require the
existing sensitive-change verification. Provider changes require restart;
Compose/environment overrides remain authoritative and locked in the GUI.

Prometheus token generation creates a local draft. Copy it, then review/apply
to activate it. **Stage application defaults** stages reviewed built-in values
and credential clearing, preserving deployment-controlled fields. Observer
mode/poll changes immediately refresh the active snapshot without editing or
reloading the daemon's own files.

## Scan Jobs refresh

The table fetches an authenticated, uncached server-rendered fragment every
**2 seconds while listed jobs are queued/running**, and every **5 seconds**
otherwise. Finished status and verdict update without navigation or reload.
The same shared table refreshes on Scan and on the dashboard for authorized
operators/administrators. Viewer-only dashboards retain their read-only table
without attempting the operator endpoint.

Requests do not overlap, have a 10-second browser deadline, pause when hidden
and stop when leaving the page. Temporary failures retain the last result and
retry. Authentication failures pause updates. Changes preserve table scroll
and focused job links. Job/administration pages are excluded from the tab cache,
including deployments under a servlet context prefix.

The list endpoint retains operator authorization, bounded history, HTML escaping
and `Cache-Control: no-store`. No scan worker waits on the browser refresh.
API/CLI/MCP transport and authorization rules remain unchanged.

## Verification

See [the current validation report](CONFIGURATION_CENTER_VALIDATION.md). The
real packaged-app browser regression seeds a legacy encrypted LDAP row in a
temporary H2 database, verifies every option in the GUI, applies partial edits,
recreates the process and checks the retained values and credential. It also
submits three uploads to a gated synthetic ClamD server: two running workers
and one queued job must become finished with clean, detected and error verdicts
without navigation. Tests use synthetic credentials and isolated volumes.

```bash
mvn -B -ntp clean verify
python3 scripts/test_configuration_center_consolidation.py
python3 scripts/test_configuration_center_direct.py
python3 tools/verify-configuration-center.py
bash scripts/release-gate.sh --static-only
```

The browser scripts require Playwright/Chromium and the JDK. These tests do not
claim live LDAP/SSO provider acceptance or a Docker image build on this host.
