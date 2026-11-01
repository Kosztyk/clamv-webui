# LDAP TLS trust and configuration diagnostics

This historical Phase 3.1 topic now follows the v2.1.0 Configuration Center workflow. LDAP configuration is edited only under **Configuration Center → Identity & API → LDAP / Active Directory**. Provider changes require restart. Keep a working local administrator for recovery.

## Trust a private directory CA

Obtain the directory service's CA certificate from its administrator. Import it into a dedicated Java truststore; `keytool` prompts for the password rather than placing it in the command line:

```bash
mkdir -p certificates
keytool -importcert -alias directory-ca \
  -file ./certificates/directory-ca.crt \
  -keystore ./certificates/directory-truststore.p12 \
  -storetype PKCS12
```

Mount the prepared file in the Web UI service. These paths are examples; the file must exist and be readable by container UID 10001:

```yaml
volumes:
  - ./certificates/directory-truststore.p12:/app/trust/directory-truststore.p12:ro
```

In **Configuration Center → Network → Java truststore**, choose `/app/trust/directory-truststore.p12`, select `PKCS12`, and enter the truststore's password. Review, apply and restart the application. A deployment `JAVA_TOOL_OPTIONS` truststore override remains authoritative; remove that override before managing the same setting in the GUI.

Use the actual directory URL in LDAP settings. A documentation-only example is `ldaps://directory.example.test:636`. The server certificate must be valid for the chosen hostname or IP address, and Java must trust its issuing CA. The browser-facing HTTPS certificate and the directory certificate are separate trust relationships.

Select **Test connection** before enabling LDAP. Testing does not save the draft. Select **Review changes**, apply the reviewed settings, then restart. Keep certificate and hostname validation enabled.

## Diagnose a failed operation

Check the HTTP status and sanitized application logs:

```bash
docker compose logs --tail=150 clamav-web-client
docker exec clamav-web-client sh -c 'ls -ld /app/conf /app/data /app/trust'
```

A 401 or 403 can indicate an expired session, authorization failure or missing CSRF token. Review errors are shown without exposing submitted credentials. Connection tests distinguish certificate, bind and network failures. Do not print a complete environment or `JAVA_TOOL_OPTIONS` into an issue: either can contain secrets.

Back up `conf/configuration-center.key`, the corresponding configuration file and the complete persistent conf/data volumes. Retain `conf/auth-settings.key` when upgrading older database-managed provider settings. Never delete a key to fix a permission problem; restore access or the matching backup.

See [current configuration and recovery](CONFIGURATION_CENTER.md), [the complete guide](COMPLETE_USER_GUIDE.md) and [the field audit](CONFIGURATION_FIELD_AUDIT.md).
