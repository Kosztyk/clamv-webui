# Beta2 cumulative source — Audit finding #8 changed files

**Baseline:** `clamav-webui-v2.1.0beta2-quarantine-transaction-fixed-full-source.zip` (fixes #1–#7 retained).

## Modified

- `CHANGELOG.md`
- `SERVER_DEPLOYMENT.md`
- `docs/CONFIGURATION_FIELD_AUDIT.md`
- `docs/FIELD_VALIDATION.md`
- `docs/releases/v2.1.0beta2.md`
- `scripts/test-full-source-audit-regressions.py`

## Added

- `AUDIT_FIX8_CHANGED_FILES.md` — this inventory.
- `AUDIT_FIX8_VALIDATION.md` — validation evidence and limitations.

## Regenerated

- `SOURCE_MANIFEST.sha256` — digest list for every source-file member other than the manifest itself.

## Explanation

- The broken Markdown link to `SERVER_PACKAGE_VALIDATION.json` was replaced with links to reports actually included in this ZIP. Two related unlinked references were rewritten so operators are not misled into expecting that generated report. The original audit's historical notes are retained as historical, not upgraded to claims of new test execution.
- `ScanJobType type` in `ScanExecutionService.handleResult()` is already used as part of fix #6 to authorize source-file quarantine specifically for PATH/WATCH jobs. Removing it would undo a security guarantee, so Java is deliberately unchanged.
- The old `@SuppressWarnings("unchecked")` has already been removed from that service. Suppressions in unrelated Java classes were not modified.
- The existing static CI regression script now checks included Markdown links and guards both scan findings against reintroduction.

**No application behavior, runtime property, schema, Docker configuration or GitHub release was changed.**
