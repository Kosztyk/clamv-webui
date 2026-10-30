# Phase 0 verification report

**Source baseline:** uploaded `clamv-webui-main.zip` (`main` snapshot dated 2026-08-13, Maven version 2.3.6).  This is a source patch, not a certified build of an existing production image. Maven integration tests provide an isolated bootstrap password so they do not leave a real `data/bootstrap-admin-password` in the checkout.

## Implemented

- Restored Thymeleaf templates for every routed login, operations and admin page; `error.html`; consistent sidebar; existing PJAX navigation hooks.
- New `/clamdtop` live UI backed by existing `ClamdTopService.snapshot()` via role-scoped `/api/clamdtop` endpoint, with escaped text-only command/file rendering.
- Kept native `MULTISCAN` on uploads; Compose now mounts `./data` at `/app/data` on both services so clamd can see upload paths. INSTREAM fallback is explicit.
- `SecurityConfig` permits `/error` and static assets, protects admin URLs; disabled H2 console by default. Exception handler no longer redirects all errors to dashboard (could hide template errors).
- Environment/DB secrets: generated per-install admin password in `data/bootstrap-admin-password` (POSIX 0600), optional `CLAMAV_BOOTSTRAP_ADMIN_PASSWORD`, password length policy, last-enabled-admin deletion safeguard; warning for legacy `admin/admin` accounts.
- `PathPolicy` resolves real filesystem paths (including symlinks) for allowlisted roots and targets; enforced at queueing, directory watching and execution for PATH/WATCH jobs.
- Existing null-safe webhooks preserved. Added notification on successful `OK` scans (existing code had only found/error notifications). Added API audit recording for upload and path requests.
- Compose, `build_docker.sh`, README, security/deployment guide, `.gitignore`, `.dockerignore`, GitHub Actions workflow and EICAR smoke script.

## Verified in this local session

| Check | Result |
|---|---|
| Java 21 `javac` on standalone PathPolicy and AtomicPropertiesFile | PASS |
| Standalone Java filesystem test (canonical file, denied external path, symlink escape, 0600 atomic settings) | PASS |
| JavaScript syntax using Node `--check` | PASS |
| Shell `bash -n` | PASS |
| YAML parsing of Compose and CI workflow using PyYAML | PASS (structural parser only) |
| HTML structure parsing for all 15 Thymeleaf templates | PASS (HTML syntax only, not Thymeleaf rendering) |

## Not yet verified here

- Maven dependency resolution and `mvn verify` (Maven not installed; container cannot reach Maven Central).
- Real Spring Boot / Thymeleaf rendering tests, role restrictions, audit/webhook JUnit suite (requires Maven dependency resolution).
- Docker image build/run, `docker compose config` CLI validation and EICAR integration scan (Docker CLI/daemon not available).
- Behavioral parity with a separately deployed native/ncurses ClamdTop binary (the supplied source contained `ClamdTopService`, but not the deployed terminal implementation). The web STATS monitor has been reconstructed, rather than importing unavailable production terminal assets.

The CI workflow is designed to verify these once committed to GitHub on an environment with Docker, Maven and network access. **Do not deploy to production until those checks pass.**

## Reproduce on a Docker-equipped host

```bash
mvn -B -ntp verify
docker compose config --quiet
docker compose up -d --build
scripts/smoke-eicar.sh
docker compose logs --tail=100
docker compose down
```

Use an isolated, newly created `./data` folder for acceptance; back up existing `./conf` and `./data` before upgrading. This is a non-destructive verification run only if the source is tested separately from production data.
