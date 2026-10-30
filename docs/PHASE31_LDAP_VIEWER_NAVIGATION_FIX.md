# LDAP Viewer navigation — v2.0.0 UI consistency fix

## Why `kosztyk` receives HTTP 403 on Scan and Scan Jobs

LDAP group `clamav-viewers` is mapped to `ROLE_VIEWER`. The existing role contract in
README.md and SecurityConfig permits `GET /dashboard`, `/main`, `/clamdtop` to VIEWER.
`/scan`, `/jobs`, `/jobs/{id}` are OPERATOR/ADMIN pages and can expose paths and job detail.
This is *not* an LDAP authentication problem: the identity and group mapping are working.

## Minimal UI correction

- Show **Scan** and **Scan Jobs** sidebar links only to OPERATOR and ADMIN roles.
- On the dashboard, render a scan job ID as non-clickable text for VIEWER-only users.
- Keep the authorized OPERATOR/ADMIN links unchanged.
- Do not broaden scan, file path, job detail, API or admin server permissions.
- Local admin and ClamdTop behavior are unchanged. No migration or configuration changes.

## How to test scanning using the current LDAP test account

In Authentication → LDAP / AD, set **Operator groups** to `clamav-viewers` and save,
then log out and log back in with `kosztyk`. This gives *every member* of that
LDAP group OPERATOR rights: acceptable for a disposable test server, not preferred
for production. Production should use a separate LDAP group `clamav-operators`
and put only approved scan operators in it. The operator role includes viewer rights.

The role is computed at authentication time; an existing session does not automatically
receive the new authorization after saving a mapping.

## Verification

`ViewerSidebarAuthorizationTest` covers the rendered sidebar for VIEWER, OPERATOR,
and ADMIN, as well as the server's denial of direct viewer GET requests to scan/jobs.
Execute `mvn test` (or `docker compose build clamav-web-client`) before deployment.
Run a browser login as viewer and as operator after upgrading.
