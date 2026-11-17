# ClamAV Web UI — maintainer release procedure

This is the **single, end-to-end release runbook**. It complements the technical
release implementation in `.github/workflows/release.yml`, not a substitute for
its required checks. Nothing in a GitHub release deploys to a production server.

## Ownership, prerequisites and one-time GitHub setup

- A maintainer with write access prepares the release. The GitHub `release`
  environment requires approval from a listed reviewer. A sole maintainer can
  serve as that reviewer when GitHub's **Prevent self-review** option is off.
- **Protect `main`** with the single required `CI / gate` status check, and
  enable **Require review from Code Owners** when an independent code owner
  can approve PRs. The `CODEOWNERS` file alone does not enforce approvals.
- Under **Settings → Environments**, create `release`, configure required
  reviewers and restrict deployment branches/tags as appropriate. Only **after**
  protection is set, add the repository Actions variable
  `RELEASE_PUBLICATION_ENABLED=true`. Leave it unset/false for draft-only
  releases. GitHub Actions must be enabled and the repository must have rights
  to publish GHCR packages and use OIDC for Sigstore signing.
- Under **Settings → Code security**, enable **Private vulnerability reporting**
  (see `SECURITY.md`); an administrator must verify this setting in GitHub. The
  contents of this source archive cannot enable repository settings.
- Verify that Dependabot, CodeQL, GitHub Actions, and the container registry
  are configured; check registry package visibility for public image pulls.
- Have `git`, `gh`, Python 3, Maven, JDK 21, Go (per `cli/go.mod`) and Docker
  when performing the optional local end-to-end checks.

## 1. Prepare and review the release candidate

1. Choose the version. The **only manually edited version is the root
   `pom.xml` `<version>`**; do not change resource placeholders, OpenAPI,
   Compose, or CLI version separately. Stable `2.1.0` uses `v2.1.0`;
   prerelease `2.1.0-beta2` uses `v2.1.0-beta2`. Uppercase historical tags
   are not the format for new releases.
2. Open a PR against `main`; complete the `.github/pull_request_template.md`
   checklist. Update `CHANGELOG.md` **Unreleased** with user-visible changes;
   separate security, fixed, changed, and migration/compatibility notes.
   Categorize issues/PRs using GitHub labels; `.github/release.yml` controls
   GitHub-generated release-note sections.
3. Review Flyway migrations for H2/PostgreSQL/MySQL/MariaDB, any rollback
   limits, environment variables, Configuration Center settings, API v1
   compatibility, and upgrades from existing instances. In particular,
   verify `SPRING_FLYWAY_BASELINE_ON_MIGRATE` / `SPRING_FLYWAY_BASELINE_VERSION`
   guidance for an existing unversioned database (not a universal setting for
   fresh databases). Back up persistent `data/` and `conf/` outside Git.
4. Run local checks when available:

   ```bash
   python3 scripts/check-versions.py
   python3 scripts/check-issue56-release-docs.py
   python3 tools/audit-source-package.py .
   bash scripts/release-gate.sh --docker-build
   ```

   The **full** gate runs `mvn clean verify` and Docker build; `--static-only`
   is useful for developing documentation but **not sufficient** for release.
   Run relevant Go/CLI checks and the OpenAPI contract suite as applicable.
   CI additionally performs database Testcontainers, security scanners,
   native image/EICAR smoke, and signed-artifact checks.
5. Merge the PR only after the same `gate` is green. Wait for the **main-push**
   `CI / gate` to finish successfully at the exact merge commit. A PR green
   check alone does **not** qualify for release.

## 2. Create the immutable version tag

```bash
# On the approved main commit after its main-push CI gate is green:
git fetch origin main --tags
git switch main
git pull --ff-only origin main
python3 scripts/check-versions.py
# Replace this example with the exact pom.xml version. Do NOT reuse a tag.
git tag -a v2.1.0 -m 'ClamAV Web UI v2.1.0'
git push origin v2.1.0
```

A tag pushed to another branch, an unapproved commit, or a version different
from the POM **fails before any image or release publication**. If the tag
already exists, do not move/reuse it: publish a new version instead.

For a no-publication diagnostic of an **existing tag**, the release workflow
has `workflow_dispatch`; manual runs **cannot** write the image/release:

```bash
gh workflow run release.yml --ref main -f tag=v2.1.0
```

## 3. Observe the automated, full release pipeline

Watch **Actions → Verified release** for the tag:

1. `verify-tag` checks exact tag, canonical POM version, `main` ancestry,
   latest successful `main` push `CI / gate`, and immutable commit SHA.
2. `release-tests`, `db-migrations`, **`upgrade-path` (H2 and PostgreSQL)**,
   `cli-test`, `clamctl`, and `cli-integration` run Maven/JDK 21, Flyway Testcontainers matrix, browser,
   `--docker-build`, CodeQL/CI prerequisites, Go staticcheck/govulncheck,
   reproducibility and the real built-server CLI integration.
