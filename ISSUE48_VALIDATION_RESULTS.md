# Issue #48 — CI gate validation (local source candidate)

Date: 2026-11-13. Baseline is the **issue #46** full-source archive.

## Completed locally

| Test | Result |
| --- | --- |
| `python3 scripts/check-ci-workflow.py` | PASS: YAML structure, trigger coverage, 9 job IDs, branch-protection gate, matrix, caching, pinned actions, permissions, timeouts, artifacts |
| `python3 scripts/test-ci-gate.py` | PASS: docs-only, DB migration, CLI-only and full dispatch, fail/cancel/unexpected-skip/missing/unknown-output cases, real Git diff, rename/deletion detection |
| `python3 scripts/check-issue45-supply-chain.py` | PASS: immutable action references, vendored JS hashes, image digests, Dependabot and Trivy/CodeQL blocking policy |
| `python3 scripts/test-issue45-security-gate.py` | PASS: rejects altered vendor asset, mutable action ref and disabled critical CVE gate |
| Issue #36–#46 offline issue scripts | PASS: all 12 listed source checks including #38 Configuration Center |
| Renamed static inventories | PASS: static, database and monitoring source inventories |
| `bash scripts/release-gate.sh --static-only` | PASS: browser/Maven intentionally skipped in static mode |
| `bash scripts/clamav-release-gate.sh --static-only` | PASS: browser/Maven intentionally skipped in static mode |
| YAML parsing, Bash `-n`, Python syntax, source security audit | PASS |
| `python3 scripts/check-i18n.py` with local Chromium prior to static-only switch | PASS: browser locale fixture |

## Not executed / acceptance pending

- **Actual GitHub `CI / gate` statuses** on a docs-only PR and a migration PR. Without pushing the archive there is no Actions run or branch protection status to inspect.
- **Maven/Spring tests**, including all selected Java suites and the database Testcontainers matrix. Maven is unavailable on the local archive-build host.
- **Docker build, Trivy CRITICAL-CVE scan, actual HEALTHCHECK, end-to-end EICAR, baseline image size comparison**, and container-log uploads. Docker is unavailable here.
- **Go 1.25 CLI matrix** across Linux/macOS/Windows, `go vet` and six executable cross-builds. Local Go is 1.23.2; CI supplies 1.25 using setup-go.
- **Playwright trace uploads** from GitHub Actions. Trace recording is wired into native terminal tests; artifact creation must be verified in CI.

## Branch protection acceptance procedure

1. Push this archive as a PR branch. Confirm **`CI / gate`** is reported and succeeds (or fails meaningfully) on all PRs.
2. Open a docs-only PR: `changes`, `static`, and `gate` should succeed; heavy jobs should be skipped and gate should be successful.
3. Open a test migration PR: all three Testcontainers vendors, Java, container + Trivy, CodeQL must be selected. Force a database test failure in a disposable PR to confirm `gate` fails.
4. Require **only `gate` as the repository's CI check** under the `main` branch ruleset; remove the old issue-specific checks. Do not disable separately required reviews.

Only offline preflight checks can be marked complete before those GitHub runs; no application build or CI status has been claimed to have passed.
