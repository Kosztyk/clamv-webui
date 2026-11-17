# Issue #42 — changed files

- `.gitignore`: ignore all runtime configuration; allow only the template.
- `conf/clamav-web-client.properties.example`: credential-free example distributed in source.
- `src/main/java/info/trizub/clamav/webclient/service/SettingsService.java`: migrate and scrub legacy plaintext webhook URL; reject writes without encrypted storage.
- `src/test/java/info/trizub/clamav/webclient/service/Issue42LegacyWebhookMigrationTest.java`: encrypted migration / precedence / fail-closed tests.
- `tools/audit-source-package.py`, `publish-clamav-webui.sh`: reject accidental runtime file reintroduction or tracked file publication.
- `scripts/check-issue42-runtime-config.py`, `.github/workflows/issue42-runtime-config.yml`: Git-clean simulation and Java regression CI.
- `docs/ISSUE42_RUNTIME_CONFIGURATION_SECURITY.md`, webhook guides, README, release notes and validation report updated.

`conf/clamav-web-client.properties` is intentionally **not present** in this source archive.
When published, the existing tracked file must be removed from Git's index;
the publish script applies that deletion in its isolated clone.
