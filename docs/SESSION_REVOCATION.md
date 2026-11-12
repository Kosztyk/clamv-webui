# Account changes and browser session revocation (issue #22)

Deleting, disabling, changing the roles of or resetting the password of a local
account ends **all existing sessions for that account** after the account
transaction commits. The next protected API request receives `401` with
`error=session_expired`; browser navigation redirects to `/login?expired`.
The login page explains that the session ended or the account changed. A reset
also signs out the administrator performing it when resetting their own account.

A failed or rolled-back change leaves valid sessions intact. Changing only the
last-login timestamp, or saving identical access settings, does not revoke them.
A new login uses the current enabled flag, password and roles. The last enabled
local administrator cannot be deleted, disabled or demoted.

## Administration

Open **Admin → Users**:

- **Change access → Role / Account → Save access** changes the account's roles
  or enabled flag and revokes its sessions when either changes.
- **New password → Reset password** changes the password and revokes sessions.
- **Delete** removes the account and revokes sessions.
- **Revoke sessions** requires a fresh authentication without modifying the
  account. Other accounts keep their sessions.

These actions require an administrator and a valid browser CSRF token. Successful
access updates and explicit revocations appear in the audit log, alongside the
existing deletion/password-reset records. Passwords and session IDs are not
included in those audit entries.

## Current-state checks and lifetime

Every authenticated browser request checks the local account's immutable ID,
username, enabled flag, roles and credential state against the database. This
also catches direct database edits and account changes observed by a different
instance. A deleted account recreated with the same username cannot inherit an
old session. An unavailable database returns `503 authentication_unavailable`
without authorizing cached privileges or destroying an otherwise recoverable
session. This adds an account lookup for each authenticated browser request.

The session principal retains an immutable digest of the encoded password for
comparison after Spring erases its password field. It retains no cleartext
password. No account schema change is needed.

| Setting | Default | Meaning |
|---|---|---|
| `server.servlet.session.timeout` / `SERVER_SERVLET_SESSION_TIMEOUT` | `30m` | Idle timeout; activity refreshes it. |
| `clamav.security.session.max-age` / `CLAMAV_SECURITY_SESSION_MAX_AGE` | `1h` | Absolute time since successful login; activity does not refresh it. |

Both are editable under **Configuration Center → Network → Reverse proxy &
session security → Advanced** and require application recreation/restart. The
GUI accepts an idle duration from 1 minute to 24 hours. Absolute age must be from
1 second to 24 hours, with startup validation as well as GUI validation. Leave
these unset in Compose to use the defaults, or add to the existing web service:

```yaml
environment:
  SERVER_SERVLET_SESSION_TIMEOUT: "30m"
  CLAMAV_SECURITY_SESSION_MAX_AGE: "1h"
```

The absolute timer begins at successful local, LDAP or OIDC authentication,
including when login rotates an anonymous session ID. Cookie activity and
background page refreshes cannot extend it. No concurrent-login count limit is
introduced. A successful fresh authentication begins a new lifetime.

Spring's session registry tracks form and OIDC logins and the custom LDAP login
filter. Servlet destruction/ID-change events maintain the registry on logout,
expiry and session fixation protection. Active native ClamdTop WebSockets close
on explicit revocation/session destruction. Their input is checked before use;
a one-second scheduled check closes connections whose account or lifetime has
become invalid, including otherwise idle connections.

## LDAP and OIDC

External group memberships are a **login-time snapshot**. Remote group removal
is not pushed to the application automatically. Remove the user's access at the
identity provider, then use **Admin → Users → External user sessions → Revoke
sessions** for the recorded issuer/subject. This expires that source-qualified
identity's sessions, including multiple browser logins, without confusing an
LDAP identity with an OIDC identity or local account of a similar name.

Without an explicit revocation, the absolute lifetime bounds how long the
existing role snapshot remains usable. Fresh authentication obtains current
LDAP memberships or current OIDC claims; the identity provider may reuse its
own SSO login, and must issue up-to-date claims. Saving/loading a new active
provider configuration invalidates existing external sessions on their next
request through the provider revision check. Configuration Center provider
changes take effect after the required restart, which also ends in-memory
sessions. Disabling an active provider rejects its sessions immediately on the
next request. Local emergency-admin sessions remain independent.

The registry and explicit session revocations are **per application instance**;
a distributed session cluster is not supported. In a multi-instance deployment,
repeat manual local/external revocation on each instance. Local database state
checks still reject changed accounts on every instance's next request. Remote
membership removal requires revocation on each instance or expiry/relogin.
Requests already executing when a change commits are not retroactively undone.

## Compatibility and upgrade

Rebuild/recreate the application using this complete source. No Flyway migration
or special upgrade flags are added by #22. Keep existing database, configuration,
proxy and scanner settings. Restart ends existing in-memory sessions.

Basic and Bearer machine credentials keep their existing per-request checks;
Basic can still carry an anonymous CSRF cookie for CLI writes. Explicit Basic
headers always cause fresh password authentication rather than reuse cached
browser authentication. Scoped API tokens are independent credentials: revoke a
compromised token separately in **Admin → API Tokens**.

## Verification

Run the complete suite and the packaged HTTP acceptance script:

```sh
mvn verify
python3 tools/verify-session-revocation.py --output /tmp/session-acceptance.json
```

Tests cover real form sessions, all account mutations, multiple sessions and
unaffected users, fresh roles/passwords, rollback, direct database changes,
absolute expiry, Basic/CSRF compatibility, external revision/disable/revocation,
LDAP session registration, WebSocket rejection and real servlet registry cleanup.
The packaged script uses the actual executable JAR and Tomcat HTTP with isolated
H2 data and independent cookie jars. External identity and WebSocket behavior is
covered by focused tests with synthetic principals/transports; no live remote
LDAP/OIDC server, native PTY or Docker run is implied by those tests. See the
[current validation record](security/session-revocation-validation.json).
