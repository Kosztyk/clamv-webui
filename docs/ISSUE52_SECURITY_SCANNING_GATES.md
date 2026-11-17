# Issue #52 — Security scanning with a single required CI gate

## Scope

Issue #52 extends the unified required `gate` introduced in issue #48 instead of
adding path-filtered, independently required workflows. `.github/workflows/ci.yml`
triggers on every pull request, every push to `main`, manual dispatch, and Monday
at 04:17 UTC. The scheduled scan performs a full sweep even without changed files.
**Only `gate` should be configured as a required GitHub status check.**

| Gate job | Engine / version | Behavior on a regular PR | Failure policy |
|---|---|---|---|
| `secrets` | Gitleaks 8.30.1 (Go module-pinned) | Every PR; checked-out tree and PR commits | Blocks on matches, errors or failed generated-token self-test |
| `workflow-lint` | actionlint 1.7.12, hadolint-action v3.3.0 by SHA | Every PR | actionlint errors and hadolint **error-level** findings block |
| `dependency-vulns` | OSV-Scanner v2.2.1 (Go module-pinned) | Every non-doc-only PR, main push and weekly run | Any nonsuppressed known vulnerability blocks, including LOW/MEDIUM (stricter than requested HIGH/CRITICAL) |
| `filesystem-vulns` | Trivy v0.70.0 via SHA-pinned action | Every non-doc-only PR, main push and weekly run | Fixable HIGH/CRITICAL OS/library vulnerabilities block |
| `docker-smoke` | Trivy v0.70.0 | Docker-impacting PRs, main changes and weekly run | CRITICAL including unfixed **and** fixable HIGH/CRITICAL block; Docker EICAR/non-root smoke preserved |
| `codeql-*` | GitHub CodeQL v3.30.0 SHA-pinned | Source changes, and weekly full run | Java, JavaScript/TypeScript and Go analysis must complete; failures block `gate` |

Docs-only PRs continue to execute `static`, `secrets`, `workflow-lint`,
`changes` and `gate`, while skipping heavyweight scans after explicit change
detection. A failed or cancelled scanner **always fails** the gate even if its
scope is normally filtered; an unexpectedly skipped required scanner also
fails. The CI root `permissions` remains `contents: read`; CodeQL receives
`security-events: write` only within its dedicated job. All GitHub marketplace
Action references are full reviewed commit SHAs. Maven/Go caches are enabled.

## Dependency suppression policy

`osv-scanner.toml` defaults to **zero exclusions**. OSV runs recursively over
the application and `cli/` dependencies. Exceptions are possible only for a
specific vulnerability ID and must include a descriptive, reviewed `reason`
(20+ characters) and an unexpired `effectiveUntil` date. Broad package ignores
are forbidden by `scripts/check-issue52-security-gates.py`. Every exception
should link its risk assessment and be revisited before expiry. Never add an
exception merely to make CI green; update vulnerable dependencies instead.

Dependabot checks the root Maven project, Docker references, GitHub Actions and
`cli/` Go modules each week. Vendored browser assets are intentionally *not*
under npm; `src/main/resources/static/js/VENDORED.md` retains the self-hosted
jQuery 3.7.1, Bootstrap and xterm hashes and human-managed update process.
Hashes are integrity evidence, not vulnerability clearance. Review vendored
library advisories separately.

## Release protection

The tag-triggered `release.yml` still refuses any tag not referencing a `main`
commit with a green `gate`. It additionally repeats Trivy filesystem and
candidate image scans at release time, checking fixable HIGH/CRITICAL findings,
while retaining the older CRITICAL-unfixed scan. The issue #51 multi-platform
GHCR flow then scans the actual pushed digest and performs keyless Cosign
signing, SBOM/provenance attestation, signature verification, and post-push
EICAR/non-root smoke tests against the digest before promotion.

## How to verify the acceptance criteria on GitHub

1. Push this complete source on a branch and open a PR. In **Actions → CI**, the
   selected security jobs should appear individually and contribute to `gate`.
2. For a secret-scanner acceptance test, use an **isolated throwaway test branch**
   with an obviously fake `ghp_`-format value (never a genuine token). Confirm
   `secrets` and `gate` fail, then delete the test branch. CI also generates a
   nonfunctional secret pattern in a temporary directory and verifies Gitleaks
   rejects it on *every* run.
3. For the dependency scanner acceptance test, on a separate throwaway branch,
   add a disposable Maven dependency with a documented known CVE (e.g. the
   historical `org.apache.logging.log4j:log4j-core:2.14.1`) and confirm OSV
   reports the advisory and `dependency-vulns` / `gate` fail. Never ship the
   test dependency. Scanner results depend on the current OSV advisory data.
4. Try a docs-only PR: heavyweight conditional checks may skip; the consistent
   `gate` must still report. A migration PR must also trigger the 3-database
   matrix; the new security jobs do not replace existing CI coverage.
5. In **Settings → Rules → Rulesets**, require only `gate` on `main`.

## Offline tests, limitations and troubleshooting

```bash
python3 -m pip install PyYAML==6.0.2
python3 scripts/check-ci-workflow.py
python3 scripts/test-ci-gate.py
python3 scripts/check-issue52-security-gates.py
python3 scripts/test-issue52-security-gates.py
python3 scripts/check-issue45-supply-chain.py
```

These tests verify wiring, pins, suppression governance and adversarial gate
semantics **without executing CVE databases or scanners**. They do not certify
that Maven, Docker, CodeQL, Trivy, Gitleaks, OSV, or the GitHub runners pass.
Actual scanner execution (including false-positive triage) happens only when
CI is pushed. Do not suppress scanner failures using `continue-on-error`,
`exit-code: 0` or permissive ignore files. CI intentionally fails closed.

**Note:** Installing Go scanners in CI uses version-pinned Go modules, not
mutable third-party container tags. The tools' transitive dependencies are
resolved by Go's module integrity mechanism. Hadolint uses an Action pinned
by full Git commit SHA; its `failure-threshold: error` blocks lint errors while
warnings remain informational to avoid changing the application's existing
Dockerfile package-installation semantics without a separate review.
