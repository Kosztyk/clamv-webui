# Issue #48 — One always-reporting GitHub CI required check

## Why

GitHub does not produce a workflow check when a workflow is skipped by
`on.pull_request.paths` or `on.push.paths`. Requiring one of those checks
causes an unrelated PR to be permanently **Expected / Waiting**. The previous
full-source archive also contained multiple issue-specific checks; the three
historic `phase0-ci.yml`, `phase2-db-integration.yml`, and `clamctl.yml` files
referenced in old documentation were no longer present. This patch replaces
all issue-specific PR workflows with one `.github/workflows/ci.yml`.

## Required status check

Only require **`gate`** in GitHub's branch protection/ruleset for `main`.
It is a job in the workflow named `CI` with `if: ${{ always() }}` and depends
on **all** other jobs. It fails if:

- change detection fails, supplies no output, or Git history is insufficient;
- a job selected by change detection fails, is cancelled, or is unexpectedly
  skipped;
- any job runs and fails, even if the change classifier would normally skip it;
- a declared prerequisite job is missing from the `needs` data.

The gate treats `skipped` as successful **only when the classifier expressly
marked that heavy job unnecessary**. A docs-only PR gets the same required
`gate` status as a change to a database migration. Every pull request gets
`changes`, `static`, and `gate` because the workflow itself is unfiltered.

### Job selection

| Files changed | Selected jobs (in addition to `changes`, `static`, `gate`) |
| --- | --- |
| Documentation (`docs/**`, README, Markdown only) | None |
| `src/main/resources/db/migration/**` | Java tests, PostgreSQL/MySQL/MariaDB database matrix, Docker smoke, CodeQL |
| Other Java sources or `pom.xml` | Java tests, Docker smoke, CodeQL (and DB matrix for POM/DB sources) |
| `cli/**` Go code | Linux/macOS/Windows Go tests and vet, six-target cross-build on Linux |
| `Dockerfile*`, Compose files, entrypoint | Container smoke + Trivy critical vulnerability gate |
| Browser assets/templates | Java tests, Docker smoke, browser/Playwright checks and CodeQL |
| Manual workflow dispatch or empty diff | All jobs, fail closed |

The classifier is `scripts/ci-detect-changes.py`, using the checked-out
Git history instead of the pull-request API. PRs compare the recorded base
commit with the merge ref; pushes compare the pre-push commit with HEAD.
If the Git base cannot be retrieved, `changes` fails and `gate` rejects it.

### Existing protection retained

- All `uses:` references pin immutable 40-character GitHub commit SHAs.
- Root permissions are `contents: read`; only CodeQL adds
  `security-events: write`.
- CI uses concurrency group `ci-${{ github.ref }}` and cancels superseded runs.
- Every job has an explicit timeout.
- Maven and Go modules are cached by the respective setup actions.
- The Docker job runs an actual image HEALTHCHECK, H2 lock cleanup, Compose
  isolation, EICAR end-to-end scan, and Trivy with `severity: CRITICAL` and
  `exit-code: '1'` before a successful gate result is possible.
- The `codeql` job analyzes Java/Kotlin as a manual build.
- Surefire/Failsafe XML, CI-only container logs, CLI executables, and Playwright
  traces are uploaded as short-retention build artifacts.

## Migrating branch protection

1. Push the new source to a branch and open a PR. Verify `CI / gate` reports.
2. In GitHub **Settings → Rules → Rulesets** (or **Settings → Branches**),
   edit the rule protecting `main`.
3. Choose `gate` as the **only** required CI status. Remove old checks such as
   `native-clamdtop-security`, `container-smoke`, and phase-specific names.
4. Test a docs-only PR: heavy jobs should be `skipped`, and `gate` should pass.
5. Test a migration change: all database matrix legs must run, and any failure
   must make `gate` fail.

Keep review and security policies you already require; “gate only” means one
required **CI** check, not disabling required reviews or other protections.
The separate `release.yml` runs on version tags (plus manual dry-run) and is not
suitable as a required PR check. It still runs verification and Trivy.

## Local validation

```bash
python3 scripts/check-ci-workflow.py
python3 scripts/test-ci-gate.py
python3 scripts/check-issue45-supply-chain.py
bash scripts/release-gate.sh --static-only
```

Run `mvn -B -ntp verify`, `mvn -B -ntp -Pdb-integration verify`, the Go
suite, Docker smoke and browser tests in GitHub Actions or a host with those
tools. Offline scripts cannot prove that GitHub has actually posted a check.
