# Security issue #14 — HTTP Basic password lockout

This complete source includes both the forwarded-header fix (#13) and Basic authentication brute-force protection (#14). Application version remains 2.1.0; no database migration or extra deployment flag is required.

HTTP Basic and local form login now share an account-wide counter across IP addresses. Five failed passwords within 15 minutes cause a 15-minute lockout from the fifth failure. Further Basic requests return 429 with login_locked and Retry-After, even with the correct password. A successful Basic or local form login before lockout clears the counter. LDAP keeps a separate account-wide counter; Bearer tokens are unaffected. Blocked requests do not extend the window. Counters remain in process memory.

Build from the extracted source root:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

Keep the existing database credentials, proxy settings and conf/data volumes when updating. Direct access should use SERVER_FORWARD_HEADERS_STRATEGY=none. Proxy TLS requires native forwarding and the actual proxy peer allowlist from the issue #13 instructions; an older explicit framework override still requires migration.

See docs/BASIC_AUTH_LOCKOUT.md for behavior and tests, docs/security/basic-auth-lockout-validation.json for current verification, and CHANGED_FILES.md for the file-by-file additions to the previous fixed archive.
