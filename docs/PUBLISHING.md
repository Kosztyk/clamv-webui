# Publish the full application source to GitHub

The complete v2.1.0 archive includes `publish-clamav-webui.sh` and `SOURCE_MANIFEST.sha256`. The same publishing script is also available separately so it can be run beside the downloaded ZIP.

## macOS and Linux

Git is required. If macOS has no working Git installation, run `xcode-select --install` once and finish the Command Line Tools installation. No Homebrew or Python installation is required by the publisher.

Save the script and the complete ZIP in Downloads, open Terminal there, and run:

```bash
cd "$HOME/Downloads"
bash ./publish-clamav-webui.sh \
  ./clamv-webui-v2.1.0-configuration-center-full-source.zip \
  OWNER/REPOSITORY
```

Replace `OWNER/REPOSITORY` with the intended existing GitHub repository. If the browser renamed a duplicate download with `(1)`, pass that actual filename in quotes. The ZIP's internal application directory remains `clamv-webui-v2.1.0/`.

The script uses an installed `gh` or downloads the official GitHub CLI 2.103.3 asset for macOS/Linux on Apple Silicon/ARM64 or x86_64. It checks the pinned asset SHA-256 before running it. On macOS, downloaded CLI files are cached in `~/Library/Caches/clamav-publisher`; on Linux, the script uses an absolute `$XDG_CACHE_HOME/clamav-publisher` or `~/.cache/clamav-publisher`. It probes a real write before downloading. If the cache is unavailable, it uses its private temporary working directory for that run. Temporary CLI credentials are configured for the running Git commands, keeping the temporary executable out of persistent Git helper settings. Checksums were checked against the official GitHub release API. No administrator installation or Homebrew is needed.

If necessary, GitHub CLI prompts for browser authentication. Repository and workflow access are needed because this update includes `.github/workflows`. An existing OAuth login without workflow scope is refreshed. For a fine-grained token, grant the destination repository **Contents** and **Workflows** write access, plus **Pull requests** write access if a PR is needed. Authentication secrets are handled by `gh`; the script does not embed a token or place one in a source file.

The publisher reads the repository's actual default branch instead of assuming `main`. Git and GitHub pagers are disabled, so the changed-file list cannot pause at a `:` prompt. Commit identity uses the authenticated GitHub account and its GitHub noreply address.

## Recover from the earlier macOS cache error

If an older script stopped while creating `~/.local/share` with **Permission denied**, replace the downloaded `publish-clamav-webui.sh` with the corrected script and rerun the same command. Your ZIP already passed verification and remains usable. This correction needs no `sudo`, ownership change or manual GitHub CLI installation. The current full archive also includes the corrected publisher.

## Verify or preview first

This validates ZIP integrity, safe paths, complete application files and every SHA-256 entry without using GitHub or the network:

```bash
bash ./publish-clamav-webui.sh ./clamv-webui-v2.1.0-configuration-center-full-source.zip --check
```

This prepares and displays the update in an isolated clone, with no commit or push:

```bash
bash ./publish-clamav-webui.sh ./clamv-webui-v2.1.0-configuration-center-full-source.zip OWNER/REPOSITORY --dry-run
```

To publish a new branch and open a PR directly:

```bash
bash ./publish-clamav-webui.sh ./clamv-webui-v2.1.0-configuration-center-full-source.zip OWNER/REPOSITORY --pull-request
```

## What gets published

The verified ZIP is the complete intended source snapshot. In a temporary clone, the script removes obsolete **tracked repository files**, copies the verified application files, stages the full snapshot and commits it. This preserves Git history while making the resulting tracked source match the archive, including removing tracked files absent from the new source. It does not read or modify an existing local checkout, running deployment, `.env`, database, conf volume or data volume.

The normal command tries a regular fast-forward push to the default branch. It never force-pushes, rebases away concurrent changes or deletes branches/tags. If the branch is protected or has advanced, the same prepared commit is published on a uniquely named update branch and a PR is opened. Review/merge that PR to complete the default-branch update. If both pushes fail, the command exits with an error and retains the isolated working directory for diagnosis. It claims success only after an accepted push or opened PR. If the complete source is already present, it reports that no update is needed.

A previous credential committed to GitHub history is not erased by a source snapshot update; that requires a separate credential rotation/history-cleanup procedure. This ZIP itself contains no Git history or live deployment state.

## What remains separate

Pushing source does not publish a GitHub release, build/push a Docker Hub image or deploy the application. CI is configured in the repository, but its successful completion must be checked on GitHub after the push.

Build this source normally:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

Then follow [README: Configuration Center](../README.md#configure-the-app-with-configuration-center) or [README: docker-compose.yaml](../README.md#configure-the-app-with-docker-composeyaml). No `build-and-start.sh --trusted-proxy` step is needed.

Official references: [GitHub CLI browser login](https://cli.github.com/manual/gh_auth_login), [Git credential setup](https://cli.github.com/manual/gh_auth_setup-git), [repository metadata](https://cli.github.com/manual/gh_repo_view), [pull request creation](https://cli.github.com/manual/gh_pr_create) and [the pinned CLI release](https://github.com/cli/cli/releases/tag/v2.103.3).
