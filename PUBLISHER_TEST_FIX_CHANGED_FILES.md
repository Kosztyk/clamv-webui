# Publisher regression fixture repair — beta2 local source candidate

Baseline archive: `clamav-webui-v2.1.0beta2-native-fallback-fixed-full-source.zip`. Previous scan-worker shutdown lifecycle and native-upload fallback fixes remain unchanged.

## Existing files updated

1. `scripts/test-publish-clamav-webui.py`
   - Centralize the expected source ZIP entry root as `ARCHIVE_ROOT = "clamav-webui-v2.1.0beta2"` and use it in all test archives, duplicates, and symbolic-link fixtures.
   - Include `.gitignore`, the required sample configuration and the actual `tools/audit-source-package.py` in the synthetic source fixture. This matches the real publisher's required-file checks and exercises its preauthentication privacy audit.
   - Reuse the standard archive builder for the private-address regression test.
   - Update the protected-default-branch PR-body assertion from obsolete `107-field audit` text to `docs/QUEUE_COVERAGE_UPGRADE_HARDENING.md` as used by the current publisher.
   - Add a negative test verifying that legacy `clamv-webui-v2.1.0/` ZIP roots are still rejected before authentication.
2. `CHANGELOG.md` — document the publisher fixture repair.
3. `docs/releases/v2.1.0beta2.md` — note scope, coverage and release status.

## New documentation

- `PUBLISHER_TEST_FIX_CHANGED_FILES.md` — this inventory.
- `PUBLISHER_TEST_FIX_VALIDATION.md` — independent test and package verification results.

## Intentionally not changed

- `publish-clamav-webui.sh`: the **publisher itself was not defective** and its fail-closed ZIP root/manifest validation remains intact.
- Docker, Spring/Java, API/UI, ClamAV scan logic, database schema/migrations, and environment settings.

The source manifest is regenerated, but otherwise all packaged files remain byte-identical to the previous archive.
