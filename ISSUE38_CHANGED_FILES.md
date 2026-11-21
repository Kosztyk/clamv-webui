# Issue #38 — Native clamdtop subprocess / resource isolation

**Baseline:** `clamav-webui-v2.1.0beta2-issue37-container-maintenance-full-source.zip` (includes #36 and #37 fixes).

## Changed

- `src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocket.java` — exact four-key environment allowlist, authenticated principal admission, eight global/two-per-user quotas via atomic reservation, idempotent cleanup, child-process teardown, heartbeat/idle timeout, JVM shutdown handling.
- `src/main/resources/static/js/native-clamdtop.js` — 30-second visible-tab heartbeats, timer cleanup when socket disconnects.
- `src/main/resources/application.properties` — optional `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS`, default 180 seconds.
- `README.md`, `docs/COMPLETE_USER_GUIDE.md`, `docs/releases/v2.1.0beta2.md` — limits, deployment and user-visible behavior.
- `ISSUE37_CHANGED_FILES.md` — note that the unused legacy class was retired by issue #38.

## Added

- `src/main/java/info/trizub/clamav/webclient/web/ClamdtopSessionBudget.java` — synchronized reservation and idempotent release.
- `src/test/java/info/trizub/clamav/webclient/web/ClamdtopSessionBudgetTest.java` — per-user denial and 24-way admission race.
- `src/test/java/info/trizub/clamav/webclient/web/Issue38NativeClamdtopSecurityTest.java` — real `/usr/bin/env` subprocess allowlist, WebSocket two-per-user behavior, launch failure cleanup, heartbeat and inactivity expiration.
- `scripts/check-issue38-clamdtop-security.py` — offline source contract checks.
- `.github/workflows/issue38-clamdtop-security.yml` — Maven + browser JS + source-contract tests on PR.
- `docs/ISSUE38_NATIVE_CLAMDTOP_SECURITY.md` — threat model, configuration, runtime acceptance test.
- `ISSUE38_VALIDATION_RESULTS.md` — local evidence and environment limitations.

## Removed

- `src/main/java/info/trizub/clamav/webclient/web/NativeClamdtopWebSocketHandler.java` — unused Spring component with an independent secret-inheriting process launcher.

No Flyway migration, Dockerfile base-image changes, JDBC changes, security-role changes, or published release in this revision.

## Follow-up: Configuration Center timeout field

The issue #38 native session idle timeout is now editable as **Monitoring → Native ClamdTop sessions → Native ClamdTop idle timeout (seconds)**. See `ISSUE38_CONFIG_CENTER_CHANGED_FILES.md` and `ISSUE38_CONFIG_CENTER_VALIDATION.md`. The property `clamav.clamdtop.idle-timeout-seconds` persists in Configuration Center; `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS` remains an optional authoritative Compose override. Default 180, range 1–86400, restart required.
