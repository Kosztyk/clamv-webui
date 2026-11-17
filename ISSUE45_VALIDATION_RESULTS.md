# Issue #45 validation report — 2026-11-13

## Executed successfully (offline)

- Verified official complete jQuery v3.7.1 JS bytes against independent CDN SHA-256 `fc9a93dd241f6b045cbff0481cf4e1901becd0e12fb45166a8f17f95823f0b1a` (87,533 bytes). No slim build or CDN runtime dependency was introduced.
- All original issue #34 and #36–#44 offline check scripts passed. They are source/isolated-behavior checks, **not** the full Maven/Spring suite.
- `python3 scripts/check-issue45-supply-chain.py` passed: immutable GitHub Actions references, manifest integrity, three weekly Dependabot ecosystems, pinned scanner/java images, Trivy CRITICAL blocker and CodeQL SAST.
- `python3 scripts/test-issue45-security-gate.py` passed five scenarios: valid baseline, tampered vendor JS rejected, Trivy exit-code downgraded to zero rejected, action SHA downgraded to mutable tag rejected, restored baseline passed.
- All eight GitHub Actions workflow YAML files, `.github/dependabot.yml` and default Compose YAML parsed successfully with PyYAML.
- Node v22 `--check` passed for locally served jQuery 3.7.1 and native ClamdTop JavaScript.
- `tools/audit-source-package.py` passed for the complete source tree and archive; no prohibited runtime state or secrets.
- Complete ZIP integrity, SHA-256 source manifest and publishing preflight validated.

## Not executed here

- `mvn -B verify`: Maven unavailable.
- Docker image build and `docker inspect`: Docker unavailable.
- Trivy live image scan / CodeQL upload: requires GitHub Actions run after changes are pushed.
- Dependabot opening Maven, Docker and GitHub Actions pull requests: depends on pushing the configuration, an available update and GitHub scheduled execution. Adding a valid schedule does not produce PRs immediately.
- Browser-based UI regression after changing jQuery and updating the legacy xterm bundle: no live browser/application deployment.

## Residual risks / non-goals

- The preexisting xterm Browserify bundle lacks recorded upstream version and build provenance; this archive records its immutable checksum and identifies the version as **unverified**. Replacing the terminal renderer requires compatibility testing and is not silently performed.
- Ubuntu `apt` package repositories, Maven dependencies without a complete lockfile, and Trivy's vulnerability DB are moving inputs; digest-pinned Docker `FROM` references are not a bit-for-bit reproducibility guarantee.
- Trivy deliberately fails on **any currently known CRITICAL OS/library CVE**, including unfixed vulnerabilities. A genuine critical finding may require an upstream base-image/package update rather than suppressing the CI job.

## Verification on GitHub

1. Open a pull request with this full-source tree and inspect `Issue 45 - Supply chain security gates`.
2. Confirm the `Vendored integrity and critical-CVE image gate` job built the image and passed Trivy (no CRITICAL findings), and `CodeQL Java SAST` completed successfully.
3. Confirm weekly Dependabot entries for Maven, Docker and GitHub Actions are active in repository settings. Future PRs depend on available updates.
4. Confirm `beta-prerelease.yml` blocks release publication if its candidate image contains CRITICAL vulnerabilities.