3. **`upgrade-path` must pass for both vendors**: a real pinned published predecessor image
   is upgraded in place with preserved database and `data/` + `conf/` state;
   actual Basic/bearer logins, API data, Flyway history and AES-GCM settings
   are checked before image promotion or release drafting. See
   `docs/ISSUE57_UPGRADE_PATH_GATE.md`.
4. `cli-sign` signs the detached binary checksums via keyless Sigstore.
5. `image-publish` pushes a temporary multiarch index (amd64/arm64) to GHCR,
   scans **both pushed architectures** for fixable HIGH/CRITICAL CVEs, generates
   CycloneDX SBOMs and provenance, signs/attests the immutable image digest,
   verifies signatures, and pulls/runs **that same pushed digest** for non-root
   and EICAR smoke tests. Only then does it promote the digest to public tags.
   Prereleases receive only their exact version tag; stable releases get
   `X.Y.Z`, `X.Y`, `latest`.
6. `draft` re-verifies the tag and all image/CLI signatures, downloads the JAR,
   six Go binaries, full-source ZIP, image evidence, SBOMs and detached CLI
   checksum signature, checks `SHA256SUMS`, and creates a **draft** GitHub
   release with automatically generated category notes. Existing releases
   are never overwritten.
7. `publish` runs only when `RELEASE_PUBLICATION_ENABLED=true`. The protected
   `release` environment pauses for **required reviewer approval**; after it
   checks the tag and signatures yet again, it publishes the draft. Otherwise
   the draft stays unpublished for manual inspection.

**A failed build, test, scan, signature or smoke test is a release blocker.**
Do not use manual release creation to bypass a red gate; repair and submit
an updated source PR, then use a **new tag/version**.

## 4. Post-release verification and communication

```bash
gh release view v2.1.0 --repo Kosztyk/clamv-webui
# Download, then run: (the release publishes SHA256SUMS and signed CLI checksums)
sha256sum -c SHA256SUMS
# For the image, obtain the exact published digest from the release evidence:
docker pull ghcr.io/kosztyk/clamav-web-client:2.1.0
# Verify signatures against the exact trusted GitHub Actions identity/issuer,
# not just that *some* signature exists (see docs/ISSUE51_SIGNED_GHCR_RELEASE.md).
```

Verify release assets, image pullability, GHCR package visibility, and API/
smoke tests. Announce changes from `CHANGELOG.md` and generated GitHub notes.
Document upgrade migrations and backup/restore paths. A published release is
**not** a Docker Hub push and does **not** restart any production container.
A production rollout is a separate, scheduled approval using the verified
image **digest** (see `docker-compose.ghcr.yml`).

## 5. Rollback, yank or withdraw

- **Production:** restore the previously verified **digest**, redeploy the
  Compose service, check health/EICAR. If a Flyway schema migration is involved,
  **do not blindly downgrade** against the upgraded DB; restore a compatible
  DB/`data/`/`conf/` backup in a controlled maintenance window and validate.
- **Broken but unpublished draft:** leave it unpublished, fix the code and
  create a **new** tag/version. Preserve the failed logs and review evidence.
- **Published vulnerability/regression:** promptly mark the release unsafe in
  its notes, set `gh release edit TAG --draft=true` where GitHub permits it,
  mark affected GHCR image tags deprecated/restricted, notify downstream
  users, and release a patched version. Unpublishing cannot retract already
  downloaded binaries/images; never silently repoint an immutable version tag.
- **Credentials or sensitive IPs accidentally committed:** stop publication,
  revoke/rotate credentials, remove offending literals from current source,
  and evaluate Git history/packages and cached artifacts. Deleting a GitHub
  release does **not** remove exposed bytes from Git history or forks. Seek
  GitHub support for sensitive-history removal if appropriate; force rewrites
  need coordination and cannot guarantee historical erasure.
- **Rollback of workflow itself:** revert via reviewed PR, retain `CI / gate`
  and last verified digest; do not disable the required checks.

## Files and further detail

- `.github/workflows/ci.yml` — always-reporting required `gate`.
- `.github/workflows/release.yml` — full tag verification and artifacts.
- `.github/release.yml` — generated release-note categories.
- `docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md` — approval mechanics.
- `docs/ISSUE51_SIGNED_GHCR_RELEASE.md` — cosign/image attestations.
- `docs/ISSUE54_CLI_QUALITY_SIGNED_RELEASE.md` — six CLI binaries/signatures.
- `docs/ISSUE55_API_CONTRACT_GATE.md` — API compatibility.
- `SECURITY.md` — coordinated private vulnerability disclosure.
