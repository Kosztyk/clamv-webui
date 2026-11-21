# Issue #38 — Configuration Center idle timeout validation

## Automated source validation (this environment)

- `python3 scripts/check-issue38-config-center.py` — **17/17 PASS**.
- `python3 scripts/check-issue38-clamdtop-security.py` — **22/22 PASS**.
- `python3 scripts/check-issue37-maintenance.py` — **15/15 PASS**.
- `python3 scripts/check-issue36-h2-source.py` — **10/10 PASS**.
- JSON parse for the 122-field catalog — **PASS**.
- JavaScript syntax (`node --check` for Configuration Center and native browser terminal) — **PASS**.

Maven, Docker, and live browser deployment tests were **not run** in this environment. Offline source checks cannot establish that the whole Maven suite passes or that a particular running deployment uses the setting.

## Run Java tests (Java 21 and Maven required)

```sh
mvn -B '-Dtest=Issue38ClamdtopIdleConfigurationCenterTest,Issue38NativeClamdtopSecurityTest,ClamdtopSessionBudgetTest,NativeClamdtopConnectionTest' test
mvn -B verify
```

## Manual GUI / Docker acceptance

1. Ensure `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS` is **absent** from the running container's environment. Verify `/app/conf` persistence. Start the updated image.
2. Open **Administration → Configuration Center → Monitoring → Native ClamdTop sessions**. Confirm the numeric field shows **180**, and remains editable even when Prometheus is disabled.
3. Enter `300`. Select **Review changes → Apply**. Confirm the **Restart required** message names the field. Restart the application, then confirm **300** remains saved and is active for new native sessions.
4. Reject `0`, `86401`, non-integers and blank values. `1` and `86400` are accepted (do not use 1 for real passive monitoring).
5. Add a Compose override `CLAMAV_CLAMDTOP_IDLE_TIMEOUT_SECONDS: "600"`, recreate the container, and confirm the GUI shows **600** and marks the field **Deployment configuration** / read-only. Attempted review/changes to this field must be rejected. The saved **300** must remain intact.
6. Remove the override, recreate the container, and verify the GUI again shows editable **300**. Exercise hidden-tab idle cleanup (duration plus up to 30 seconds) and visible-tab heartbeat continuation against a test ClamD instance.
7. Re-run the issue #38 subprocess/environment tests and monitor-resource tests. Verify the limit remains 2 processes/user, 8 global and child secrets remain excluded.

No GitHub push, Docker build, or live WebSocket verification was performed as part of preparing this archive.
