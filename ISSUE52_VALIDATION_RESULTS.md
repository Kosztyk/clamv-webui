# Issue #52 — Validation report

## Performed offline in the source workspace (PASS)

- GitHub issue #52 read using the GitHub connector.
- `.github/workflows/ci.yml` and `release.yml`: parsed as YAML with
  PyYAML BaseLoader; Dependabot and GHCR Compose YAML also parsed.
- `python3 scripts/check-ci-workflow.py`: unified always-reporting job selection,
  all scanner needs and the Java/JS/Go matrix accepted.
- `python3 scripts/test-ci-gate.py`: documentation-only, DB migration, CLI and
  fail-closed result paths passed.
- `python3 scripts/check-issue52-security-gates.py`: the security policies,
  tool versions, update cadence, documented exception governance, SHA-pinned
  Actions, release repeat scans and Cosign/EICAR preservation passed.
- `python3 scripts/test-issue52-security-gates.py`: negative tests rejected
  failed/cancelled/skipped scanner jobs, disabled vulnerability blocking, a
  CRITICAL-only policy, missing CodeQL language, disabled secrets/dependency/
  workflow linters, mutable Actions, removed Go Dependabot updates, expired
  suppressions and suppressions with no adequate explanation.
- Original issue #36–#46 source check scripts passed separately, including the
  issue #45 supply-chain guard. Issue #49 and #51 workflow/policy tests and
  issue #50 version check also passed separately.
- `python3 tools/audit-source-package.py .`: runtime-file/credential package
  audit passed before archiving.
- Python scripts compile and modified shell scripts pass `bash -n`.

## Not performed / must be checked on GitHub (PENDING)

- **Real Gitleaks binary run** and generated fake-token detection (the CI
  script enforces this only when the GitHub runner installs Gitleaks).
- OSV-Scanner network-backed vulnerability audit, including a test-branch
  vulnerable Maven dependency detection.
- Trivy filesystem and image scans with an up-to-date vulnerability DB.
- CodeQL Java, JavaScript and Go analysis and upload to the Security tab.
- actionlint, hadolint, full Maven tests, DB Testcontainers, Docker smoke,
  GHCR signature and SBOM/provenance verification.
- Live pull-request always-reporting `gate` behavior and the Dependabot PR
  generation cycle.

The **source guards passed**, not the live vulnerability acceptance criteria.
The CI pipeline is designed to **fail** if scanners find real vulnerabilities
or the scanner binaries cannot be installed. That is intentional and should be
resolved after reviewing the findings rather than weakening the checks.

## Offline commands

```bash
python3 -m pip install PyYAML==6.0.2
python3 scripts/check-ci-workflow.py
python3 scripts/test-ci-gate.py
python3 scripts/check-issue52-security-gates.py
python3 scripts/test-issue52-security-gates.py
python3 scripts/check-issue45-supply-chain.py
python3 scripts/check-issue51-container-release.py
python3 scripts/check-versions.py
python3 tools/audit-source-package.py .
```

## GitHub acceptance procedure

Push the complete source as a PR. Validate each scanner in the `CI` workflow;
create separate throwaway test branches with a **fake** GitHub token and an
**intentionally vulnerable** test-only dependency, and verify that their jobs
and the single `gate` fail. Confirm docs-only PRs skip heavy scanners and
migrations select all three database vendors. Do not merge test fixtures.
Then verify `gate` passes on `main`, with security checks contributing to the
result, before creating a tag for issue #51's signed GHCR release process.
