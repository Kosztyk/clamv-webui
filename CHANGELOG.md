# Changelog

All notable **user-visible** changes to ClamAV Web UI are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
releases use [Semantic Versioning](https://semver.org/). The project's
canonical build/version value comes from `pom.xml`. A version is **released**
only after its Git tag, verified artifacts and GitHub release have been published.
The entries below describe released source when a dated version heading is present.

## [Unreleased]

_No unreleased user-visible changes yet._

## [2.1.0] - 2026-11-20

- Direct-HTTP setup/recovery now persists only `server.servlet.session.cookie.secure=false` when explicitly enabled on a trusted HTTP network; forwarded-header trust is not silently changed.
- PostgreSQL startup now detects the legacy `processed_files.idx_processed_path` UNIQUE constraint shape and safely normalizes it before Flyway V9, while leaving the V9 migration checksum unchanged.
- Docker Hub OpenVEX attachment now uses Docker Scout's normal discoverable attestation backend with the short Hub repository reference and publisher-author verification.
- Fixed the ClamAV 1.4.7 hardened-runtime validation so the final Web UI image requires the installed `clamav-libs>=1.4.7-r0` package while explicitly rejecting the `clamav` meta-package. The native `clamdtop` binary is copied from the build stage, so requiring the meta-package made otherwise-correct images fail during Docker build.

### Quarantine identity and recovery hardening (Flyway V11)

- Reserve all source detections atomically in the quarantine journal before moving the first file. Rejected paths receive a durable `FAILED` outcome; permission repair can no longer erase other files' quarantine-failure warnings.
- Commit SHA-256/size evidence per authorized source and revalidate it before and after movement, including restart recovery. Missing evidence, replaced paths and mismatched destinations fail closed and retain a positive malware verdict.
- Persist the scan-time quarantine-enabled flag and add V11 migrations for H2, PostgreSQL, MySQL and MariaDB. Extend regression coverage for replacement files, incomplete journals, rejected paths, transaction failures and settings changes. Runtime/database validation remains pending.


### Added

- Fail-closed prior-release-to-candidate Flyway upgrade tests for persisted H2 and PostgreSQL databases, Basic/bearer authentication, encrypted LDAP/OIDC settings and user/job/token history.

- Required, always-reporting unified GitHub Actions `gate` for Java, CLI,
  database migrations, browser, API contract, and Docker smoke tests.
- Maven quality checks, coverage floor, Checkstyle-equivalent/PMD and
  SpotBugs/FindSecBugs baselining, Java 21/25 test matrix.
- Gitleaks, OSV-Scanner, CodeQL (Java/JS/Go), Trivy, actionlint, hadolint,
  Dependabot and vendored JavaScript dependency inventory.
- Verified tag-triggered draft releases, signed multiarch GHCR images,
  SBOM/provenance attestations, reproducible and signed CLI executables,
  immutable-digest EICAR and non-root smoke tests.
- OpenAPI Spectral linting, `oasdiff` breaking-change gate, MockMvc API
  contract tests and required CLI-to-built-server integration.
- Release/incident runbook, private vulnerability disclosure guidance, code
  ownership rules, PR checklist, automated release-note categories, and
  fail-closed source privacy audit for internal IPv4 addresses.

### Changed

- Source-file PATH/WATCH quarantine now validates every daemon-reported filename against the current configured allowed roots AND the original scan target. Paths outside either scope, symlink aliases, relative/traversal spellings and stale targets are rejected; the VIRUS_FOUND evidence is retained with a quarantine warning. Upload quarantine remains separate.
- Scan completion methods now delegate to an independently proxied `ScanJobFinalizationService` with short Spring transactions; completion metrics and audit recording occur only after successful commit, not on rolled-back updates. No database migration or configuration changes.

- POM is the canonical application version for resources, CLI, OpenAPI and
  release tags; development Compose builds use a version-neutral local tag.
- Service-layer cleanup removes duplicate scan completion methods and routes
  both UI/API scan creation through transactional batch metadata commits.
- LDAP directory identity remains stable across URL/base DN changes after
  the historical issuer is pinned on the first upgraded startup.
- Documented examples use RFC 5737 TEST-NET addresses instead of an operator's
  real homelab IPs.

### Security

- Upgraded the bundled native ClamAV monitor/library package and the default pinned ClamD service from 1.4.6 to 1.4.7, the upstream security patch release fixing CVE-2026-76411, CVE-2026-76414, CVE-2026-76415, CVE-2026-76416, CVE-2026-76417, CVE-2026-76421, CVE-2026-76422, CVE-2026-76430, CVE-2026-76515, and CVE-2026-76516. Docker/runtime smoke checks require installed `clamav-libs>=1.4.7-r0` and explicitly reject installing the `clamav` meta-package in the hardened Web UI runtime; native `clamdtop` is copied separately.
- Release VEX generation now uses Docker Scout 1.29-compatible qualified Alpine package PURLs for both x86_64 and aarch64 so the `CVE-2026-107891` `not_affected` statement is actually suppressed rather than merely displayed.
- Release images now generate a digest-scoped OpenVEX statement for `CVE-2026-107891`, validate that it names exactly Alpine `musl 1.2.6-r3`, attach it through the existing pushed registry tag with Docker Scout while retaining the exact digest inside the VEX product, and include the exact VEX document in checksummed release evidence. A registry-agnostic helper can attach the same release-scoped statement to the separately published Docker Hub image.
- Container runtime now builds and installs `libfontenc 1.1.10-r0` from the Alpine recipe to remediate `CVE-2026-109908` while retaining Alpine 3.24's distribution-patched `musl 1.2.6-r3` for `CVE-2026-107891`. Runtime and Docker smoke assertions enforce the fixed `libfontenc` floor.
- HTTP REST scanner credential safeguards, safe audit logging, webhook HMAC,
  server-side redaction, session revocation, runtime configuration protection,
  ClamdTop process isolation and stricter authorization/validation parity.
- Supply-chain release artifacts fail closed on critical defects, including
  signature, checksum, image scan and provenance verification errors.

### Fixed

- Corrected references to a historical, unshipped server-package validation JSON; documentation now links to validation reports actually included in the source archive. A CI regression guard checks these links and confirms that the scan-type quarantine authorization and absence of obsolete suppression remain intact.
- Directory watching is now incremental and bounded per scheduler tick: up to 256 filesystem entries and 128 indexed fingerprint lookups per batch, with resumable depth-first directory cursors and fair progress across watched roots. The historical full-prefix marker preload is removed; persistence and retry semantics are preserved.
- CI change detection now treats the JaCoCo coverage-ratchet script and its structural/regression tests as Java-quality control-plane changes, ensuring Maven quality and coverage comparison jobs cannot be silently skipped on coverage-policy-only PRs.
- GitHub source-publisher regression fixtures preserve the historical v2.1.0beta2 ZIP-root regression case and required source files, include the real source-privacy auditor, and assert the current PR description. A new test ensures legacy ZIP root layouts remain rejected before authentication.
- Native upload path scans now select their transport before finalizing the result. Only native ClamD I/O failures trigger the configured INSTREAM fallback; database, quarantine and notification failures never cause a duplicate scan. Added targeted regression tests.
- Coordinated scan-worker shutdown during Spring context stop: durable queued jobs are preserved, active scans receive a grace period, forced interruption does not persist a false terminal error, and interrupted jobs are recovered on next single-instance startup. Added concurrency/restart regression tests and configurable shutdown budgets.
- H2 shutdown/legacy database-close behavior and Flyway migration compatibility.
- CSV formula-injection handling, bounded session/scan/HTTP resources and
  response/error-path parity across browser and API endpoints.

### Compatibility and migration notes

- Existing installations should back up their persistent DB and `conf/` volume
  before upgrading. Flyway baseline-on-migrate is for existing non-Flyway
  schemas, not a blanket setting for fresh databases. See `RELEASE.md` and
  `docs/COMPLETE_USER_GUIDE.md`.
- Unintended breaking `/api/v1` changes are blocked by compatibility checks.

## [2.0.0]

- Historical GitHub release `V2.0.0`; consult its original release page for
  the exact historical asset set and publication date. This entry does not
  retroactively claim the unreleased changes above were part of v2.0.0.

## [1.0.0]

- Historical GitHub release `V1.0.0`. Earlier history is preserved in Git.

[Unreleased]: https://github.com/Kosztyk/clamv-webui/compare/V2.0.0...main
[2.0.0]: https://github.com/Kosztyk/clamv-webui/releases/tag/V2.0.0
[1.0.0]: https://github.com/Kosztyk/clamv-webui/releases/tag/V1.0.0

### Post-audit hardening (2026-11-14)

- Bounded scan dispatch capacity with persistent-job retry and overload metric.
- Measured JaCoCo coverage non-regression relative to the immutable PR/push base.
- Latest published ancestor release selection, commit verification and dynamic Flyway history in upgrade tests.
- Opt-in complete Java formatting modernization workflow; no unsupported claim of completed legacy cleanup.