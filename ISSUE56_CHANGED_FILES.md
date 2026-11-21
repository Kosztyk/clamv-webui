# Issue #56 — Changed files

Baseline: complete issue #55 full source, retaining every prior change.

## Release governance

- `RELEASE.md` — complete version, approval, gate, tag, artifact verification, rollback and incident runbook.
- `CHANGELOG.md` — Keep a Changelog structure, candidate changes and historical release references.
- `SECURITY.md` — maintained versions and private disclosure instructions, including the GitHub settings that an administrator must enable.
- `.github/release.yml` — GitHub-generated release note categories.
- `.github/pull_request_template.md` — tests, migration, API compatibility, documentation, security, privacy and reviewer checklist.
- `.github/CODEOWNERS` — owners for security config, identity, migrations, API, workflows, Docker, registry/publishing and release policy.
- `README.md`, `docs/RELEASE_NOTES_V2_1_0.md` — new release/security documentation entry points.
- `docs/ISSUE56_RELEASE_PROCESS_AND_SOURCE_PRIVACY.md` — implementation, privacy remediation and operational caveats.

## Address removal from public source

- `INTEGRATION.md`, `SECURITY_UPDATE.md`, `docs/ISSUE51_SIGNED_GHCR_RELEASE.md`
- `SERVER_DEPLOYMENT.md`, `docs/REVERSE_PROXY_TLS.md`, `docs/PRODUCTION_SCANNING.md`, `docs/releases/v2.1beta1.md`
- `src/test/java/info/trizub/clamav/webclient/configuration/Issue40RestConfigurationCenterTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/Issue40RestSecurityTest.java`
- `src/test/java/info/trizub/clamav/webclient/service/Issue26EndpointValidationTest.java`
- `src/test/java/info/trizub/clamav/webclient/web/UiSmokeTest.java`

Real operator network values were replaced by reserved TEST-NET documentation values, with unrelated synthetic RFC1918 fixture addresses retained solely where the SSRF logic must exercise private network behavior. No actual operator address is reproduced in this report.

## Prevent reintroduction

- `tools/audit-source-package.py` — fail-closed source-tree/ZIP RFC1918 scan; explicit per-test exemptions, not broad source/test exclusions; no private values printed.
- `scripts/check-issue56-release-docs.py` — release/security governance assertions.
- `scripts/test-issue56-release-docs.py` — eight positive/adversarial privacy and documentation tests.
- `scripts/release-gate.sh` — launch privacy checks before full release checks.
- `.github/workflows/ci.yml` — scan every PR via always-on static job included in `gate`.
- `publish-clamav-webui.sh` — refuse unsafe full-source ZIP before GitHub auth/network actions.
- `scripts/test-publish-clamav-webui.py` — publisher rejection regression.
- `SOURCE_MANIFEST.sha256` — refreshed full-source hashes.

No production runtime settings, database migrations, or application logic changes are required. The modified test literals preserve equivalent endpoint-validation behavior.
