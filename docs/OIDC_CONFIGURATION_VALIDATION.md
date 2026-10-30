# OIDC compatibility update — validation

Validation date: 29 October 2026. Application version: 2.0.0.

## Completed

- Maven `clean verify`: **BUILD SUCCESS — 129 tests, 0 failures, 0 errors,
  0 skipped**. The executable Spring Boot JAR was built successfully.
- All four requested standard Spring environment variables bind to the active
  lazy OIDC provider. A complete standard configuration enables the SSO button;
  explicit custom disable and saved admin settings take precedence.
- A Spring MVC authorization request produces a redirect containing the requested
  scopes, client ID, state and existing `/login/oauth2/code/clamav` callback.
- Real local username/password login succeeds with an unreachable configured
  issuer. Boot and local login do not perform issuer discovery.
- Scope Save/Test routes, H2 persistence, restart loading, invalid-scope rejection,
  legacy saved JSON, and bootstrap-secret retention were exercised.
- Existing role-mapping and claim-extraction tests pass. A new environment-driven
  check extracts a `roles` claim and maps its configured admin group to
  `ROLE_ADMIN`, `ROLE_OPERATOR` and `ROLE_VIEWER`.
- Phase 1, Phase 2 and Phase 4 structural checks passed. Six-language catalogs and
  JavaScript syntax checks passed. The two new UI phrases are translated into
  Ukrainian, Romanian, French, Spanish and German, alongside English.
- All existing database migration files are unchanged. Scopes use the existing
  provider-settings JSON, so no new database migration is required.

## New tests

| Test class | Cases | Coverage |
|---|---:|---|
| `OidcPropertyAliasesTest` | 9 | Four exact environment names; automatic enable; defaults; incomplete configuration; canonical and legacy precedence; indexed scopes; secret-file priority; legacy opt-in; claim, button and logout environment names with role mapping |
| `LazyOidcClientRegistrationRepositoryTest` | 4 | Scopes on authorization-code registration; cache invalidation after settings revision; lazy discovery; disabled/unknown registration; invalid scopes; CSV/space normalization and unsafe-value rejection |
| `OidcSettingsCompatibilityTest` | 3 | Saved scopes/disable precedence after restart; retained environment secret; old JSON without scopes; invalid save leaves prior configuration intact |
| `OidcConfigurationUiTest` | 1 | Exact standard environment names in a full application context; SSO button; real local login; authorization redirect; admin scope field; Test versus Save; reload; secret retention; invalid UI save |

The full suite also retains the LDAP compatibility, role/sidebar, API, scan,
webhook, UI rendering, path-policy and H2 migration tests.

## Runtime-specific test setup

The Java tests use the cached project dependencies and Java 17. Mockito's Byte
Buddy agent was loaded at JVM startup because automatic agent attachment is
unavailable here. This requires no production dependency or Dockerfile change.

## Validation limits

- Live Authelia, Authentik, Keycloak, Dex, Okta and Google Identity login was not
  exercised. Issuer discovery in the new authorization-redirect test is mocked;
  the real token exchange, ID-token validation and provider logout require an
  actual configured provider. Existing Spring security validation is retained.
- Docker execution and the external-database integration profile were not run.
- Browser fixtures were not run: Playwright and Chromium are unavailable. The
  combined static release gate stops at its Playwright import in the Save/Test
  browser script. Structural checks and Maven verification ran separately; the
  combined gate is not reported as successful.

For live acceptance, configure your provider's supported scopes, callback and
role claims; authenticate mapped and unmapped users, then verify the intended
local or provider logout mode. Keep a working local administrator for recovery.
