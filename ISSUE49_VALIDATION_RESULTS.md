# Issue #49 — validation report

Source baseline: issue #48 unified CI gate complete-source ZIP.

## Executed offline (13 November 2026)

- `scripts/check-issue49-release.py`: **PASS** (tag trigger, dry-run, job
  dependencies, read-only top-level permissions, full Maven/Docker gate,
  database integration matrix, Trivy blocking CRITICAL policy, EICAR smoke,
  release artifacts and checksums, draft publication and opt-in approval).
- `scripts/test-issue49-release.py`: **8/8 test methods PASS**. Includes
  successful and mismatched version tags, annotated tags, diverged/non-main
  commits, moved tags, missing/red/skipped CI gates, rejection of an older
  green CI run when newer CI failed, six-platform CLI manifest SHA and version
  verification, release-wide SHA256SUMS round-trip and post-build tampering.
- `scripts/test-issue49-policy.py`: **8/8 positive/adversarial scenarios PASS**.
  Changing Trivy to advisory, making release static-only, letting manual
  dispatch publish, removing protected publication, skipping remote ref
  verification or dropping generated notes is rejected.
- Existing issue #36–#46 source regression scripts: **PASS**.
- Existing issue #45 vendored-asset checksum and CI security gates: **PASS**.
- Issue #48 CI contract and conditional `gate` regression tests: **PASS**.
- `bash scripts/release-gate.sh --static-only`: **PASS** (does **not** run
  Maven, Docker or the browser suite). Updated release scripts have no shell
  syntax errors.
- GitHub Actions YAML parsed; Actions use 40-character SHA pins; no
  `beta-prerelease.yml` bypass remains.
- `tools/audit-source-package.py` source inspection: **PASS**.
- ZIP integrity, complete-source SHA256 manifest and the existing
  `publish-clamav-webui.sh ... --check` preflight: **PASS**.

## Not executed / acceptance pending on GitHub

Maven and Docker are not installed in the authoring environment. Real
`mvn clean verify`, Testcontainers (all three vendors), Docker image build,
EICAR and HEALTHCHECK smoke, live Trivy database scans, cross-compiled real Go
binaries and the GitHub Release draft/approval flows require a GitHub runner.
The tests above use **synthetic** local Go binary and JAR fixtures to validate
asset/checksum handling, not to claim real binaries were built. The repository
has not been pushed and no release, tag, approval, or branch protection has
been modified by this work.

## Manual verification

See `docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md`. Push the updated repository,
wait for the green `gate` **on main**, and tag the matching Maven version.
With the current POM that is `v2.1.0-beta2`, not `v2.1.0`. Confirm that
red and non-main tags stop in `verify-tag` without a draft and that a green
matching tag creates a draft with the JAR, six executables, source ZIP and
`SHA256SUMS`. Before enabling automatic public publication, configure the
protected `release` environment with required reviewers and explicitly set
`RELEASE_PUBLICATION_ENABLED=true`.
