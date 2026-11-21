# Issue #44 — Persistent LDAP identity namespace and strict StartTLS certificate verification

The old LDAP login implementation computed the `ExternalIdentityMapper` issuer from `LDAP_URL + "/" + LDAP_BASE_DN`. This string is input to `ldap:` + first 48 SHA-256 hex chars of `(issuer + NUL + immutable LDAP subject DN)`. Editing an LDAP hostname, switching a failover server, or changing the configured search base caused new canonical principals, even when the LDAP user was unchanged.

## Fix and safe upgrade

**The first application startup after this upgrade must use the old LDAP URL and base DN.** Flyway V8 (H2, PostgreSQL, MySQL, MariaDB) creates `ldap_directory_identity`, a single-row identity namespace in the same DB as `external_identities` and scan history. If LDAP is enabled and no ID has been pinned yet, the service stores the *historical issuer string exactly* (`old URL + "/" + old base DN`). Existing principal hashes, submitted-by values and external identity relationships remain valid. Subsequent starts, LDAP hostname failover and changes to base DN use the saved ID, not the current connection details. The pin is shared between application instances that use the same DB. If LDAP is disabled and no ID has ever been pinned, the service waits for LDAP to be enabled.

**Do not change both the connection URL/base and application version before the first upgrade startup**: the application cannot infer the previous issuer if it was never recorded. If you already changed the LDAP URL before upgrading, restore it for the first startup, then edit the URL after the ID has been stored. Make backups of the database and the existing configuration before upgrading. No automatic rewrite of `external_identities` or scan/audit history is performed. If the LDAP server changes the *actual returned user DN* (the immutable subject), an additional identity migration is necessary; changing the issuer alone cannot correct changes to the subject.

## Configuration Center

**Administration → Configuration Center → Identity & API → LDAP → Advanced → Stable LDAP directory ID**

- Canonical property: `clamav.identity.ldap.directory-id`
- Docker env override: `CLAMAV_IDENTITY_LDAP_DIRECTORY_ID`
- Default: blank = automatically pin the legacy issuer, once, in the application database
- Custom ID: 1–128 characters, letters/digits followed by letters/digits/dot/underscore/hyphen/colon; set **before first enabling LDAP** on a new installation
- Applying changes to this setting requires a restart. Environment override locks the GUI field.
- Once an ID has been pinned, changing it through Configuration Center Review/Apply is rejected. On startup, a conflicting deployment-level value is also rejected. To change the identity namespace deliberately requires a separately planned identity migration, not simply editing this field.

For new installations only, this is a valid explicit ID:

```yaml
environment:
  CLAMAV_IDENTITY_LDAP_DIRECTORY_ID: "corporate-ldap"
```

For **existing deployments upgrading from previous versions**, leave that environment variable and the GUI field unset. The first startup automatically pins the exact historical issuer, preserving previously computed principal names. After that first startup you can change `CLAMAV_IDENTITY_LDAP_URL` or the LDAP Base DN without changing canonical principals as long as the LDAP subject DN remains stable.

The pinned ID is stored in the same database as the auth/audit data; **migrating to a different database must include the V8 table and row**. Do not discard it between container recreations or database migrations.

## StartTLS hostname verification

The application continues to use `DefaultTlsDirContextAuthenticationStrategy` with its **default hostname verifier** (`null`, not a permissive custom verifier). The Spring LDAP default invokes the JNDI/JSSE hostname check during TLS negotiation. LDAPS uses JVM TLS hostname verification as before; trust validation and SAN/hostname checks must stay enabled. StartTLS never uses native JNDI LDAP pooling.

The regression suite includes an **actual local JNDI StartTLS exchange**: a temporary self-signed certificate is trusted by the test client but intentionally has a SAN that does *not* match the loopback LDAP URL. The JDK 21 client must throw `SSLPeerUnverifiedException` with a hostname-mismatch diagnostic; rejection for a different reason is not counted as a pass. The fixture uses JDK `keytool`, Java's built-in TLS engine, and a minimal local LDAP BER responder, without third-party LDAP servers or network access.

Tests:

```bash
python3 scripts/check-issue44-ldap-identity.py
mkdir -p target/issue44-jndi
javac -d target/issue44-jndi src/test/java/info/trizub/clamav/webclient/identity/StartTlsJndiMismatchProbe.java
java -cp target/issue44-jndi info.trizub.clamav.webclient.identity.StartTlsJndiMismatchProbe
mvn -B -ntp '-Dtest=Issue44LdapDirectoryIdentityTest,Issue44StartTlsHostnameVerificationTest,LdapSettingsCompatibilityTest,LdapConfigurationUiTest,ConfigurationConsumerWiringTest' test
mvn -B -ntp verify
```

**Note:** StartTLS checks run against the configured JVM trust material (including Configuration Center truststore and any mounted CA). Production LDAP must present a certificate whose SAN matches the exact LDAP hostname/IP used by the app. Do not disable hostname verification to workaround a certificate mismatch.
