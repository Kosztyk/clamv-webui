#!/usr/bin/env bash
# Publish this complete v2.1.0 source snapshot. macOS Bash 3.2 and Linux.
# Usage: bash publish-clamav-webui.sh FULL_SOURCE.zip OWNER/REPOSITORY [--dry-run|--pull-request]
# Offline validation: bash publish-clamav-webui.sh FULL_SOURCE.zip --check
set -euo pipefail
umask 077
export GIT_PAGER=cat GH_PAGER=cat PAGER=cat GH_HOST=github.com
publish_work=''
publish_mode=push
publish_arguments=()
publish_die() { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
publish_info() { printf '\n%s\n' "$*"; }
publish_cleanup() {
    local result=$?
    trap - EXIT
    if [[ -n "$publish_work" && -d "$publish_work" ]]; then
        if [[ "$result" -eq 0 ]]; then rm -rf "$publish_work"
        else printf '\nWorking files retained at: %s\n' "$publish_work" >&2; fi
    fi
    exit "$result"
}
trap publish_cleanup EXIT
publish_help() {
    printf 'Usage: bash %s FULL_SOURCE.zip OWNER/REPOSITORY [--dry-run|--pull-request]\n' "${0##*/}"
    printf '       bash %s FULL_SOURCE.zip --check\n' "${0##*/}"
    printf '\n--check validates the complete archive offline.\n'
    printf '%s\n' '--dry-run stages an isolated repository update without a commit or push.'
    printf '%s\n' '--pull-request publishes a new branch and opens a PR instead of trying the default branch.'
    printf 'Default: normal push to the repository default branch; PR fallback if rejected. No force-push.\n'
}
for publish_argument in "$@"; do
    case "$publish_argument" in
        --help|-h) publish_help; exit 0 ;;
        --check) [[ "$publish_mode" == push ]] || publish_die 'Choose only one mode.'; publish_mode=check ;;
        --dry-run) [[ "$publish_mode" == push ]] || publish_die 'Choose only one mode.'; publish_mode=dry-run ;;
        --pull-request) [[ "$publish_mode" == push ]] || publish_die 'Choose only one mode.'; publish_mode=pull-request ;;
        --*) publish_die "Unknown option: $publish_argument" ;;
        *) publish_arguments+=("$publish_argument") ;;
    esac
done
[[ "${#publish_arguments[@]}" -ge 1 && "${#publish_arguments[@]}" -le 2 ]] || { publish_help; exit 2; }
publish_archive="${publish_arguments[0]}"
[[ -f "$publish_archive" ]] || publish_die "Archive not found: $publish_archive"
publish_archive=$(cd "$(dirname "$publish_archive")" && pwd -P)/$(basename "$publish_archive")
publish_repo="${publish_arguments[1]:-}"
if [[ "$publish_mode" != check ]]; then
    [[ "$publish_repo" =~ ^[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_.-]+$ ]] || publish_die 'Supply the destination as OWNER/REPOSITORY.'
fi
for publish_tool in unzip awk sort uniq wc tr mktemp cp; do
    command -v "$publish_tool" >/dev/null 2>&1 || publish_die "Required command missing: $publish_tool"
done
if command -v shasum >/dev/null 2>&1; then publish_hash_tool=shasum
elif command -v sha256sum >/dev/null 2>&1; then publish_hash_tool=sha256sum
else publish_die 'Install shasum or sha256sum before continuing.'; fi
publish_hash() {
    if [[ "$publish_hash_tool" == shasum ]]; then shasum -a 256 "$1" | awk '{print $1}'
    else sha256sum "$1" | awk '{print $1}'; fi
}
publish_work=$(mktemp -d "${TMPDIR:-/tmp}/clamav-source-publish.XXXXXX")
unzip -Z1 "$publish_archive" > "$publish_work/members.txt" || publish_die 'Cannot read ZIP members.'
[[ -s "$publish_work/members.txt" ]] || publish_die 'The archive is empty.'
[[ -z "$(sort "$publish_work/members.txt" | uniq -d)" ]] || publish_die 'Duplicate ZIP members are not permitted.'
publish_files=0
while IFS= read -r publish_member; do
    [[ "$publish_member" == clamv-webui-v2.1.0/* ]] || publish_die 'Use the complete prepared v2.1.0 source ZIP, not a patch or GitHub automatic ZIP.'
    publish_relative="${publish_member#clamv-webui-v2.1.0/}"
    [[ -n "$publish_relative" ]] || continue
    [[ "$publish_relative" =~ ^[A-Za-z0-9_./@+-]+$ ]] || publish_die 'Unsafe or unsupported archive pathname.'
    case "${publish_relative##*/}" in
        .env|.env.*) [[ "${publish_relative##*/}" == .env.example ]] || publish_die 'Private environment files are not source files.' ;;
        *.key|*.pem|*.p12|*.pfx|*.jks|*.crt|id_rsa|id_ed25519) publish_die 'Private key, certificate or truststore files are not part of this source release.' ;;
    esac
    case "/$publish_relative" in
        *'/../'*|*'/..'|*'/./'*|*'//'*) publish_die 'Archive paths must be normalized relative paths.' ;;
        *'/.git/'*|*'/.git'|*'/.env'|*'/.secrets/'*|*'/.secrets') publish_die 'Git metadata and runtime secrets do not belong in the source archive.' ;;
        /data/*|/target/*|/conf/*.key|/conf/*truststore*|/conf/*.p12|/conf/*.pfx|/conf/*.jks|/conf/*.pem|/conf/configuration-*|/conf/integrations.properties)
            publish_die 'Runtime data, keys, truststores or configuration credentials are present in the archive.' ;;
    esac
    case "$publish_member" in */) ;; *) publish_files=$((publish_files+1)) ;; esac
