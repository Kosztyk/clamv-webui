# Comprehensive full-source review — 2026-11-14

## Scope and limits

Baseline: the complete source after issues #36–#57, distributed as
`clamav-webui-v2.1.0beta2-issue57-upgrade-path-gate-full-source.zip`.
The review checked **all archive file contents by type** (text decoding, Python,
JSON, YAML, XML/SVG, HTML, JavaScript and shell parsing; Java 21 syntax parsing,
Go formatting), checked exact-file duplicates, and manually inspected critical
Java services, the Go/Java interface, Maven POM, Flyway upgrade probe and
fixtures, Dockerfile, GitHub CI/release workflows, and security/publishing scripts.
This is an extensive source-level inspection, **not proof that every branch of
runtime code or every dynamic feature works**. Binary assets were inventoried
and inspected for packaging/privacy rules, not reverse-engineered. Dead methods
cannot be conclusively identified by text search because Spring dependency
injection, Jackson reflection, Thymeleaf, scheduler and CLI entry points all
create valid dynamic references. No unverified methods have been deleted.

The runner has Java 21, Python 3, Node 22, and Go 1.23, but **no Maven, Docker,
or Go 1.25 toolchain** and no usable Maven Central network. Therefore Spring
integration tests, actual database migrations, external scanners, GHCR
signatures and Docker smoke checks were **not executed**. Real release
acceptance remains dependent on the GitHub runners.

## Corrected source defects

| Severity | Finding | Remedy and regression |
|---|---|---|
| **High** | Issue #53's `scripts/issue53-prepare-baseline.py` failed whenever an older base POM already contained `ci-quality`, making *every future Java PR* fail after #53 was merged. | Replace the existing baseline quality profile with the candidate's scanner configuration; reject duplicate profiles. Expanded negative tests. |
| **High** | `scripts/ci-detect-changes.py` did not select the complete build/security matrix after CI workflow or gate-selector changes. A workflow change could pass without exercising CodeQL or Docker. | Changes to both CI/release YAMLs, the selector, or gate evaluator now select **all** CI scopes; added negative scenario in `test-ci-gate.py`. |
| **Medium** | The release's `spotless:check` used `-Dspotless.ratchet.ref=HEAD`, a zero-diff reference that effectively disabled formatting checks. | Changed to `HEAD^` and added a policy test that rejects `HEAD`. **Limit:** this checks the tagged commit against its immediate parent. The required earlier `main` CI gate still enforces PR-delta formatting. A clean full-project formatter pass has not been established. |
| **Low** | `pom.xml` SCM publishing metadata still referred to the upstream author's repository instead of `Kosztyk/clamv-webui`. | Corrected Maven SCM connection/developerConnection/URL without changing author attribution; added regression assertion. |

`test-full-source-audit-regressions.py` now runs in the always-reporting CI
static job and in `scripts/release-gate.sh`. This protects the corrections
against recurrence.

## Remaining risks — intentionally *not* silently "fixed"

| Severity | Area | Observation / next action |
|---|---|---|
| **High (performance)** | `ScanExecutionService.init()` | The `LinkedBlockingQueue<>` is unbounded and `locallyQueued` retains one reference for each waiting job. Large backlogs can cause memory pressure. The persisted database reconciliation makes a bounded queue possible, but the commit callbacks must gracefully defer rejected submissions, and reconciliation/fairness/shutdown must be load-tested. Do not replace this queue with `CallerRunsPolicy` without evaluating API latency and transaction behavior. |
| **High (release assurance)** | Java/Go/Docker/DB integration | Syntax and policy checks cannot prove real Spring startup, H2/PostgreSQL upgrade, secrets decryption, OSV/Trivy, Cosign or the six-platform Go build. Require **live successful GitHub Actions jobs** before publishing. |
| **Medium (quality debt)** | Java formatting/static findings | Spotless uses incremental formatting; existing findings are baseline-compared and the JaCoCo line-coverage minimum is provisionally only 5%. Full automated refactoring or dead-code removal is unsafe without compiler/runtime analysis. Ratchet threshold and debt down as baseline reports become available. |
| **Medium (release maintenance)** | `config/upgrade-baseline.json` | The predecessor is deliberately pinned to `v2.1beta1`. Future releases must refresh this tag, SHA, fixture and version expectations after validating the replacement. It is not an automatic latest-release finder. |
| **Medium (release formatting)** | `release.yml` | The new `HEAD^` ratchet avoids the self-comparison but is not a substitute for an all-source formatting baseline. Ensure all Java files changed since the previous release have been checked by PR CI. |
| **Low** | Duplicate file inventory | Exact duplicates are limited to intentional shared/vendor SQL migration variants, historical fixtures, and vendored artwork/licensing. None was removed without a verified equivalence/use-site proof. |

## Security and local-network privacy

The package privacy audit succeeded. No project-specific LAN addresses from
previous homelab examples remain. The 8 RFC1918 literal occurrences in the
Java source are deliberate synthetic private-network **negative test fixtures**
in two test classes. These are never network endpoints used by deployed code.
The source archive includes no runtime database, `conf` keys, `.env`, certificates,
or production credentials. This audit does not erase sensitive data from
historical Git commits, tags or previously published ZIPs.

## Validation performed

- Full-file inventory and UTF-8 parse (1,365 baseline files; 1,345 text files;
  145,611 lines), Python AST, JSON/YAML, XML/SVG, HTML syntax and shell/JS parsing:
  **0 parse/syntax failures** at baseline.
- Java 21 compiler parser: **300 Java files, 0 syntax errors**.
- Go source `gofmt -l`: **no formatting differences**.
- Node configuration-observer regression: **5/5 passing**.
- All **42** applicable standalone repository check scripts: **42/42 passing**
  against the revised tree, including all relevant issue #36–#57 checks.
- `bash scripts/release-gate.sh --static-only`: output reached
  `RELEASE GATE COMPLETE`; compilation/browser tests were explicitly skipped
  as required by that mode; the containing tool invocation exceeded its
  execution wait limit, so only the captured completion log is asserted.
- Maven POM XML, workflow/Compose YAML and source-package privacy checks
  completed; full runtime validation remains pending.

## Release acceptance before touching production

1. Open a PR and require the single `CI / gate` result to pass on the **PR and
   on the final `main` commit**; inspect Java 21/25, Spotless, PMD, SpotBugs,
   JaCoCo, Go, Docker/Trivy, browser, API and database matrix results.
2. For a release tag, require the **full** `release-tests`, H2/PostgreSQL
   `upgrade-path`, Testcontainers, signed CLI, digest-pulled GHCR smoke and
   post-signature verification jobs. Do not publish merely on static success.
3. Load-test concurrent submissions and scan-queue recovery before deploying
   to high-traffic installations. A separate bounded-queue change needs a
   durable DB-retry behavior and regression coverage.
4. Back up `data/` and `conf/` and the external database before any migration.
   Follow `RELEASE.md` for explicit approval and rollback.
