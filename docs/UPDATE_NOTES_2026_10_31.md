# 31 October 2026 — Configuration Center, runtime, proxy and webhook update

The current full-source release is **2.1.0**; see [release notes](RELEASE_NOTES_V2_1_0.md). This source update includes issues
[#10](https://github.com/Kosztyk/clamv-webui/issues/10),
[#11](https://github.com/Kosztyk/clamv-webui/issues/11) and
[#12](https://github.com/Kosztyk/clamv-webui/issues/12), retaining the previous
application features, migrations, CLI/MCP distribution and security updates.

Build directly from the repository root:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

No deployment helper, build argument or `--trusted-proxy` flag is required.
This source release does not itself publish a Docker Hub image or GitHub release.

## Configuration Center edition

This full-source edition adds the application Configuration Center, protected database-independent setup/recovery, encrypted persisted credentials, authoritative Compose overrides and live/restart review. Existing identity UI precedence has changed: explicit deployment values now win. See [setup and compatibility](CONFIGURATION_CENTER.md), [every central field](CONFIGURATION_REFERENCE.md) and [new validation](CONFIGURATION_CENTER_VALIDATION.md). Application version is 2.1.0 and the Flyway schema remains V4; no new SQL migration or baseline is required for this feature.

## Direct setup correction

Setup and Configuration Center support direct HTTP on the published application port with explicit GUI confirmation for unencrypted credential submission. Neither nginx nor a homelab address is required. The root Compose no longer preconfigures a REST URL or application environment overrides. Invalid recovery tokens return 401; a temporary retry limit returns 429 with Retry-After. Machine API Bearer authentication still requires HTTPS.

## Changes

| Area | Result |
|---|---|
| Container identity (#10) | Numeric `USER 10001:10001`; writable image-owned `conf`/`data`; root-owned, read-only application JAR; native ClamdTop retained |
| Reverse-proxy TLS (#11), trust boundary (#13) | Forwarded headers default to `none`; TLS termination requires explicit `native` mode and an allowlist of proxy peers |
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
- Issue #13 supersedes the earlier automatic proxy trust: direct access uses
  `none`. Proxy deployments must opt into `native` and supply the proxy allowlist.
  Replace older explicit `framework` overrides. The proxy must overwrite
  forwarded headers and be the exclusive path to the backend; see
  [migration instructions](REVERSE_PROXY_TLS.md).
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

Earlier v2.0.0 validation reported 307 Maven tests (302 passed, 5 skipped), 14
executable-JAR checks and a runtime dependency advisory query. The 82 focused
webhook tests also passed on the operator's Docker test host. Historical reports
describe their original test run; current v2.1.0 checks are recorded in
[Configuration Center validation](CONFIGURATION_CENTER_VALIDATION.md).
