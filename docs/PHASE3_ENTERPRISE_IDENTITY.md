# Phase 3 — LDAP / Active Directory and OIDC authentication

LDAP configuration also supports the issue #3 `SECURITY_LDAP_*` names and a full
group-search filter. See [LDAP configuration compatibility](LDAP_CONFIGURATION_COMPATIBILITY.md)
for precedence, examples and preservation of existing settings.

Phase 3 extends the working Phase 2 Spring Boot 3.5 / Flyway / PostgreSQL application. **Both providers are disabled by default.** Existing local users and v1 Bearer tokens remain separate; no previous phase functionality is intentionally removed.

## Security model and data upgrade

- Flyway V3 adds `external_identities` on H2, PostgreSQL, MariaDB and MySQL. It stores an opaque, deterministic `ldap:<SHA256>` or `oidc:<SHA256>` principal with the exact `(source, issuer, immutable subject)`, first seen and last seen timestamps. It **never** stores reusable passwords for external users or creates local `app_users` rows for them. Email/usernames are not identity keys and never link accounts automatically. No local-profile linking UI is enabled; linking would require a separate explicit, audited admin workflow.
- Local accounts continue authenticating only against `app_users`; `/login` is the local break-glass form. LDAP has a **distinct** `/login/ldap` POST form. OIDC uses a Spring Security authorization-code callback at `/login/oauth2/code/clamav` (or configured registration ID). This avoids ambiguous provider selection and allows local login to function when external providers fail.
- Roles are explicit allowlists. All external users get **zero authorities** unless a group matches a configured mapping. `ROLE_OPERATOR` implies `ROLE_VIEWER`; `ROLE_ADMIN` implies all three. IdP administrator names or email domains alone do not grant any role. On successful external admin sign-in, the audit log records `EXTERNAL_ADMIN_GRANTED`, with a source-qualified principal ID.
- Once authorized, an external profile's groups are mapped on every new sign-in. Existing browser sessions keep their role snapshot until logout/expiration; revoke sessions as part of IdP role revocation where immediate revocation is necessary.
- All browser-changing requests require CSRF, including legacy session-based `/api/**`. Explicit HTTP Basic on legacy API and Bearer token on v1 API bypass browser CSRF but **require HTTPS**. Browser sessions and API tokens are separate authentication mechanisms.
- Local/LDAP form login failures are throttled at five per account + remote IP per 15-minute window, independent between providers. This is **per-process only**, not a distributed lockout system. Configure perimeter WAF/IdP rate limits if publicly accessible. OIDC lockouts must be enforced by the identity provider.
- Existing local `admin/admin` credentials are **rejected at startup** instead of silently running a weak emergency account. Rotate those on Phase 2 *before upgrading*. An existing database with no enabled local administrator likewise fails closed; it will not silently promote an unassigned user. New empty DBs generate a cryptographically random one-time local admin password (or require a provided password of at least 12 characters); rotate and securely delete `data/bootstrap-admin-password`.

### Existing PostgreSQL database `clamv`

1. Stop the web client and take a consistent DB backup before upgrading: `pg_dump -Fc -h 192.168.68.112 -U YOUR_DB_USER -d clamv -f clamv-phase2-before-phase3.dump`. Back up `./conf` and `./data` too. Store the DB dump outside the container and restrict access.
2. Confirm `flyway_schema_history` currently reports successful V1 and V2, and that at least one enabled secure local admin exists. **Do not** set `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true`; that flag is only for intentionally baselining old unmanaged databases.
3. Build the full source: `docker compose build --no-cache clamav-web-client`. Startup should apply V3 automatically. Verify `flyway_schema_history` includes V3, and that `external_identities` exists. Local sign-in and scan history should be unchanged.
4. Keep OIDC/LDAP disabled until local-login and post-restart scanning tests pass. Enable **one** provider first and verify role mapping before enabling the second.
5. Rollback: stop Phase 3, **restore the consistent Phase 2 PostgreSQL backup** plus matching files/config and Phase 2 image. Do not attempt an ad-hoc Flyway `clean`, downgrade, or table drop on production data.

