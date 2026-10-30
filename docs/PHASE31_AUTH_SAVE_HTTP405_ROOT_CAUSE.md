# v2.0.0 Phase 3.1 — Authentication Save HTTP 405 root-cause fix

## Root cause (reproduced in Chromium)

The Authentication page's JavaScript used:

```javascript
const endpoint = submitter?.formAction || form.action;
```

`HTMLButtonElement.formAction` is a reflected property whose value for a Save
button **without** an explicit `formaction` attribute is the current page URL,
not the containing form's action URL. As a result, clicking **Save LDAP settings**
(or **Save OIDC settings**) sent `POST /admin/settings/authentication`.
The Spring MVC endpoint at that URL has only a GET handler, so it correctly
responded with `405 Method Not Allowed`. LDAP was not involved in this 405.

The Test buttons already had explicit `formaction` attributes, explaining why
Test reached the appropriate controller while Save did not.

## Fix

- The JavaScript honors `submitter.formAction` **only if** the submit button
  explicitly carries the `formaction` attribute; otherwise it uses `form.action`.
- Both Save buttons now have an explicit, Thymeleaf-generated `formaction`:
  `/admin/settings/authentication/ldap/save` and
  `/admin/settings/authentication/oidc/save`.
- The shell includes a versioned authentication-management.js asset URL, so the
  browser will request the new file rather than reuse the previous cached copy.
- Existing CSRF, HTTPS-only secret submission, write-only secret handling,
  persistence and authorization checks were **not changed**.

## Regression coverage

- `scripts/test_authentication_form_routes.py` loads the **real** HTML template
  (with Thymeleaf URL placeholders materialized) and the production JavaScript
  into Chromium. It simulates both provider Test and Save responses, checks that
  the browser emits exactly the required URLs, tests the old missing-formaction
  case, preserves form fields after failure, and verifies the browser's HTTP
  secret-submission guard. The network is mocked: this is **not** an LDAP or
  PostgreSQL integration test.
- `Phase31AuthenticationUiTest.authenticationButtonsRenderExplicitSaveAndTestPostTargets`
  covers Thymeleaf-rendered form URLs and both Spring MVC AJAX POST endpoints.
  Execute in Docker or a Maven/JDK 21 environment; it was not executable in
  this artifact-building environment.

## Deployment without changing the existing environment

The included `docker-compose.yml` is a **sample** and may use H2 and port 8080.
Do **not** replace the active Compose file on a PostgreSQL installation using
port 8081. Instead, update the application source or merge application sources
into the currently deployed Compose project. Preserve all of its PostgreSQL,
NGINX/HTTPS and LDAP environment variables, `conf` and `data` volume mounts.

After backing up database and volumes, rebuild from the application's Compose
working directory:

```bash
docker compose build --no-cache clamav-web-client
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
```

Browse to `https://192.168.68.112/login`, sign in as local admin and open
Administration > Authentication. Saving and testing should now send requests
ending with `/ldap/save` or `/ldap/test`, respectively (similarly for OIDC).
Use browser DevTools Network to verify the exact request URL and JSON response.

**Other blocker:** LDAP log `ssl/tls alert certificate unknown` indicates a
separate Java truststore/CA problem. With **Enable LDAP** selected, Save also
requires a successful LDAP test before activating the provider. Until that CA
is trusted by the ClamAV Java runtime, the corrected Save may legitimately
return a JSON validation failure, rather than HTTP 405. For test environments,
settings can first be saved with the provider disabled; enable after TLS
trust and binding are verified.
