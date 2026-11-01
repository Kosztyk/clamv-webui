# v2.0.0 — Authentication form persistence and responsive shell fix

> **Current v2.1.0 configuration:** global application fields are edited only in Configuration Center. Former Settings/Authentication bookmarks redirect there; old provider, metrics and observer write routes are retired. Use Test connection, Review changes and Apply; restart provider changes when indicated. Diagnostic/client pages remain read-only. Earlier form behavior described below is historical. See [the consolidation audit](CONFIGURATION_CONSOLIDATION.md) and [current setup](CONFIGURATION_CENTER.md).

This is a source-preserving maintenance update to the Phase 3.1 application. It does not change Flyway migrations, the PostgreSQL schema, configured provider credentials, scan jobs, the API, or the ClamdTop implementation.

## What changed

- `Administration > Authentication`: LDAP/AD and OIDC/SSO Test and Save now use CSRF-protected same-origin JSON requests. They no longer reload the page. Test only probes the provider; **Test does not save**. Save persists validated settings to `auth_provider_settings` and activates them for new sign-ins. Failed requests keep the entire in-browser draft, including a newly typed secret. After a successful Save, a typed secret field is emptied, and the page indicates the secret is stored. After a successful Test, the secret remains in the form so a subsequent Save can persist it.
- Missing LDAP fields are listed explicitly. If `Enable LDAP login` is checked, an LDAPS or StartTLS URL, manager DN, base DN, user search filter, group search base, at least one explicit role group and a configured bind secret are required. Activation still requires a successful connection/TLS check. **To save a partially completed configuration, keep the provider disabled, then configure certificates, test, and enable it.**
- A new secret is **never** transmitted over plain HTTP. Use a trusted HTTPS reverse proxy and ensure Spring sees the original HTTPS scheme via `SERVER_FORWARD_HEADERS_STRATEGY=framework`. Password inputs are write-only; the server never returns their contents.
- Without JavaScript, the original redirects still function and retain non-secret draft fields after Test or validation failure. Secrets are never placed in flash messages or HTML.
- The PJAX navigation cache does not store the Authentication page, preventing a stale provider state from being shown after Save.
- Sidebar grows with the full menu and page. Footer sits at the bottom of the main column on short pages, follows the content on long pages, and is included on login and error pages. App version remains **2.0.0**.

## How to verify

1. Back up the existing PostgreSQL database and `conf` volume; build the updated source with `docker compose build --no-cache clamav-web-client` (this runs the Maven suite).
2. Open `https://YOUR_WEBUI/admin/settings/authentication`; expand LDAP / AD.
3. Enter a valid `ldaps://...` server URL, base DN, bind DN, user search filter `(uid={0})`, group search base (e.g. `ou=groups`), viewer group and bind password. Use the trusted LDAP CA certificate in the Java truststore.
4. Click **Test LDAP connection**. The page must not reload or clear the fields; it must explicitly say that Test did **not** save.
5. If Test fails, edit the field/certificate and retry. The existing typed fields remain visible. If validation reports a missing field, it must identify the field.
6. Click **Save LDAP settings**; expect a successful persistence message, the provider's saved indicator, and a cleared password input (without deleting the encrypted saved credential). If the enable checkbox is checked, the provider must pass the connection test to activate.
7. Reload the page and confirm the nonsecret settings persist, the saved-secret indicator remains set and the provider state is correct. Restart the container and verify again.
8. Verify the login page shows LDAP only when enabled and a local administrator can still log in with LDAP offline.
9. Repeat the draft/Test/Save sequence with OIDC and then compare the footer/sidebar alignment on a short and a long page.

## Important constraints

- A Test is not a Save. The submitted new secret is temporarily held only in the browser form; after a full reload it must be entered again unless already saved encrypted in the database.
- TLS trust is not relaxed. A failed directory bind, network timeout, or untrusted LDAP CA prevents enabling a provider; see `docs/PHASE3_ENTERPRISE_IDENTITY.md` and `docs/PHASE31_AUTH_MANAGEMENT.md`.
- Already-saved settings in PostgreSQL are preserved. No `SPRING_FLYWAY_BASELINE_ON_MIGRATE=true` is required for this update.
- Maven and live external LDAP/IdP verification must be executed on the Docker-enabled build host; static checks and browser layout tests alone are not sufficient to certify the authentication chain.
