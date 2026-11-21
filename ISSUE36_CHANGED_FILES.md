# Issue #36 — additional verification hardening

This full-source package is derived from the uploaded `clamav-beta2-issue36-full(1).zip` (v2.1.0beta2). That archive **already contained** the primary issue #36 fix; those implementation changes have been retained unchanged.

## Changes in this revision

| File | Change |
|---|---|
| `src/test/java/info/trizub/clamav/webclient/configuration/Issue36H2ShutdownTest.java` | Adds a real HikariCP file-backed H2 pool close/reopen persistence test, and strengthens the legacy-upgrade regression to start from an **open old-format -1 database** and reset its persistent delay without a manual SHUTDOWN |
| `scripts/verify-issue36-h2-shutdown.sh` | Tests the built image's actual default H2 URL rather than forcing it via environment; treats SIGKILL or OOM as failed smoke tests |
| `scripts/check-issue36-h2-source.py` | Adds offline source checks for those regressions, including the live legacy -1 to 0 transition |
| `docs/ISSUE_36_H2_SHUTDOWN.md` | Clarifies smoke-test behavior and HikariCP coverage |
| `README.md` | Links to the enhanced validation coverage |
| `docker-compose.minimal.yaml`, `docker-compose.environment.yaml`, `docker-compose.configuration-center.yaml` | Adds the same 60-second graceful stop period as the main Compose file |

## Preserved existing issue #36 fixes

- `src/main/resources/application.properties`: H2 file default resets persistent delay using `DB_CLOSE_DELAY=0` with no `DB_CLOSE_ON_EXIT=FALSE`.
- `src/main/resources/configuration-center/catalog.json` and `src/main/resources/static/js/configuration-center.js`: same safe URL.
- `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationStore.java`: maps only the exact old saved default to the new default in memory, without altering user configuration files.
- `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java`: rejects unsafe H2 file-based closure options at Configuration Center.
- `docker-compose.yml`: 60-second shutdown grace period.

## Validation

```bash
python3 scripts/check-issue36-h2-source.py
bash -n scripts/verify-issue36-h2-shutdown.sh
mvn -B -Dtest=Issue36H2ShutdownTest test
docker compose build clamav-web-client
bash scripts/verify-issue36-h2-shutdown.sh clamav-web-client:2.1.0beta2-local
```

**Runtime verification is required on a host with Maven dependencies and Docker available.** This offline package cannot itself prove the acceptance criterion; the disposable smoke test must pass on the built image. It creates and deletes its own test volume and does not touch production data.
