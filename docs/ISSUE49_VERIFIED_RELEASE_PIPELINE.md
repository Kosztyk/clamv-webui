# Issue #49 — verified, tag-triggered GitHub releases

**Implementation:** `.github/workflows/release.yml` supersedes the old
`beta-prerelease.yml`. The unified pull-request workflow `ci.yml` and its
single required `gate` check from issue #48 are retained unchanged.

## Release lifecycle and security boundary

1. A **push** of an existing canonical SemVer tag `vMAJOR.MINOR.PATCH` or
   `vMAJOR.MINOR.PATCH-PRERELEASE` starts the release workflow. Manual
   `workflow_dispatch` takes a tag name and performs **verification only**:
   draft creation and publication are both disabled for dispatched runs.
2. The initial read-only job refuses tags with a version different from the
   Maven project's own `<version>`. It resolves the tag (including annotated
   tags), verifies the tag object matches the checked-out commit, and checks
   via the GitHub compare API that the commit is on `main`.
3. The exact commit must have a **completed successful `CI` push run on `main`**
   with exactly one completed successful **`gate`** job. The most recent run is
   authoritative; a previous success cannot mask a newer failure. A PR check
   from a different branch does not qualify. If the latest CI run is pending,
   missing or red, the tag workflow fails **before any publishing**. Wait for
   main CI to finish before creating the tag.
4. Jobs pin checkout to that exact commit and execute `bash
   scripts/release-gate.sh --docker-build`, including `mvn clean verify`,
   the scanner Docker build, mandatory Playwright tests, Docker HEALTHCHECK
   smoke, a Trivy **CRITICAL** blocker, actual Compose EICAR scanning, and the
   Testcontainers database migration matrix (PostgreSQL, MySQL, MariaDB).
5. The Go job runs `go test`, `go vet` and produces **six `clamctl` binaries**:
   Linux, macOS and Windows, each for amd64 and arm64. Every binary must match
   the tag-derived version in the Go build manifest and the manifest's SHA-256.
6. The draft job, **only for push events**, waits for every prior job to
   succeed. Its write token is scoped to the draft job. It checks that the
   remote tag has not moved, downloads the immutable CI artifacts, and
   assembles the versioned application JAR, six CLI executables, the full
   tracked-source ZIP, and **four signed-image evidence files** (OCI index, two CycloneDX SBOMs and immutable image reference); `SHA256SUMS` covers all twelve files.
   The checksums are independently verified before `gh release create` is
   allowed to create a **draft** release with automatically generated notes.
   Existing releases are never overwritten. Prerelease tags produce prereleases.
7. The draft remains **unpublished by default**. The publish job is enabled
   only when the repository variable `RELEASE_PUBLICATION_ENABLED` equals
   `true`. It uses the **`release` protected environment**, which **must**
   have required reviewers configured. After approval, the job rechecks the
   remote tag, downloads the draft assets, verifies all SHA-256 sums again,
   and only then sets `--draft=false`. No workflow can configure GitHub
   required reviewers on its own. **Do not enable the variable before
   configuring the environment protection.**

### Configure protected publication in GitHub

Open **Settings → Environments → New environment**. Create `release`, add one
or more **Required reviewers**, and consider allowing deployment only from
protected tags. Review that the reviewers are active and that bypass is
restricted. After this has been configured, create the repository Actions
variable `RELEASE_PUBLICATION_ENABLED` with value `true` under **Settings →
Secrets and variables → Actions → Variables**. Without that variable, each
successful tag push produces **a draft but no public release**. You can also
publish a draft manually in GitHub after verifying the assets.

### Correct version and tag

The current source is **`2.1.0`** in `pom.xml`, so the canonical final-release
tag is **`v2.1.0`**. Historical prerelease spellings remain historical and are
not reused. The release workflow requires the tag to match the Maven version
exactly and to point at a commit already merged into `main` with a successful
main-push `gate` run.

Once this change is merged into `main` and its `gate` is green:

```bash
git fetch origin main --tags
git switch main
git pull --ff-only origin main
git tag -a v2.1.0 -m 'ClamAV Web UI v2.1.0'
git push origin v2.1.0
```

Do not move or reuse an already published release tag. For an offline/CI dry
run without creating a release:

```bash
gh workflow run release.yml --ref main -f tag=v2.1.0
```

The tag must already exist in GitHub; dry-run still verifies its provenance
and executes the expensive release jobs. You may also verify policy locally:

```bash
python3 scripts/check-issue49-release.py
python3 scripts/test-issue49-release.py
bash scripts/release-gate.sh --static-only
```

A local `--static-only` check **does not certify** Maven, Docker, Go, GitHub
Actions, the database matrix, environment approvals or release assets. The
`release.yml` tag run executes these checks in GitHub CI before creating a
release draft. The GitHub CI job uploads Surefire/Failsafe, Playwright and
container logs for diagnostics on failure.

**Access:** Root `GITHUB_TOKEN` is read-only. The verifier has `actions: read`
for checking CI status; only the draft/publish jobs have `contents: write`.
GitHub Actions are SHA-pinned. No GitHub write operations are performed by
these offline source tests.

**Issue #51 extension:** The tag-only workflow now builds a multi-architecture
GHCR image after the full release tests, scans fixable HIGH/CRITICAL CVEs
on each architecture, signs the pushed digest with cosign, records CycloneDX
SBOMs and BuildKit provenance, and runs non-root/EICAR smoke on the
**published digest** before assigning stable tags or drafting the release.
See [issue #51 guide](ISSUE51_SIGNED_GHCR_RELEASE.md).