done < "$publish_work/members.txt"
[[ "$(unzip -Z -l "$publish_archive" | awk '$1 ~ /^l/ {n++} END {print n+0}')" == 0 ]] || publish_die 'Symbolic links are not permitted in the prepared source archive.'
unzip -tq "$publish_archive" > "$publish_work/zip-check.log" || publish_die 'ZIP integrity check failed; download the full archive again.'
mkdir -p "$publish_work/extracted"
unzip -q "$publish_archive" -d "$publish_work/extracted"
publish_source="$publish_work/extracted/clamv-webui-v2.1.0"
for publish_required in pom.xml Dockerfile README.md SOURCE_MANIFEST.sha256 docker-compose.minimal.yaml docker-compose.environment.yaml docs/CONFIGURATION_FIELD_AUDIT.md src/main/resources/application.properties; do
    [[ -f "$publish_source/$publish_required" ]] || publish_die "Incomplete source: missing $publish_required"
done
awk 'length($1)!=64 || $1 !~ /^[0-9a-f]+$/ || substr($0,65,2)!="  " || substr($0,67)=="" {bad=1} END {exit bad}' \
    "$publish_source/SOURCE_MANIFEST.sha256" || publish_die 'Invalid source manifest format.'
[[ -z "$(awk '{print substr($0,67)}' "$publish_source/SOURCE_MANIFEST.sha256" | sort | uniq -d)" ]] || publish_die 'Duplicate manifest paths.'
publish_manifest_files=0
while IFS= read -r publish_line; do
    publish_path="${publish_line:66}"
    [[ "$publish_path" =~ ^[A-Za-z0-9_./@+-]+$ && "$publish_path" != SOURCE_MANIFEST.sha256 ]] || publish_die 'Invalid manifest path.'
    case "/$publish_path" in *'/../'*|*'/..'|*'/./'*|*'//'*) publish_die 'Manifest path escapes the source tree.' ;; esac
    [[ -f "$publish_source/$publish_path" && ! -L "$publish_source/$publish_path" ]] || publish_die 'A manifest file is missing or is not a regular file.'
    publish_manifest_files=$((publish_manifest_files+1))
done < "$publish_source/SOURCE_MANIFEST.sha256"
[[ "$publish_files" -eq $((publish_manifest_files+1)) ]] || publish_die 'Archive contains files missing from its manifest.'
if [[ "$publish_hash_tool" == shasum ]]; then
    (cd "$publish_source" && shasum -a 256 -c SOURCE_MANIFEST.sha256) > "$publish_work/manifest-check.log" 2>&1 || publish_die 'Source SHA-256 validation failed.'
else
    (cd "$publish_source" && sha256sum -c SOURCE_MANIFEST.sha256) > "$publish_work/manifest-check.log" 2>&1 || publish_die 'Source SHA-256 validation failed.'
fi
publish_info "Validated $publish_files full-source files. Archive SHA-256: $(publish_hash "$publish_archive")"
if [[ "$publish_mode" == check ]]; then publish_info 'Archive validation passed. No GitHub authentication or network access was used.'; exit 0; fi
for publish_tool in git curl uname; do command -v "$publish_tool" >/dev/null 2>&1 || publish_die "Required command missing: $publish_tool"; done
git --version >/dev/null || publish_die 'Install Git. On macOS: xcode-select --install'

