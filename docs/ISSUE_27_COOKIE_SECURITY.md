# Issue #27 — Secure session and locale cookies

This change is based on the **v2.1.0beta2 full-source archive containing issues #25 and #26**. No code from those fixes is removed.

## Default (HTTPS production)

The packaged application now explicitly defines:

```properties
server.servlet.session.timeout=${SERVER_SERVLET_SESSION_TIMEOUT:30m}
server.servlet.session.cookie.secure=${SERVER_SERVLET_SESSION_COOKIE_SECURE:true}
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=${SERVER_SERVLET_SESSION_COOKIE_SAME_SITE:lax}
```

Both `JSESSIONID` and the `clamav_lang` locale cookie use `Secure`, `HttpOnly`, and `SameSite=Lax`. The locale cookie derives its `Secure` and `SameSite` policy from the same resolved settings, *not* from the backend request's `isSecure()` state; this addresses TLS-terminating proxies. Spring Security sends `Referrer-Policy: no-referrer` on protected and public MVC responses.

These options are **startup-bound**. Restart/recreate the Web UI container after changing them. NGINX must overwrite client-supplied forwarding headers and the backend must trust only its actual proxy peer(s). The trusted-proxy issue #13 protection stays unchanged.

## Docker Compose: HTTPS behind NGINX

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:v2.1.0beta2-issue27-local # build this source locally
    environment:
      SERVER_FORWARD_HEADERS_STRATEGY: "native"
      SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: "${TRUSTED_PROXY_CIDR:?set to actual proxy peer CIDR}"
      SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
      SERVER_SERVLET_SESSION_COOKIE_SAME_SITE: "lax"
      SERVER_SERVLET_SESSION_TIMEOUT: "30m"
```

This is an *environment snippet*, not a complete install: merge it into your existing Compose. The image reference is a **local build tag**, not an image published to Docker Hub. Build it from this source (`docker build -t clamav-web-client:v2.1.0beta2-issue27-local .`). Do not reuse another environment's proxy CIDR.

## Plain HTTP development **only**

For an intentionally direct-HTTP deployment on a trusted network, use **Setup & recovery → Direct HTTP configuration** and enable **Allow sign-in and configuration over HTTP on this trusted network (saved)**. This persists `server.servlet.session.cookie.secure=false`; the equivalent deployment override is `SERVER_SERVLET_SESSION_COOKIE_SECURE=false`. Secure cookies are not transmitted over ordinary HTTP by browsers, so leaving the secure default on prevents session-based login from sticking. HTTP provides no transport encryption; HttpOnly and SameSite=Lax remain enabled.

## Existing Configuration Center values

Previously saved `server.servlet.session.cookie.secure=false` values are **not overwritten** during upgrades. The direct-HTTP banner reflects the saved value, and Administration → Configuration Center → Network exposes **Require HTTPS for session cookies** without hiding it under Advanced settings. Re-enable HTTPS-only cookies when moving the deployment to HTTPS, then restart. A deployment override takes precedence over GUI configuration and can lock the field. SameSite stays Lax by default. `SameSite=None` must never be used without Secure enabled.

## Security headers and CSP

Referrer-Policy `no-referrer` is enabled via Spring Security. A blocking `script-src 'self'` CSP is **not** enabled yet because current templates still contain inline `<script>` and event-handler attributes (`onclick`/`onchange`); enforcing that policy before removing them would break functionality. Existing CSP settings on recovery/setup pages remain unchanged.

## Verification

Run the new automated tests (`CookieLocaleSecurityTest`, `CookieSessionHttpIntegrationTest`) together with the full Maven suite. Check actual HTTP response headers (using HTTPS at your proxy):

```bash
curl -sk -D - https://YOUR_CLAMAV_UI/login -o /dev/null | grep -iE 'set-cookie|referrer-policy'
curl -sk -D - 'https://YOUR_CLAMAV_UI/setLocale?lang=ro&returnTo=/login' -o /dev/null | grep -iE 'set-cookie|referrer-policy'
```

Expected session/locale attributes: `Secure; HttpOnly; SameSite=Lax`. The session cookie is issued when a session is created; the login form normally obtains a CSRF token and creates one. Proxy/Tomcat startup options and the optional HTTP-development override must be verified in the actual deployment.
