# Issue #44 validation — 2026-11-13

## Fixed behavior

LDAP principal derivation no longer hashes the current LDAP connection URL and Base DN. `LdapDirectoryIdentity` pins an immutable namespace once in the application SQL database. For existing deployments it stores the exact legacy issuer `originalURL + "/" + originalBaseDn`, preserving already-computed principal hashes and references. Changing URL or base after that initial upgrade leaves the namespace unchanged, provided the LDAP server still returns the same immutable full user DN.

The application has an optional **Stable LDAP directory ID** field under Configuration Center → Identity & API → LDAP → Advanced. An explicitly selected ID must be supplied before first LDAP setup and is immutable afterwards; deployment override: `CLAMAV_IDENTITY_LDAP_DIRECTORY_ID`. The generated/stored DB ID is separate from the blank GUI setting, so automatic legacy IDs containing `://` are not mistakenly normalized as operator-entered IDs. Configuration Center rejects rotation at Review/Apply, and startup rejects contradictory explicit IDs. H2, PostgreSQL, MySQL and MariaDB all receive Flyway V8 migration files.

StartTLS still uses the strict default `DefaultTlsDirContextAuthenticationStrategy` (no custom trust-all `HostnameVerifier`), with JDK 21 hostname verification. No certificate trust or endpoint-name checks have been disabled.

## Actually executed

- `scripts/check-issue44-ldap-identity.py`: **20/20 offline source checks passed** (catalog, schema, wiring, tests, strict StartTLS strategy).
- Isolated Java 21 compile/execution of the actual `LdapDirectoryIdentity.java` against a minimal JDBC stub: passed historical issuer retention, URL/base-change invariance, ID-rotation rejection, and 100 concurrent startup attempts. This is an isolated check; it does **not** verify JDBC integration or SQL dialect execution.
- JDK 21 **real local wire-level StartTLS test**: generated a temporary cert with an intentionally incorrect SAN, explicitly trusted it, completed the local LDAP StartTLS BER exchange, and confirmed that `StartTlsResponse.negotiate()` rejected it with an `SSLPeerUnverifiedException` specifically for a hostname mismatch. Source: `StartTlsJndiMismatchProbe.java`. No production network used.
- Earlier issue #36–#43 offline source checks: passed.
- Catalog parsed and checked for duplicate field keys; GitHub Actions YAML parsed successfully; no bundled credentials, runtime configuration, certificates or keys.
- `tools/audit-source-package.py`: passed.
- Full ZIP integrity and SHA-256 manifest/publisher preflight: verified when packaging.

## Not executed here

- The full Maven/Spring Boot/JUnit suite (`mvn -B -ntp verify`) because Maven and its dependencies are not installed in this environment.
- Actual migration against running PostgreSQL, MariaDB and MySQL databases, and LDAP login against a real directory.
- Docker build/deployment and application-level health checks because Docker is not available.

## Recommended release acceptance checks

1. Back up existing SQL data, `conf` and private keys. **Do not change LDAP URL/base DN before the first startup of this version.**
2. Run `mvn -B -ntp '-Dtest=Issue44LdapDirectoryIdentityTest,Issue44StartTlsHostnameVerificationTest,LdapSettingsCompatibilityTest,LdapConfigurationUiTest,ConfigurationConsumerWiringTest' test` and `mvn -B -ntp verify`.
3. Run `docker build --pull -t clamav-web-client:issue44 .` and start on the *existing* database with its old LDAP URL/base.
4. Confirm a single row in `ldap_directory_identity` and the same principal IDs in `external_identities`. Test LDAP login, then change hostname to the failover URL; confirm principals and job audit attribution are unchanged. If the directory changes the returned user DN, explicitly plan a subject migration.
5. Confirm StartTLS rejects a trusted certificate whose SAN does not match the configured host. Confirm a valid trusted certificate with matching SAN is accepted on the real LDAP infrastructure.

Do not close issue #44 as fully verified until the Maven/Spring, SQL migration and live LDAP behavior have been checked in your deployment.