### LDAP/Active Directory (optional)

Use a DNS hostname with a certificate trusted by the Java runtime (public CA or import the enterprise CA into a mounted JVM truststore). **Self-signed-untrusted, LDAP without StartTLS, and disabling certificate/hostname verification are not supported.** A simple working LDAPS example:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_IDENTITY_LDAP_ENABLED: "true"
      CLAMAV_IDENTITY_LDAP_URL: "ldaps://ldap.example.com:636"
      CLAMAV_IDENTITY_LDAP_BASE_DN: "dc=example,dc=com"
      CLAMAV_IDENTITY_LDAP_MANAGER_DN: "cn=clamav-bind,ou=service,dc=example,dc=com"
      CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE: "/run/secrets/ldap_bind_password"
      CLAMAV_IDENTITY_LDAP_USER_SEARCH_BASE: "ou=people"
      CLAMAV_IDENTITY_LDAP_USER_SEARCH_FILTER: "(uid={0})"
      CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_BASE: "ou=groups"
      CLAMAV_IDENTITY_LDAP_GROUP_MEMBER_ATTRIBUTE: "member"
      CLAMAV_IDENTITY_LDAP_GROUP_NAME_ATTRIBUTE: "cn"
      CLAMAV_IDENTITY_LDAP_NESTED_GROUPS: "true"
      CLAMAV_IDENTITY_LDAP_MAX_NESTED_DEPTH: "3"
      CLAMAV_IDENTITY_LDAP_CONNECT_TIMEOUT_MILLIS: "5000"
      CLAMAV_IDENTITY_LDAP_READ_TIMEOUT_MILLIS: "5000"
      CLAMAV_IDENTITY_LDAP_REFERRALS: "ignore"
      CLAMAV_IDENTITY_LDAP_POOLED: "false"
      CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER: "ClamAV-Readers"
      CLAMAV_IDENTITY_LDAP_GROUPS_OPERATOR: "ClamAV-Operators"
      CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN: "ClamAV-Administrators"
    secrets: [ldap_bind_password]
secrets:
  ldap_bind_password:
    file: ./.secrets/ldap_bind_password
```

For **Active Directory**, use `CLAMAV_IDENTITY_LDAP_USER_SEARCH_FILTER: "(sAMAccountName={0})"`, `CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_BASE` to match your directory, and ensure the bind account can read group membership. Nested group lookup walks `member` DN links to a configured depth (maximum 8, 256 groups), not `tokenGroups`. AD-specific trust/forest boundaries require deployment-specific testing. LDAPS connection pooling is optional; StartTLS **forces native pooling off**.

For StartTLS use `ldap://ldap.example.com:389`, `CLAMAV_IDENTITY_LDAP_START_TLS=true`. Never set StartTLS and `ldaps://` together. A secure manager password must be mounted from a secret file or environment (the file is preferred), not committed to the repository.

### OIDC: Authentik / Keycloak (optional)

The four standard Spring OIDC variables are also supported, with configurable
scopes (default `openid,profile,email,groups`). A complete standard registration
enables SSO unless explicitly disabled; existing custom settings and admin-saved
settings retain precedence. See [OIDC_CONFIGURATION_COMPATIBILITY.md](OIDC_CONFIGURATION_COMPATIBILITY.md).