# Pinned official asset SHA-256 values verified against the GitHub release API.
publish_bootstrap_gh() {
  if command -v gh >/dev/null 2>&1; then
    publish_gh=$(command -v gh)
    return
  fi
  local version='2.103.3' platform architecture asset expected_sha cached unpack archive
  case "$(uname -s):$(uname -m)" in
    Darwin:arm64|Darwin:aarch64)
      platform='macOS'; architecture='arm64'
      expected_sha='d700207eae9ae98fe2b89535c2c087c07823d4628c56364695a5a07ef311c062' ;;
    Darwin:x86_64)
      platform='macOS'; architecture='amd64'
      expected_sha='a2f86418f4350ae6e9ec60d5000bdf7665728436946a97fc27fe50e6ae752682' ;;
    Linux:aarch64|Linux:arm64)
      platform='linux'; architecture='arm64'
      expected_sha='ee32df2323c8847f5fac6cb6b4b69ca75828e9329a8f22f677021f7b3306ce63' ;;
    Linux:x86_64)
      platform='linux'; architecture='amd64'
      expected_sha='4c806978ffce1cec70853302a56d70ac6da4f19bae7adca4e1a8ab00633dd91d' ;;
    *) publish_die 'Automatic GitHub CLI installation supports macOS/Linux on arm64 or amd64. Install gh manually for this platform.' ;;
  esac
  cached="$HOME/.local/share/clamav-publisher/gh-$version-$platform-$architecture"
  if [[ -x "$cached/bin/gh" ]]; then
    publish_gh="$cached/bin/gh"
    export PATH="$cached/bin:$PATH"
    return
  fi
  if [[ "$platform" == 'macOS' ]]; then
    asset="gh_${version}_${platform}_${architecture}.zip"
  else
    command -v tar >/dev/null 2>&1 || publish_die 'tar is required to install GitHub CLI on Linux.'
    asset="gh_${version}_${platform}_${architecture}.tar.gz"
  fi
  archive="$publish_work/$asset"
  publish_info "Installing GitHub CLI $version from its official release (SHA-256 verified)..."
  curl --fail --location --show-error --retry 3 --connect-timeout 15 --max-time 300 \
    "https://github.com/cli/cli/releases/download/v$version/$asset" -o "$archive"
  [[ "$(publish_hash "$archive")" == "$expected_sha" ]] || publish_die 'GitHub CLI download checksum mismatch; nothing was installed.'
  unpack="$publish_work/gh-unpack"
  mkdir -p "$unpack"
  if [[ "$platform" == 'macOS' ]]; then
    unzip -q "$archive" -d "$unpack"
  else
    tar -xzf "$archive" -C "$unpack"
  fi
  local extracted=''
  for candidate in "$unpack"/*/bin/gh "$unpack"/bin/gh; do
    [[ -f "$candidate" ]] && extracted="$candidate"
  done
  [[ -n "$extracted" ]] || publish_die 'The GitHub CLI archive did not contain bin/gh.'
  mkdir -p "$cached/bin"
  cp "$extracted" "$cached/bin/gh"
  chmod 700 "$cached/bin/gh"
  publish_gh="$cached/bin/gh"
  export PATH="$cached/bin:$PATH"
  "$publish_gh" --version
}
publish_bootstrap_gh

if ! "$publish_gh" auth status --hostname github.com >/dev/null 2>&1; then
    publish_info 'Sign in to GitHub in the browser when prompted. Allow repository and workflow access.'
    "$publish_gh" auth login --hostname github.com --git-protocol https --web --scopes repo,workflow
fi
"$publish_gh" auth setup-git --hostname github.com
publish_oauth_scopes=$("$publish_gh" api --include user | awk 'tolower($0) ~ /^x-oauth-scopes:/ {sub(/^[^:]*:[[:space:]]*/, "");gsub(/[[:space:]]/, "");print}')
if [[ -n "$publish_oauth_scopes" && -z "${GH_TOKEN:-}" && -z "${GITHUB_TOKEN:-}" ]]; then
    case ",$publish_oauth_scopes," in
        *,workflow,*) ;;
        *) publish_info 'Approve workflow access for the included CI files.'; "$publish_gh" auth refresh --hostname github.com --scopes workflow ;;
    esac
