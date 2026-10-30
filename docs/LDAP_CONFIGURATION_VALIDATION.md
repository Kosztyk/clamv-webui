# LDAP compatibility update — validation

Validation date: 29 October 2026. Application version: 2.0.0.

## Completed

- Maven `verify`: **BUILD SUCCESS — 112 tests, 0 failures, 0 errors, 0 skipped**.
- Executable Spring Boot JAR packaged successfully with Java 17, the project's
  configured source level. The Dockerfile continues to use Java 21.
- Phase 1, Phase 2 and Phase 4 structural checks passed.
- Six-language catalog and JavaScript syntax checks passed. All existing
  translations are unchanged; the two added LDAP field phrases are translated
  into Ukrainian, Romanian, French, Spanish and German.
- All existing migration files are unchanged. The filter uses existing provider
  settings JSON; existing saved settings missing the field retain their lookup.

## New tests

| Test class | Cases | Coverage |
|---|---:|---|
| `LdapPropertyAliasesTest` | 5 | All nine requested variables; defaults; existing-property precedence; existing documented environment names; dotted properties |
| `RecursiveLdapGroupResolverTest` | 15 | Compound and username filters; LDAP escaping; single-pass substitution; legacy member attribute; AD matching rule; malformed templates; nested searches |
| `LdapSettingsCompatibilityTest` | 4 | Persist/reload filter; legacy saved JSON; environment and file-secret retention/rotation; invalid save leaves previous configuration intact |
| `LdapConfigurationUiTest` | 1 | Alias-configured login options; successful local login with LDAP enabled; admin field rendering and save; persisted HTML-escaped filter; retained bootstrap secret |

The full suite includes the existing authentication, role/sidebar, API, scan
completion, webhook, UI rendering, path-policy and H2 migration tests.

## Runtime-specific test setup

This environment does not permit Mockito's automatic agent attachment. The
Maven run loaded the existing Byte Buddy agent at JVM startup through Surefire's
`argLine`; no application or Maven dependency changes were needed. Dependency
downloads used the runtime's network proxy. The packaged application has no
dependency on those test-time settings.

## Not exercised

- Live OpenLDAP, FreeIPA, LLDAP or Microsoft AD servers. Directory calls in the
  new group-query and admin tests are mocked; Spring configuration binding,
  actual UI routes, local login and H2 persistence are real.
- Docker image execution and external-database integration profile.
- Playwright browser fixtures, whose Python dependency/browser are unavailable.

For directory acceptance, configure the intended server and trusted CA, perform
manager-bind Test, then authenticate actual viewer/operator/admin users and an
unmapped user. Verify local recovery while the directory is unavailable.
