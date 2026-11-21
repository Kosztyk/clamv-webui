# Issue #38 follow-up — Native ClamdTop idle timeout in Configuration Center

**Baseline:** `clamav-webui-v2.1.0beta2-issue38-native-clamdtop-security-full-source.zip` (retains all issue #36–#38 changes).

## Behavior

The `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS` option is now fully configurable from the built-in GUI:

**Administration → Configuration Center → Monitoring → Native ClamdTop sessions → Native ClamdTop idle timeout (seconds)**.

The field is not gated behind Prometheus. It defaults to `180`, accepts integer seconds `1..86400`, and uses the existing **Review changes → Apply** mechanism. Saving a new value indicates **Restart required**, because `NativeClamdtopWebSocket` reads the property at startup. The saved value survives restarts as long as `/app/conf` is persistent. Normal authenticated administrator and protected recovery authorization requirements still apply.

Optional Docker Compose configuration:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS: "180"
```

**Do not add the variable to Compose if you want the GUI to manage it.** A deployment environment value takes precedence, makes the GUI field read-only and does not erase the saved GUI value. Remove the variable and recreate the container to return control to Configuration Center.

This option does not affect the fixed per-user/global process quotas (2/8), visible-tab heartbeat (30 seconds) or scheduler frequency (30 seconds).

## Changed / added files

- `src/main/resources/configuration-center/catalog.json` — dedicated ungated Monitoring section and validated `clamav.clamdtop.idle-timeout-seconds` field with Compose alias.
- `src/main/resources/static/js/configuration-center.js` — section icon and live draft summary of the idle timeout.
- `src/test/java/info/trizub/clamav/webclient/configuration/Issue38ClamdtopIdleConfigurationCenterTest.java` — new four-case JUnit regression: catalog/bounds, GUI persistence/restart, deployment override/read-only lock, and actual Spring constructor value binding.
- `scripts/check-issue38-config-center.py` — independent 17-contract offline check.
- `README.md`, `docs/CONFIGURATION_CENTER.md`, `docs/CONFIGURATION_REFERENCE.md`, `docs/CONFIGURATION_FIELD_AUDIT.md`, `docs/COMPLETE_USER_GUIDE.md`, `docs/ISSUE38_NATIVE_CLAMDTOP_SECURITY.md`, `docs/releases/v2.1.0beta2.md` — explain GUI location, precedence, default, acceptable range and activation.
- `ISSUE38_CONFIG_CENTER_VALIDATION.md` — source validation and runtime test instructions.

`NativeClamdtopWebSocket.java` and `application.properties` already consume the existing canonical `clamav.clamdtop.idle-timeout-seconds` property; no parallel configuration service, second setter or DB migration is introduced.