fi
[[ "$("$publish_gh" api "repos/$publish_repo" --jq '.permissions.push')" == true ]] || publish_die 'The active GitHub account lacks push access to the selected repository.'
publish_base=$("$publish_gh" repo view "$publish_repo" --json defaultBranchRef --jq '.defaultBranchRef.name')
[[ -n "$publish_base" && "$publish_base" != null ]] || publish_die 'The destination must have an existing default branch.'
git check-ref-format --branch "$publish_base" >/dev/null || publish_die 'Invalid default branch returned by GitHub.'
publish_info "Preparing the complete source update for $publish_repo ($publish_base) in an isolated clone..."
git clone --single-branch --branch "$publish_base" "https://github.com/$publish_repo.git" "$publish_work/repository"
cd "$publish_work/repository"
# The supplied ZIP is the authoritative complete tracked source snapshot.
# Remove obsolete tracked files in this isolated checkout, then stage only the
# verified archive. No deployment folders or existing local checkout are read.
git rm -r --ignore-unmatch --quiet -- .
cp -Rp "$publish_source/." ./
git add --force --all -- .
[[ "$(git ls-files | wc -l | tr -d ' ')" -eq "$publish_files" ]] || publish_die 'The staged source file count does not match the verified archive.'
if git diff --cached --quiet; then publish_info 'The selected default branch already contains this complete source. No push needed.'; exit 0; fi
publish_info 'Prepared source and documentation changes:'
git --no-pager diff --cached --stat
if [[ "$publish_mode" == dry-run ]]; then publish_info 'Dry run passed. No commit, branch or push was created.'; exit 0; fi
publish_identity=$("$publish_gh" api user --jq '[.login, (.id | tostring)] | join(" ")')
read -r publish_login publish_user_id <<< "$publish_identity"
[[ "$publish_login" =~ ^[A-Za-z0-9-]+$ && "$publish_user_id" =~ ^[0-9]+$ ]] || publish_die 'Cannot determine the authenticated GitHub identity.'
git config --local user.name "$publish_login"
git config --local user.email "${publish_user_id}+${publish_login}@users.noreply.github.com"
publish_title='Release v2.1.0 Configuration Center, complete field audit and deployment guides'
git -c commit.gpgsign=false commit -m "$publish_title"
publish_commit=$(git rev-parse HEAD)
if [[ "$publish_mode" == push ]]; then
    if git push origin "HEAD:refs/heads/$publish_base"; then
        publish_info "SUCCESS: complete application source and documentation pushed to $publish_repo/$publish_base"
        printf 'Commit: https://github.com/%s/commit/%s\n' "$publish_repo" "$publish_commit"
        printf 'CI: https://github.com/%s/actions\n' "$publish_repo"
        exit 0
    fi
    # A failed response can be ambiguous; avoid a duplicate PR if the push landed.
    publish_remote=$(git ls-remote origin "refs/heads/$publish_base" | awk '{print $1}')
    if [[ "$publish_remote" == "$publish_commit" ]]; then publish_info 'SUCCESS: the complete update is already on the default branch.'; exit 0; fi
    publish_info 'Default-branch push was rejected. Publishing an update branch and opening a pull request...'
fi
publish_branch="update/configuration-center-2.1.0-$(date -u +%Y%m%dT%H%M%SZ)-$$"
if ! git push origin "HEAD:refs/heads/$publish_branch"; then
    publish_die 'GitHub rejected the update branch. Check repository Contents and Workflows write permissions; the isolated checkout is retained.'
fi
cat > "$publish_work/pr-body.md" <<'PUBLISH_PR_BODY'
Provide the complete v2.1.0 application with Configuration Center as the sole global editor, standalone setup/recovery, existing security and webhook fixes, CLI/MCP source, database migrations, monitoring dashboards and automatic scan-job refresh.

Includes the 107-field audit and fixes for disabled-engine scan submission, primary-engine naming/policy, watcher timing/retry behavior and validation bounds. The README and guides describe GUI-managed, Compose-managed and hybrid configuration, the minimal Compose, separate recovery/bootstrap credentials, persistence and upgrades. Examples use generic values; private deployment state is excluded.

The source archive includes its own file-integrity manifest. Java, packaged-application and browser results and environment limitations are recorded in SERVER_PACKAGE_VALIDATION.json and docs/CONFIGURATION_CENTER_VALIDATION.md. Docker images and GitHub releases are separate operations.
PUBLISH_PR_BODY
"$publish_gh" pr create --repo "$publish_repo" --base "$publish_base" --head "$publish_branch" \
    --title "$publish_title" --body-file "$publish_work/pr-body.md"
publish_info 'SUCCESS: the complete source is on the update branch and its pull request is open. Merge the PR to update the default branch.'
