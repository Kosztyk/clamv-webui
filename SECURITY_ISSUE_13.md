# Security issue #13 — forwarded-header trust

Direct requests now ignore forwarded headers by default. A client cannot change its socket IP address or make HTTP pass HTTPS gates with X-Forwarded-* or RFC Forwarded headers. Application version remains 2.1.0; no database migration is needed.

Build this complete source from its extracted root:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

For an existing deployment, retain the existing database URL, credentials, mounts and truststore settings. Apply `SERVER_FORWARD_HEADERS_STRATEGY: none` for direct access. The packaged root Compose replaces the uploaded installation's private endpoints and database credentials with environment placeholders; do not overwrite a working deployment Compose without carrying its values across. Use docker-compose.minimal.yaml for a fresh Configuration Center installation.

For TLS termination, explicitly select native forwarding and set SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES to the proxy's actual socket IP/CIDR. The default (?!) trusts nobody, including in native mode. Replace any existing explicit framework override, including a saved GUI value, then restart/recreate the Web UI. Follow docs/REVERSE_PROXY_TLS.md for the complete proxy settings, edge header replacement and migration steps.

The standalone recovery listener now also checks native mode and the proxy peer allowlist before treating a forwarding header as HTTPS. Explicit HTTP confirmation and recovery lockout remain enforced for direct clients.

Validation: Maven verify succeeded (628 tests: 623 passed, 5 existing UnixClamdClientTest tests skipped by their assumptions, zero failures/errors). All 9 packaged recovery/forwarding checks, 36 Configuration Center checks and 11 deployment-helper scenarios passed. Docker image execution was not performed. See docs/security/forwarded-header-trust-validation.json for exact coverage and limits.

CHANGED_FILES.md lists every changed/new source file relative to the uploaded archive. Runtime databases, uploads, credentials, encryption keys, recovery tokens, local certificates, logs and old generated JARs are excluded from this source package. Keep your existing conf/data volumes when deploying.
