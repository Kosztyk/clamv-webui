# Issue #44 — changed files

All files are included in the **complete v2.1.0-beta2 source archive**. This is an incremental update to the cumulative issue #43 full source. Earlier fixes #36–#43 remain intact.

## Java implementation

- `src/main/java/info/trizub/clamav/webclient/identity/LdapDirectoryIdentity.java` — pin the original LDAP issuer in an immutable shared DB row; allow a custom ID only on first initialization; reject rotations and tolerate concurrent inserts.
- `src/main/java/info/trizub/clamav/webclient/identity/LdapDirectoryIdentityPolicy.java` — reusable explicit ID validation.
- `src/main/java/info/trizub/clamav/webclient/identity/IdentityProperties.java` — separately track the optional configured ID and the effective pinned directory ID.
- `src/main/java/info/trizub/clamav/webclient/identity/AuthenticationSettingsService.java` — pin the ID during startup before external logins; preserve it on later edits; keep the legacy settings and Configuration Center paths compatible.
- `src/main/java/info/trizub/clamav/webclient/identity/DynamicLdapAuthenticationProvider.java` — derive external LDAP principals using the stable ID instead of the mutable URL/base DN.
- `src/main/java/info/trizub/clamav/webclient/identity/IdentityLdapConfiguration.java` — expose the existing strict default StartTLS strategy to regression tests without introducing a permissive verifier.
- `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCenterService.java` — reject a conflicting ID at review/apply when one is already pinned.
- `src/main/java/info/trizub/clamav/webclient/configuration/ConfigurationCatalog.java` — apply explicit ID syntax validation in Configuration Center.

## SQL and Configuration Center

- `src/main/resources/db/migration/{h2,postgresql,mysql,mariadb}/V8__ldap_directory_identity.sql` — create durable `ldap_directory_identity` table on every supported DB.
- `src/main/resources/configuration-center/catalog.json` — new Advanced LDAP directory ID field, restart-bound, deployment-override precedence.
- `src/main/resources/application.properties` — optional `CLAMAV_IDENTITY_LDAP_DIRECTORY_ID` binding, blank by default.

## Tests and CI

- `src/test/java/info/trizub/clamav/webclient/identity/Issue44LdapDirectoryIdentityTest.java` — historical hash, existing external identity row, URL/base changes, new custom ID, conflicting ID, temporarily disabled LDAP, input validation.
- `src/test/java/info/trizub/clamav/webclient/identity/Issue44StartTlsHostnameVerificationTest.java` — strict default strategy, real JNDI SAN mismatch, sanitized mismatch diagnostics, optional external fixture.
- `src/test/java/info/trizub/clamav/webclient/identity/StartTlsJndiMismatchProbe.java` — independently runnable real local JNDI/JSSE StartTLS handshake with a trusted but wrong-host certificate.
- `src/test/java/info/trizub/clamav/webclient/identity/LdapConfigurationUiTest.java` — pinned ID cannot be changed through Configuration Center.
- `src/test/java/info/trizub/clamav/webclient/identity/{LdapSettingsCompatibilityTest,OidcSettingsCompatibilityTest,AuthenticationSettingsSecretBindingTest}.java` — include V8 schema in partial in-memory test databases.
- `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationConsumerWiringTest.java` — Flyway expected migration version 8.
- `scripts/check-issue44-ldap-identity.py` and `.github/workflows/issue44-ldap-identity.yml` — focused offline, Maven and strict JDK TLS checks.

## Documentation

- `README.md`, `docs/COMPLETE_USER_GUIDE.md`, `docs/CONFIGURATION_CENTER.md`, `docs/CONFIGURATION_REFERENCE.md`, `docs/CONFIGURATION_FIELD_AUDIT.md`, `docs/RELEASE_NOTES_V2_1_0.md`, `docs/ISSUE44_LDAP_STABLE_DIRECTORY_ID.md`.
- `ISSUE44_VALIDATION_RESULTS.md` — validation results, reproducibility and limitations.
- `SOURCE_MANIFEST.sha256` — rebuilt archive integrity manifest.

**Data migration:** Flyway V8 applies automatically on a properly configured existing database. There is no rewrite or deletion of existing LDAP external identities or historical scan/audit records. **The first upgrade startup must retain the old LDAP URL and base DN**, before they are changed afterwards.
