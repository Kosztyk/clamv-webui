# Issue #50 — Changed files

- `pom.xml`: selectively filter application, OpenAPI and UI version resources with a delimiter safe for Spring/Thymeleaf; preserve unfiltered secret/config resources and Spring Boot build-info.
- `src/main/resources/application.properties`, `src/main/resources/openapi/clamav-v1.yaml`, `src/main/resources/configuration-center/setup.html`, `src/main/resources/templates/login.html`, `error.html`, `fragments/shell.html`: use filtered version and runtime cache-bust tokens.
- `src/main/java/.../web/UiModelAdvice.java`: non-version fallback for direct, unfiltered development modes.
- `cli/cmd/clamctl/main.go`, `cli/tools/build.go`, `cli/internal/cli/app.go`, `cli/internal/cli/client.go`, `cli/README.md`: derive binary version from root POM and use it in User-Agent; refuse conflicting explicit builder versions.
- `docker-compose.yml`, `docker-compose.configuration-center.yaml`, `docker-compose.environment.yaml`, `docker-compose.minimal.yaml`, `.env.example`, `build-and-start.sh`, `deploy-configuration-center.sh`: use neutral local tag, optional deployment override.
- `.github/workflows/ci.yml`, `.github/workflows/release.yml`: always-running version check, built artifact validation and pre-release tagged version validation.
- `scripts/release-gate.sh`, `scripts/clamav-release-gate.sh`, `scripts/verify-issue36-h2-shutdown.sh`, `scripts/check-static-inventory.py`, `scripts/generate-i18n-catalog.py`: remove hard-coded current version; keep legacy wrapper.
- `scripts/check-versions.py`, `scripts/test-issue50-versions.py`: new fail-closed validator and adversarial tests.
- `README.md`, `docs/ISSUE49_VERIFIED_RELEASE_PIPELINE.md`, `docs/ISSUE50_VERSION_CONSISTENCY.md`, release notes, this changelog, validation report and `SOURCE_MANIFEST.sha256`: documentation, reproducibility and packaging.

Earlier issue #36–#49 fixes, migrations, security controls and configuration values are preserved.
