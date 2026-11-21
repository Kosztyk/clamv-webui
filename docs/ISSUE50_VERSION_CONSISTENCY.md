# Issue #50 — One canonical release version

**Canonical version:** top-level `<project><version>` in `pom.xml` (currently `2.1.0`). The spelling of a git release tag is always the lowercase `v` followed by the **exact** Maven version. Historical `V1.0.0` / `V2.0.0` tags and previous release documentation are not retroactively rewritten.

## What updates automatically

| Consumer | Implementation |
|---|---|
| Maven JAR name | Maven `<project><version>` |
| Spring Boot `/actuator/info` build metadata | Already-generated `spring-boot-maven-plugin:build-info` |
| Application `clamav.app.version` | Maven resource filter: `@@project.version@@` in `src/main/resources/application.properties` |
| OpenAPI `info.version` | Maven resource filter for `src/main/resources/openapi/clamav-v1.yaml`; published resource from JAR is generated from POM |
| Login/footer and unauthenticated setup page | Maven-filtered version placeholders; Thymeleaf binds the running app version |
| Template JS/CSS cache busting | Runtime `${appVersion}` instead of a manually-maintained numeric query suffix |
| `clamctl` binary version | `cli/tools/build.go` reads `../pom.xml`, injects version through `-ldflags=-X main.version=...`, and rejects conflicting `-version` |
| CLI `User-Agent` | Uses the actual executable/application version, not a second literal |
| Docker Compose / local build scripts | Stable local tag `clamav-web-client:local`; user-overridable `CLAMAV_WEBUI_IMAGE_TAG`, no fictitious release version |
| README | Identifies POM as canonical; historical issue write-ups remain historical |

Only the six explicitly listed Maven resources are filtered. The delimiter is `@@` with Maven's default delimiters disabled, so **Spring `${CLAMAV_...}` and Thymeleaf `@{...}` expressions are not substituted by Maven**. The original runtime file under `conf/` is not filtered or shipped; never store credentials in source files.

## Release procedure: one manual change

For the final 2.1 release the root `<project><version>` is already `2.1.0`. For a future release, edit that root project version only (not the Spring Boot parent dependency version), then run:

```bash
python3 scripts/check-versions.py
python3 scripts/test-issue50-versions.py
mvn -B -ntp clean verify
python3 scripts/check-versions.py --verify-built
(cd cli && go run ./tools/build.go -targets linux/amd64)
```

After merging to `main`, wait for the successful required `gate` check and create a `v2.1.0` tag (never an uppercase `V`). `.github/workflows/release.yml` rejects a tag that differs from the POM or lacks a successful `main` gate, then verifies the full release build and package checksums. No manual version edits are needed in application properties, OpenAPI, CLI, Compose or README.

When building a local Compose image:

```bash
docker compose build clamav-web-client
docker compose up -d
```

The local image tag is `clamav-web-client:local` unless `CLAMAV_WEBUI_IMAGE_TAG` is set in your deployment environment. This is deliberately separate from the release's `vX.Y.Z` Git tag. For a versioned published image, use an explicitly approved/tagged release artifact rather than overwriting the local tag.

## Failure-mode acceptance tests

```bash
python3 scripts/check-versions.py --tag v2.1.0
# PASS — tag and current POM version match.

python3 scripts/check-versions.py --tag v2.1.0-beta2
# FAIL — tag/POM mismatch on the final 2.1.0 source.
```

The regression suite simulates a POM-only bump and proves that no downstream version sources require manual modification. It intentionally corrupts the app version, OpenAPI, CLI, Compose, README and release gate independently and verifies each failure. With `--verify-built` the checker also inspects Maven-filtered `target/classes` to catch misconfigured resource filtering, missing files, or stale values.

## Test boundaries

The offline checker, adversarial fixtures, CI YAML parsing and release-gate static mode can run without Maven/Docker. **The Maven resource filtering build, Java/Spring runtime metadata, Docker smoke and full Go CLI tests require Maven, Docker and Go 1.25 on a runner** and must be verified in CI before releasing. Never treat successful source checks as proof of a complete release build.
