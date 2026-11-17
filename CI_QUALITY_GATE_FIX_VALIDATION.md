# ClamAV Web UI v2.1.0beta2 — Java-quality selection fix validation

## Reproduction before the patch

```
python3 scripts/ci-detect-changes.py --files scripts/issue58-coverage-ratchet.py scripts/test-issue58-hardening.py
```

Previously returned `java=false`, `codeql=false`, `upgrade=true`, incorrectly skipping `java-tests` and `java-quality`.

## Verified selection after the patch

- `scripts/issue58-coverage-ratchet.py` alone: `java=true`, `static=true`, `codeql=true`, `upgrade=true`, `security=true`; unrelated `db`, `cli`, `docker`, `browser`, `api` remain `false`.
- `scripts/check-issue58-hardening.py` alone: same result.
- `scripts/test-issue58-hardening.py` alone: same result.
- Combination of all three: same result.
- `scripts/issue58-resolve-predecessor.py` alone: upgrade job selected; Java-quality remains skipped as intended.
- CLI entry point with `GITHUB_OUTPUT` writes `java=true` for coverage-policy-only changes.
- Skipping the required `java-quality` job while its scope is active is rejected by the CI merge gate.

## Offline tests

The following were executed successfully after the edit:

```
python3 scripts/check-ci-workflow.py
python3 scripts/test-ci-gate.py
python3 scripts/check-issue53-java-quality.py
python3 scripts/test-issue53-java-quality.py
python3 scripts/check-issue58-hardening.py
python3 scripts/test-issue58-hardening.py
python3 scripts/test-full-source-audit-regressions.py
python3 scripts/test-issue52-security-gates.py
python3 scripts/test-issue45-security-gate.py
```

## Packaging and integration verification

- ZIP CRC and source SHA-256 manifest verified after repackaging.
- No archived file changes outside the expected five modified files, two new report files and generated manifest.
- The real publisher offline `--check` validated the resulting full-source ZIP (1,388 entries) with no authentication or network access.
- The publisher integration regression suite was re-run: ten cases completed successfully; the run exceeded the local execution limit during `test_rejected_branch_push_exits_with_failure_instead_of_claiming_success`. The entire suite is **not** reported as passed for this patch.
- Maven is not installed in this sandbox, so the full `mvn -B -ntp clean verify` and live GitHub Actions matrix have **not** been executed. The existing CI workflow does run the Maven quality comparison when the selector sets `java=true`.
- No GitHub publication or release was created.
