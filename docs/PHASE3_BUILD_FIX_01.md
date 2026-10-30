# Phase 3 — Build Fix 01 (logout redirect)

The first Phase 3 Docker build completed compilation but failed one of 70 Maven tests:
`Phase3OfflineIdentityProviderTest.ssoButtonVisibleButIssuerOutageDoesNotBlockLocalBreakGlass`.

The test verified successful local emergency-administrator login with an intentionally
unavailable OIDC issuer, then submitted a CSRF-protected `POST /logout`.
The response was HTTP 204, rather than a redirect. The browser logout form must
redirect to the login page instead of displaying a blank response.

## Correction

- Configure the final Spring Security logout success handler **after** the optional
  OAuth2/OIDC login configuration.
- Local and LDAP logout redirects to `/login?logout` without calling the identity provider.
- In OIDC `logout-mode=provider`, only actual OIDC principals use the client-initiated
  provider logout handler. Local break-glass and LDAP logouts still redirect locally.
- The test now asserts that logout is rejected without CSRF, redirects with CSRF,
  and invalidates the HTTP session. Added a local-only logout regression test.

No production Flyway migrations, persisted data models, REST endpoints, or UI files changed.
No tests have been executed in this environment; the Dockerfile runs `mvn -B -q verify`
on rebuild.
