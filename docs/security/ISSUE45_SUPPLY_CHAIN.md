# Issue #45: supply-chain and CI controls

See [asset inventory](../../src/main/resources/static/js/VENDORED.md).

- **Dependabot:** `.github/dependabot.yml` schedules Maven, Docker and GitHub Actions updates weekly. PRs require review; schedules alone do not guarantee an immediate PR. Vendored JS is outside these ecosystems.
- **Immutable actions:** Every workflow `uses:` reference is a full, verified GitHub commit SHA. Dependabot updates SHA-pinned references in subsequent PRs.
- **CVE gate:** the `docker-smoke` job of `.github/workflows/ci.yml` builds the production image locally and runs `trivy-action@<verified-sha>` with `severity: CRITICAL`, `vuln-type: os,library`, and `exit-code: '1'`. It includes unfixed CVEs. No `continue-on-error`, no empty ignore file. Trivy DB refresh means scan results can evolve while base digests remain fixed.
- **SAST:** CodeQL Java/Kotlin (manual Maven build). `security-events: write` is limited to the CodeQL job.
- **Reproducibility:** Main Java images and ClamAV scanner are digest-pinned. The default Compose scanner now directly resolves to the verified ClamAV 1.4.7 digest when .env is absent; overrides should always be pinned. CI no longer uses `docker build --pull`. Alpine package repositories and vulnerability database updates still prevent bit-for-bit repeatability.
- **Verification:** Run `python3 scripts/check-issue45-supply-chain.py`, `mvn -B verify`, and monitor the Trivy + CodeQL workflow results. Docker and Maven runtime execution was unavailable in the archive-build environment; do not treat source checks as equivalent.

The tag-gated `release.yml` runs the complete gate, database matrix, Docker smoke and blocking CRITICAL-CVE scan before creating a **draft** GitHub release. Publication requires separately configured protected-environment approval.
