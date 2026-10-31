# Bearer authentication behind a TLS-terminating proxy — issue #11

Reverse proxy HTTPS recognition is enabled in the application source by default:

```properties
server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:framework}
```

A normal build packages this configuration into the JAR:

```bash
docker build --pull -t clamav-web-client:2.0.0-local .
```

No helper script, `--trusted-proxy` option, custom build argument or environment
opt-in is needed. Nginx, Traefik, HAProxy and Kubernetes Ingress can terminate
TLS and forward HTTP to application port 8080. Spring processes RFC 7239
`Forwarded` and legacy `X-Forwarded-*` headers before the security filters, so
`request.isSecure()` reflects the original HTTPS connection.

The Dockerfile also contains issue #10's `USER 10001:10001` fix. Both changes
apply when building directly from this complete source archive.

## Existing runtime settings

The root Compose, standalone/remote examples and example environment file all
default to `framework`. If an older deployment explicitly supplies
`SERVER_FORWARD_HEADERS_STRATEGY=none` or
`--server.forward-headers-strategy=none`, remove that override to use the new
default. Higher-priority environment settings, command-line arguments and
external Spring configuration still apply normally.

An explicit `SERVER_FORWARD_HEADERS_STRATEGY=framework` remains supported for
compatibility. The reverse-proxy Compose override and helper's legacy
`--trusted-proxy` option are optional ways to set the already-enabled default.
They do not patch source code or add fixes to an image.

## Proxy trust boundary

With `framework` enabled, forwarded headers are trusted input. Make the
proxy/Ingress the exclusive path to the backend, using the appropriate
firewall, loopback binding or Kubernetes NetworkPolicy. Spring does not itself
prove that a request sender is your proxy.

The trusted edge must remove incoming `Forwarded` and `X-Forwarded-*` values and
write the correct scheme/host/client information. For a simple Nginx HTTPS
virtual host, put the following in its proxy location:

```nginx
proxy_pass http://127.0.0.1:8080;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header Forwarded "";
proxy_set_header X-Forwarded-Proto https;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Port 443;
proxy_set_header X-Forwarded-For $remote_addr;
proxy_set_header X-Forwarded-Prefix "";
proxy_set_header X-Forwarded-Ssl "";
```

Keep the existing `/ws/clamdtop` WebSocket Upgrade/Connection directives. Adjust
the backend address/port for your deployment. The example assumes Nginx is the
TLS edge; multi-proxy installations must establish their own trust chain.

## Kubernetes

The built image declares numeric UID/GID 10001, and proxy support is packaged
in its JAR; no forwarding environment variable is required. For example:

```yaml
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  allowPrivilegeEscalation: false
  capabilities:
    drop: [ALL]
```

This is the application container's security context. Provision writable
`/app/conf` and `/app/data` volumes using an appropriate pod `fsGroup` or storage
ownership policy; see [non-root deployment](NON_ROOT_CONTAINER.md). Configure
the Ingress to forward HTTPS and restrict backend access to the Ingress.

For pre-existing configuration files, group access from `fsGroup` alone is not
enough for the application to change owner-only file modes. Provision those files
with UID 10001 ownership as described in the non-root guide.

## Direct access opt-out and token checks

An installation that does not use a trusted proxy can explicitly ignore all
forwarded headers:

```yaml
environment:
  SERVER_FORWARD_HEADERS_STRATEGY: none
```

In that opt-out mode a direct HTTP client cannot satisfy the HTTPS check by
sending `X-Forwarded-Proto: https` or `Forwarded: proto=https`. Configure direct
HTTPS to use Bearer credentials without a proxy. Spring's `native` mode remains
available for deliberate container-specific configurations.

The Bearer filter always requires `request.isSecure()`. Plain HTTP without a
forwarded HTTPS scheme is rejected, as are forwarded HTTP, invalid or revoked
tokens and insufficient scopes. The `tls_required` response identifies the
forwarded HTTPS scheme and runtime overrides as troubleshooting points.

## Verify through the deployment HTTPS endpoint

Public DNS is not required. A LAN IP or local DNS name works when the client
trusts the certificate and the address matches its certificate SAN. For a local
CA or a directly self-signed certificate, use curl's `--cacert` or the CLI's
`--ca-file`; see [local HTTPS verification](UPDATE_AND_VERIFY.md#3-verify-https-through-the-reverse-proxy).
Use a fake token for a diagnostic that skips certificate verification; do not
disable verification for real credentials.

Use a token with `health:read` and call:

```bash
curl -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  https://clamav.example.com/api/v1/health/live
```

Use the external HTTPS URL in `clamctl config set-server` and MCP configuration.
The HTTP hop from the trusted TLS edge to the backend is not a client URL.

## Regression coverage

`ApiTokenProxyTlsIntegrationTest` starts real embedded Tomcat with **no
forwarding property override** and tests the configuration packaged by a normal
build. It verifies both header formats, plain/forwarded HTTP rejection, invalid
tokens, scopes and revocation. `ApiTokenDirectTlsIntegrationTest` explicitly
selects `none` and verifies that both forwarded-header formats are ignored.

Run `mvn -B -ntp verify`. Results and Docker-runtime limitations are recorded in
[proxy TLS validation](security/proxy-tls-validation.json). See also
[Spring Boot proxy guidance](https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)
and [Spring forwarded-header security](https://docs.spring.io/spring-framework/reference/web/webmvc/filters.html#filters-forwarded-headers).
