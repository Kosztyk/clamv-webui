# Issue #22 — revoke sessions when accounts change

This cumulative complete source retains fixes #13–#21 and fixes stale browser
sessions after local account deletion, password reset, disabling or role changes.
Committed changes expire all registered sessions for that immutable account ID;
local state is also checked before each authenticated browser request. API calls
receive 401 and browser navigation returns to an explanatory login page.

Admin → Users now provides access editing and explicit local/external session
revocation. Local, LDAP and OIDC logins share a one-hour absolute session lifetime
and a 30-minute idle timeout by default. Native ClamdTop connections also respect
session revocation. Basic/CSRF CLI requests and independent scoped Bearer tokens
keep their supported authentication flows.

External memberships remain login-time snapshots: remove access at the IdP and
revoke the recorded external identity's sessions, or wait for the bounded
lifetime and fresh authentication. Explicit registry revocation is per instance;
local database state validation works across instances sharing the database.

No schema migration is required. Build/recreate the application, then sign in
again. See [behavior, settings, administration and limits](docs/SESSION_REVOCATION.md)
and [verification results](docs/security/session-revocation-validation.json).
