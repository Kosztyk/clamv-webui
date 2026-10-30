# Administration controls and home-lab testing

Validated on 30 October 2026 (Europe/Bucharest), extending the delivered Docker
build fix package. LDAP/OIDC, PostgreSQL and the other database migrations,
Prometheus metric names, the CLI/MCP implementation and all six client binaries
are retained.

## Changes

- Every administration link has an icon, consistent spacing and an active-page
  indicator. Administration pages share the same title/icon treatment. Settings
  now names scan policy fields in plain language and uses selectors for toggles.
- Monitoring & clients provides a live Prometheus export switch, one-time token
  generation, replacement and return to container defaults. UI-managed scrape
  tokens are stored only as SHA-256 hashes in an owner-private file on the conf
  volume. Existing environment tokens remain compatible until explicitly
  overridden. Missing credentials still fail closed.
- Configuration provides observation/inheritance switches, a live polling
  interval, an explained connection/definition/thread/queue overview, and
  collapsed advanced information. Form controls sit outside the automatically
  refreshed content. The refresher preserves expanded details.
- CLI/MCP setup explains their separate processes, host-relative paths and scoped
  credentials. Database connections, file mounts and listener ports are labelled
  as deployment settings requiring recreation rather than nonfunctional live
  switches.
- Grafana dashboard, alert rules and the home-lab guide are packaged as admin-only
  downloads. The guide has ten test groups with commands, expected outcomes and
  cleanup. Optional Prometheus/Grafana Compose files and a dependency-free Python
  MCP protocol checker are supplied.

Saved monitoring/observer controls are per Web UI instance, stored in
`conf/integrations.properties`. They are not a distributed configuration bus.
The conf volume must be retained across container recreation. Disabling file
inheritance removes additional file-based policy; normal application and daemon
limits continue to apply. Saving never rewrites clamd.conf or restarts ClamD.

## Verification

| Check | Result |
|---|---|
| Full Maven `verify` | Passed: 216 cases, 211 passed and 5 skipped, no failures/errors |
| Final live-control regression class | Six cases passed, including an additional observation/policy transition case added after the full run |
| Combined Java case inventory | 217 cases, 212 passed and 5 skipped |
| Go race suite and vet | Passed |
| Configuration refresh JavaScript | Five behavior tests passed, including retained advanced-section state |
| Locale catalog checks | Passed; new core controls/headings added to all five translated catalogs |
| Lab configuration/workflow YAML and Python checker syntax | Parsed successfully |
| Real Spring / native CLI / MCP integration | Passed with Basic/CSRF and scoped Bearer tokens, isolated H2 and a synthetic ClamD fixture |
| New home-lab MCP checker | Executed successfully against the real Spring integration, including clean/EICAR fixtures, a server path, all resources and expected outside-root denial |

The live-control tests cover admin authorization, CSRF, immediate scrape
enable/disable, token replacement/rotation, preservation on blank input, hashed
persistence and reload, non-disclosure, download packaging and all nine
administration navigation icons/active states. Observer checks cover inspection,
inheritance, pause, persisted mode, invalid-file handling and resumption with a
new valid limit. Existing identity, database, metrics, scan-policy and API
regression cases pass.

Five native AF_UNIX Java cases are skipped because the execution environment
prohibits Unix socket creation. Chromium was downloaded and launched for
desktop/mobile checks but aborted at its process-singleton socket for the same
restriction. Browser screenshots and pixel/viewport validation are therefore
**not certified**. Server-rendered HTML, active navigation, form authorization,
downloads and refresh behavior were checked through MVC and Node tests.

The integration daemon is synthetic: these checks validate transport, scopes,
job persistence and adapter behavior, not real anti-malware accuracy. Your LDAP,
PostgreSQL server, production proxy, Prometheus/Grafana containers and actual
ClamD were not accessed. Use `HOME_LAB_TESTING.md` to perform those deployment
checks. No Docker engine was available here for a complete image build.
