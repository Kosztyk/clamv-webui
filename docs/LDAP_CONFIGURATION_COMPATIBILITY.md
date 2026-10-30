# LDAP configuration compatibility — v2.0.0

This update implements the configuration contract and group-search filter from
[issue #3](https://github.com/Kosztyk/clamv-webui/issues/3). The application remains
v2.0.0. No database migration is required; the filter is stored in the existing
authentication settings JSON.

## Supported variables and properties

| Environment variable | Equivalent property |
|---|---|
| `SECURITY_LDAP_ENABLED` | `security.ldap.enabled` |
| `SECURITY_LDAP_URL` | `security.ldap.url` |
| `SECURITY_LDAP_BASE_DN` | `security.ldap.base-dn` |
| `SECURITY_LDAP_USER_SEARCH_BASE` | `security.ldap.user-search-base` |
| `SECURITY_LDAP_USER_SEARCH_FILTER` | `security.ldap.user-search-filter` |
| `SECURITY_LDAP_GROUP_SEARCH_BASE` | `security.ldap.group-search-base` |
| `SECURITY_LDAP_GROUP_SEARCH_FILTER` | `security.ldap.group-search-filter` |
| `SECURITY_LDAP_BIND_DN` | `security.ldap.bind-dn` |
| `SECURITY_LDAP_BIND_PASSWORD` | `security.ldap.bind-password` |

LDAP remains disabled when `SECURITY_LDAP_ENABLED` is omitted and no existing
configuration enables it. Both environment variables and dotted properties are
supported. Do not add real passwords to source files or committed examples.

## Precedence and existing installations

1. Once LDAP is explicitly saved in Administration → Authentication, those saved
   settings remain authoritative, including after restart.
2. Existing explicit `clamav.identity.ldap.*` configuration and documented
   `CLAMAV_IDENTITY_LDAP_*` environment variables remain supported. They take
   precedence over the new compatibility defaults when both names configure the
   same setting.
3. `SECURITY_LDAP_*` / `security.ldap.*` supply startup defaults when the
   corresponding existing setting is not explicitly configured.
4. Otherwise the existing defaults apply.

Adding a new environment variable does not override a provider already saved in
the admin UI. Existing saved settings without `groupSearchFilter` retain their
member-attribute lookup. A bind secret supplied through environment or the
existing `CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE` remains usable after saving
non-secret settings and restarting. An explicitly entered UI password is still
encrypted and takes precedence. Do not configure both an inline bind password
and a bind-password file.

Existing group-to-role settings are unchanged: use the current admin UI or
`CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER`, `_OPERATOR`, and `_ADMIN`. Unmapped users
receive no application role. Local login remains available independently.

## Custom group filters

Set `SECURITY_LDAP_GROUP_SEARCH_FILTER`,
`clamav.identity.ldap.group-search-filter`, or the new **Group search filter**
field under Administration → Authentication → LDAP / AD.

| Placeholder | Value |
|---|---|
| `{0}` | Full DN of the authenticated user; during nested lookup, the current group DN |
| `{1}` | Authenticated login name |

Substituted values are LDAP-filter escaped in a single pass. Parentheses,
wildcards, backslashes and NUL in a DN/login name cannot inject filter operators.
Operators written by the administrator remain intact. Filters must contain
`{0}` or `{1}`; invalid structure, escapes, unsupported placeholders and excessive
nesting are rejected. Attribute and matching-rule support is validated by the
directory when a search is performed.

Examples:

```text
(member={0})
(&(objectClass=groupOfNames)(member={0}))
(&(objectClass=group)(member={0}))
(&(objectClass=posixGroup)(memberUid={1}))
```

Nested group traversal needs a DN-based `{0}` filter; disable **Resolve nested
groups** when using a username-only `{1}` filter. The configured group-search
base remains relative to the configured base DN. Group names continue to come
from the existing group-name attribute and existing role mappings.

Leave the filter blank to use the existing **Group member attribute** field.
For example, `uniqueMember` produces `(uniqueMember={0})`. This preserves current
deployments. Clearing the filter in the UI restores this behaviour. Older
callers that omit the new field retain an already configured custom filter.

The existing **Test LDAP connection** checks configuration and manager bind/TLS.
It does not prove a particular user's membership query succeeds. Verify an
actual directory login and mapped role after choosing a custom filter.

## Example: configure through Docker environment

Merge these entries into the existing service rather than replacing its
database, volumes or proxy configuration:

```yaml
environment:
  SECURITY_LDAP_ENABLED: "true"
  SECURITY_LDAP_URL: "ldaps://ldap.example.com:636"
  SECURITY_LDAP_BASE_DN: "dc=example,dc=com"
  SECURITY_LDAP_USER_SEARCH_BASE: "ou=people"
  SECURITY_LDAP_USER_SEARCH_FILTER: "(uid={0})"
  SECURITY_LDAP_GROUP_SEARCH_BASE: "ou=groups"
  SECURITY_LDAP_GROUP_SEARCH_FILTER: "(&(objectClass=groupOfNames)(member={0}))"
  SECURITY_LDAP_BIND_DN: "cn=service,ou=people,dc=example,dc=com"
  SECURITY_LDAP_BIND_PASSWORD: "${LDAP_BIND_PASSWORD}"
  CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER: "ClamAV-Readers"
  CLAMAV_IDENTITY_LDAP_GROUPS_OPERATOR: "ClamAV-Operators"
  CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN: "ClamAV-Administrators"
```

Supply `LDAP_BIND_PASSWORD` privately. If using the existing password-file option,
omit the inline password variable instead. Trust the directory CA in the JVM.
`ldap://` connections still require the existing StartTLS option; this change
does not enable plaintext credential transport.

## Build and validation

```bash
mvn -B -ntp verify
```

The included tests cover real Spring environment/property binding, old-name
precedence, escaped compound filters, direct/nested group lookup, admin form
render/save, H2 persistence, legacy saved settings, bind-secret retention and
local login with LDAP enabled. Tests of directory queries use mocked LDAP
connections; they are not a live AD/OpenLDAP/FreeIPA/LLDAP certification.

For installation, extract the full source into a separate build directory and
build the image using the existing deployment process. Preserve the live
`docker-compose.yml`, `.env`, `conf/`, `data/`, truststore and database. Keep
existing Flyway baseline settings unchanged. No GitHub publication or live
deployment is performed by this source update.
