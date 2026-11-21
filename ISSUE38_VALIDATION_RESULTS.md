# Issue #38 — source candidate validation (2026-11-13)

## Completed in this environment

- `python3 scripts/check-issue38-clamdtop-security.py` — **22/22 PASS**: only one launcher, environment allowlist, authenticated identity, synchronized admission, launch/close race guard, per-user and global caps, cleanup, browser heartbeat, idle expiry, shutdown, security tests and configuration.
- `python3 scripts/check-issue37-maintenance.py` — **15/15 PASS**: the container maintenance fixes remain intact.
- `python3 scripts/check-issue36-h2-source.py` — **10/10 PASS**: H2 graceful shutdown fixes remain intact.
- `python3 scripts/check-safe-scanning-source.py` — **PASS**.
- `python3 tools/audit-source-package.py .` — **PASS**, no known credentials/runtime data identified by the bundled offline scanner.
- `node --check src/main/resources/static/js/native-clamdtop.js` — **PASS**.
- Native handler and quota compile under Java 21 with purpose-built **isolated API stubs**, not with the actual Spring/Jackson dependency graph — **PASS**.
- A standalone Java 21 smoke test spawned the command from `newClamdtopProcessBuilder` as `/usr/bin/env`; the real subprocess reported exactly `PATH`, `COLUMNS`, `LINES` and `TERM` — **PASS**.
- A standalone Java 21 24-thread reservation race admitted exactly six connections (three users, two per user), and no more — **PASS**.
- A standalone Java 21 handler lifecycle smoke (isolated API stubs, real disposable `/bin/cat` process) checked per-user rejection, admission of a second user, disconnect, idle-timeout reaping, process teardown, and slot reclamation — **PASS**.
- New issue #38 tests are included in the repository and CI workflow.

## Not executed here

- Maven `mvn test` / `mvn verify` using real Spring, Jackson, JUnit, Mockito and the full dependency tree (Maven unavailable; DNS cannot resolve Maven Central).
- Docker image build, live ClamdTop ncurses PTY, actual ClamD connectivity, authentication/permissions through Spring Security and proxy upgrades (Docker daemon unavailable).
- Full browser tab heartbeat/visibility test using a real browser. The JS syntax and server unit smoke passed, but browser timer behavior must still be verified in deployment.

## Release gate

```sh
python3 scripts/check-issue38-clamdtop-security.py
node --check src/main/resources/static/js/native-clamdtop.js
mvn -B '-Dtest=ClamdtopSessionBudgetTest,Issue38NativeClamdtopSecurityTest,NativeClamdtopConnectionTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue38 .
```

Then follow the manual two-user/three-tab, idle/heartbeat, and process-environment steps in `docs/ISSUE38_NATIVE_CLAMDTOP_SECURITY.md` before claiming the full issue acceptance criteria. Issue #38 has not been closed and no GitHub code has been changed by this archive.
