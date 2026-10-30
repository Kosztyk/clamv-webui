# ClamAV Web UI 2.0.0 — Phase 3.1 Authentication Management

## Migration / safety

1. Back up the existing PostgreSQL `clamv` database using `pg_dump -Fc` and back up mounted `./conf` and `./data`.
2. Upgrade on a copy first; Flyway adds `auth_provider_settings` in V4. Existing V1-V3 schemas and rows remain unchanged.
3. Keep `SPRING_FLYWAY_BASELINE_ON_MIGRATE=false`. Do not force a new baseline on existing migrated databases.
4. Restart with both providers off or with existing Docker bootstrap defaults; **local login is always available** and requires a strong password. Confirm local login and an end-to-end scan after restart.
5. In the UI, use **Administration → Settings → Manage Authentication** (or the Authentication sidebar item). The Local/Recovery guidance remains visible. LDAP/AD and OIDC/SSO are individually expandable from their buttons; enabled providers open automatically.

## Precedence, persistence, and immediate changes

LDAP accepts both the original configuration and the issue #3 `SECURITY_LDAP_*`
aliases. Its admin form includes **Group search filter**. Details and filter
placeholder semantics are in [LDAP configuration compatibility](LDAP_CONFIGURATION_COMPATIBILITY.md).

* Environment variables `CLAMAV_IDENTITY_LDAP_*` and `CLAMAV_IDENTITY_OIDC_*` are **bootstrap/fallback values**, used until a particular provider is first saved in the UI. After saving, the provider's UI configuration overrides the Docker values across restarts.
* Each provider is independent. Saving a disabled provider retains its settings but hides that provider's login option.
* Providers are registered with Spring Security at startup without connecting to them. LDAP's authentication provider is built only on login; OIDC issuer discovery is lazy. Settings changes increment a revision and replace registrations for **new** sign-ins without restart. Existing sessions retain their authorities until they log in again.
* This is **single-replica configuration**. Multiple replicas require a shared key and coordinated settings refresh before HA can be claimed.
* The fixed OIDC registration ID is `clamav` and callback is `https://YOUR-WEBUI-DOMAIN/login/oauth2/code/clamav`. The provider issuer must be HTTPS and trusted by the JVM. Set `SERVER_FORWARD_HEADERS_STRATEGY=framework` only behind a trusted reverse proxy that strips and rewrites user-supplied forwarding headers. Use `SERVER_SERVLET_SESSION_COOKIE_SECURE=true` for TLS access.

## Credentials and rotation

* Password/client secret inputs are write-only. Blank means **keep the existing secret**; replacing means new AES-256-GCM ciphertext is stored in PostgreSQL. No secret is rendered in the page or written into logs/audit.
* The encryption key is stored separately in `./conf/auth-settings.key` (`/app/conf/auth-settings.key` in the container) with owner-read/write permissions. It is created on first secret save. Back it up with the database. Losing it prevents decrypting saved secrets; restore it, or recover by restoring database/key backup. Do not delete it on upgrade.
* Alternatively set `CLAMAV_AUTH_SETTINGS_KEY` to a base64-encoded 32-byte key, or `CLAMAV_AUTH_SETTINGS_KEY_FILE` to a persistent/secret-mounted file holding that key. Keep it stable across restarts and rotate only in a coordinated recovery procedure.
* Secret changes through the UI are rejected unless `HttpServletRequest.isSecure()` reports true. Access the application over HTTPS behind a trusted proxy and configure forwarded headers correctly. The legacy HTTP admin pages remain functional without exposing secrets.
* LDAP service passwords and OIDC client secrets from Docker secrets still work as bootstrap settings. Do not keep plaintext secrets in Docker Compose.

## Activation and tests

* Test LDAP by making a TLS-protected manager bind and reading the root context. Enter user search base/filter, group search base, member attribute, optional nested-group depth and exact comma-separated viewer/operator/admin groups. Do not enable LDAP over unprotected LDAP connections.
* Test OIDC via issuer discovery over trusted HTTPS. Discovery **does not prove** that a client ID/secret is valid; complete one real Authentik or Keycloak sign-in to test the authorization-code exchange, session state, nonce and group claims.
* Saving with Enable checked automatically probes the provider first. If verification fails, the **previous working** settings remain active. Saving disabled settings is allowed offline.
* Unmapped external group members are denied. `ROLE_ADMIN` requires an explicitly listed group and role grants are audited; identities use source-qualified immutable principals and are not provisioned into `app_users`.
* Local emergency login cannot be disabled through this page. API tokens remain separate from interactive cookie auth, and POST forms require CSRF.
* Audit events: `AUTH_PROVIDER_UPDATED`, `AUTH_PROVIDER_TEST`, `EXTERNAL_LOGIN` and `EXTERNAL_ADMIN_GRANTED`.

## Acceptance checklist

1. Verify Flyway V4 in PostgreSQL and persistence across restart, with no changes to legacy scan jobs/roles/audit.
2. Confirm provider **configuration panels** are initially collapsed when disabled, while their toggle buttons and Local/Recovery remain visible. Enabled provider panels must automatically expand. Confirm the local administrator remains usable during a simulated external IdP outage.
3. Test LDAP LDAPS/StartTLS with a trusted certificate, wrong bind secret, wrong user password, nested groups, and unmapped group denial.
4. Test Authentik and Keycloak issuer discovery and full SSO sign-in with valid/invalid groups, logout, and reverse-proxy forwarded host.
5. Confirm viewer/operator/admin permissions, audit entries, CSRF enforcement, and API bearer-token behavior.
6. Back up encryption key + database, restart, and verify that the new provider configuration persists and still authenticates.
7. Run `docker compose build --no-cache clamav-web-client` (Maven tests inside build), then `mvn -B -Pdb-integration verify` on a Docker-enabled host for PostgreSQL/MySQL/MariaDB migration tests. Real LDAP/OIDC end-to-end tests require reachable IdPs and are not validated by static checks alone.

## Versioning

**Application version: 2.0.0**, carried by Maven's artifact version, the local Compose image tag, the login page, and the common footer. This is a product version, distinct from schema migration version **V4** and security protocol version **/api/v1**. The app version remains 2.0.0 across this seven-request implementation scope unless explicitly changed.

## Authentication page disclosure behavior

* The **Local / Recovery** section stays visible at all times on the Authentication page.
* The **LDAP / AD** and **OIDC / SSO** controls remain visible even when those providers are disabled. Clicking each control independently expands/collapses its corresponding settings form without changing or saving any configuration.
* A provider whose **effective enabled setting** is `true` (from saved UI configuration, or Docker fallback) automatically opens on page load. If both are enabled, both forms are visible; if neither is enabled, neither form is initially displayed.
* The Test/Save actions retain their existing CSRF and server-side checks. The redirect fragment (`#ldap` or `#oidc`) reopens the relevant form after a successful or failed submission so the administrator can review the message.
* The page also works after PJAX sidebar navigation because its disclosure behavior is initialized on the existing `clamav:page-ready` event. The application version remains **2.0.0**.
