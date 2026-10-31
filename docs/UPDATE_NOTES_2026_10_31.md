# 31 October 2026 — runtime, proxy and webhook update

Application version remains **2.0.0**. This source update includes issues
[#10](https://github.com/Kosztyk/clamv-webui/issues/10),
[#11](https://github.com/Kosztyk/clamv-webui/issues/11) and
[#12](https://github.com/Kosztyk/clamv-webui/issues/12), retaining the previous
application features, migrations, CLI/MCP distribution and security updates.

Build directly from the repository root:

```bash
docker build --pull -t clamav-web-client:2.0.0-local .
```

No deployment helper, build argument or `--trusted-proxy` flag is required.
This is a source update; it does not itself publish a registry image or a new
versioned release.

## Changes

| Area | Result |
|---|---|
| Container identity (#10) | Numeric `USER 10001:10001`; writable image-owned `conf`/`data`; root-owned, read-only application JAR; native ClamdTop retained |
| Reverse-proxy TLS (#11) | `server.forward-headers-strategy` defaults to `framework`; HTTPS forwarded by the trusted edge is recognized before `/api/v1` Bearer authentication |
| Notification isolation (#12) | Two dedicated delivery workers and a queue of 100 pending events; network latency does not occupy scan workers |
| Delivery bounds (#12) | Defaults: 5s connect, 10s read, 5s DNS, 20s total active delivery deadline |
| Webhook address policy (#12) | Private destinations blocked by default; explicit internal opt-in; metadata/link-local protection, validated DNS addresses and disabled redirects |
| Transport dependencies | Apache HttpClient 5.6.5, HttpCore 5.4.4 and MariaDB JDBC 3.5.11; see the dependency validation report for its scope |

## Deployment compatibility

- Keep your existing Compose settings, database, identity encryption key,
  endpoints, accounts and persistent data. These changes do not require a schema
  baseline or database reset. Existing v1-to-v2 migrations still require their
  own documented schema review and backup.
- Existing mounted `conf` and `data` files must be accessible to UID/GID 10001.
  Files whose private permissions the app sets also need UID 10001 ownership.
  Image ownership does not replace host/PVC ownership. See
  [the permission migration](NON_ROOT_CONTAINER.md).
- No new environment fields are required to enable the fixes. Remove an old
  explicit `SERVER_FORWARD_HEADERS_STRATEGY=none` override to use proxy support.
  The trusted proxy must overwrite forwarded headers and be the exclusive path
  to the backend.
- To use an internal webhook receiver, explicitly set
  `CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES=true`. Metadata and other prohibited
  destinations remain blocked. The UI and private ClamD/SQL connections are
  outside this outbound webhook policy.
- Webhooks remain best effort: queue overflow drops new notifications with a
  warning, and shutdown may discard pending events. There is no durable outbox,
  automatic retry or guaranteed delivery order.

## Verification

[Update and verify](UPDATE_AND_VERIFY.md) contains the complete deployment and
testing procedure. [Field validation](FIELD_VALIDATION.md) records reported
Kubernetes Prometheus/LLDAP results and local Docker checks without treating
those reports as coverage of every deployment.

The supplied source reports 307 Maven tests (302 passed, 5 skipped), 14
executable-JAR checks and a runtime dependency advisory query. The 82 focused
webhook tests also passed on the operator's Docker test host. Historical reports
describe their original test run; current publication checks are recorded in
`security/repository-update-validation.json`.