Create a **confidential** authorization-code client in your IdP. Set the allowed redirect URI to the public HTTPS origin + `/login/oauth2/code/clamav` and allowed post-logout URL to your login page. Configure claims/scope mappings to expose exact group names; do not assume `groups` exists by default. Use the IdP's exact issuer URL from its `/.well-known/openid-configuration` response (must be HTTPS). No insecure issuer, arbitrary issuer fallback, or automatic email linking.

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_IDENTITY_OIDC_ENABLED: "true"
      CLAMAV_IDENTITY_OIDC_ISSUER: "https://auth.example.com/application/o/clamav/"
      CLAMAV_IDENTITY_OIDC_CLIENT_ID: "YOUR_PUBLIC_CLIENT_ID"
      CLAMAV_IDENTITY_OIDC_CLIENT_SECRET_FILE: "/run/secrets/oidc_client_secret"
      CLAMAV_IDENTITY_OIDC_SCOPES: "openid,profile,email,groups"
      CLAMAV_IDENTITY_OIDC_REGISTRATION_ID: "clamav"
      CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM: "groups"
      CLAMAV_IDENTITY_OIDC_GROUPS_VIEWER: "ClamAV Viewers"
      CLAMAV_IDENTITY_OIDC_GROUPS_OPERATOR: "ClamAV Operators"
      CLAMAV_IDENTITY_OIDC_GROUPS_ADMIN: "ClamAV Administrators"
      CLAMAV_IDENTITY_OIDC_BUTTON_LABEL: "Sign in with Authentik"
      CLAMAV_IDENTITY_OIDC_LOGOUT_MODE: "local"
      SERVER_FORWARD_HEADERS_STRATEGY: "framework"
      SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
    secrets: [oidc_client_secret]
secrets:
  oidc_client_secret:
    file: ./.secrets/oidc_client_secret
```

**Authentik**: set `CLAMAV_IDENTITY_OIDC_ISSUER` to the provider issuer (usually `https://auth.example.com/application/o/<slug>/`) and configure its group scope mapping. **Keycloak**: issuer typically `https://keycloak.example.com/realms/<realm>`; configure a groups mapper or set `CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM: "realm_access.roles"` if using realm roles. Never use broad `admin` group names without explicit intended authorization.

OIDC discovery is delayed until first SSO login, so a temporarily offline IdP **cannot stop the service from booting**. Spring Security performs state, nonce, ID-token issuer/signature/audience validation, confidential authorization-code exchange and session handling. Application claim mapping then denies identities without configured groups.

`CLAMAV_IDENTITY_OIDC_LOGOUT_MODE=local` only invalidates the local session; `provider` uses Spring's OIDC RP-initiated logout when supported. Always log out and back in after changing IdP groups.

For NGINX reverse proxy, forward the original public host/protocol **only from a trusted proxy**; sanitize incoming `Forwarded`/`X-Forwarded-*` headers at the edge, and use `SERVER_FORWARD_HEADERS_STRATEGY=framework` only in that deployment. Do not enable blindly for a publicly reachable backend, where spoofed headers can poison redirect URIs. Set a secure session cookie in HTTPS deployments.

### Verification checklist

1. **Both providers disabled**: original local login and API Bearer tokens work; SSO/LDAP options are hidden; `/login/ldap` is not an active authenticator.
2. **LDAP only**: valid read/operator/admin group members receive only intended roles; LDAP user with no group cannot log in; invalid password fails; nested groups work; directory offline fails quickly without preventing local login.
3. **OIDC only**: Authentik and Keycloak authorization-code login, state/nonce handling and exact issuer enforcement; users without mapped claims fail; distinct issuers with identical `sub` do not collide; logout and re-login refresh roles.
4. **Both enabled**: identical username/email across local, LDAP and OIDC produces separate principals; local admin works when both IdPs are unreachable; HTTP Basic and Bearer tokens still need HTTPS and are not silently converted to sessions.
5. **Lockout/CSRF**: after five incorrect local or LDAP attempts the corresponding username+IP is temporarily blocked, without locking out the other provider; browser POST without CSRF is rejected; logout invalidates session.
6. **Database**: Flyway V3 succeeds from empty schema and from Phase 2 V2; legacy users, roles, audit and scans remain; external subjects are written only to `external_identities` with **no password hash**.

Automated tests cover role mapping, claim parsing, identity separation, H2 V3 migration, password throttling, JPA external identity persistence, and startup/local login with a deliberately unreachable OIDC issuer. **Real Authentik/Keycloak and directory TLS integration have not been executed here**: run the above end-to-end matrix against your configured IdPs before declaring enterprise authentication production-ready. Per-instance lockouts and sessions are not a distributed HA solution; Phase 2 multi-replica limitations remain.
