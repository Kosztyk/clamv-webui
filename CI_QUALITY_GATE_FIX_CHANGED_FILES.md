# ClamAV Web UI v2.1.0beta2 — CI Java-quality selection fix

Baseline: `clamav-webui-v2.1.0beta2-publisher-tests-fixed-full-source.zip`.
This correction preserves previous scan-worker shutdown, native fallback, and GitHub publisher test repairs.

## Modified files

1. `scripts/ci-detect-changes.py`
   - Define `JAVA_QUALITY_CONTROLS` for the JaCoCo comparison policy and its structural/behavior tests.
   - Include these files in the existing `java` classification. Existing `java-tests` and `java-quality` jobs already consume this output, so the GitHub workflow is not modified.
   - The `java` branch also selects CodeQL and upgrade tests, as it does for Java source modifications.
2. `scripts/test-ci-gate.py`
   - Test each control file as a single-file change and together, verify expected selective scope, and ensure an unexpectedly skipped `java-quality` job blocks the required `gate` job.
   - Verify the real GitHub Actions `GITHUB_OUTPUT` signals from the CLI entry point.
   - Ensure unrelated issue #58 upgrade-predecessor script changes do not select Maven Java-quality jobs.
3. `scripts/test-full-source-audit-regressions.py`
   - Add a second static-CI regression guard against coverage control-plane bypasses.
4. `CHANGELOG.md`
   - Record the classifier correction in the Unreleased fixes.
5. `docs/releases/v2.1.0beta2.md`
   - Describe affected script paths, gates, unchanged configuration and local release status.

## Added files

- `CI_QUALITY_GATE_FIX_CHANGED_FILES.md` — this inventory.
- `CI_QUALITY_GATE_FIX_VALIDATION.md` — verification evidence and limitations.

## Unmodified by this patch

- `.github/workflows/ci.yml` and the `java-quality` Maven job (already correct when `java=true`).
- `scripts/issue58-coverage-ratchet.py` and its 5% minimum line-coverage policy.
- Spring/Java classes, application UI/API, ClamAV scanning logic, persistence/migrations, Compose, Dockerfile, and environment settings.

`SOURCE_MANIFEST.sha256` is regenerated to cover the full updated source archive.
