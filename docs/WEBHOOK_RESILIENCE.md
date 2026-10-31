# Webhook delivery resilience and address policy — issue #12

Direct scan notifications run on a dedicated bounded executor. Slow or failed
receivers cannot occupy the scan execution workers or change a completed scan's
result. All defaults below are part of the application source and are included
by a normal build:

```bash
docker build --pull -t clamav-web-client:2.0.0-local .
```

No helper script or build flag is required. The archive also retains issue
#10's non-root runtime, issue #11's default forwarded HTTPS support, and the
previous dependency/security fixes.

## Enable notifications in the UI

Open **Admin → Settings → Webhook notifications**, select **Enabled**, paste a
receiver URL and save. The URL remains write-only: a blank replacement retains
the saved URL, and the Remove checkbox deletes it and disables delivery.
Only administrators can change the URL; existing CSRF protections remain.

The panel shows the configured connection/response timeouts and whether server
policy permits internal receivers. Hostname DNS is checked during actual
delivery, not while saving the settings form. A hostname that later resolves
to a prohibited address is not contacted. Numeric private/metadata destinations
are also rejected when saving or re-enabling them.

Generic receivers retain the existing JSON fields, including nullable scan
outcome fields. Discord keeps its adapted message, disabled mentions and HTTPS
requirement. Payloads can contain target paths, submitter names, signatures,
errors and quarantine paths, so choose receivers accordingly. These settings
apply to managed scans submitted through the Web UI/API/watcher. Webhooks on a
separate external REST scanner remain controlled by that scanner.

## Built-in defaults and deployment controls

| Environment variable | Spring property (`clamav.webhook.` prefix) | Default | Purpose |
|---|---|---|---|
| `CLAMAV_WEBHOOK_CONNECT_TIMEOUT` | `connect-timeout` | `5s` | TCP connection and connection-pool acquisition bounds |
| `CLAMAV_WEBHOOK_READ_TIMEOUT` | `read-timeout` | `10s` | Socket/response timeout |
| `CLAMAV_WEBHOOK_DNS_TIMEOUT` | `dns-timeout` | `5s` | Maximum wait for a DNS answer |
| `CLAMAV_WEBHOOK_DELIVERY_TIMEOUT` | `delivery-timeout` | `20s` | Cancels an active delivery, including long or trickling responses |
| `CLAMAV_WEBHOOK_WORKERS` | `workers` | `2` | Dedicated webhook worker count, independent of `app.concurrentScans` |
| `CLAMAV_WEBHOOK_QUEUE_CAPACITY` | `queue-capacity` | `100` | Maximum pending notifications, excluding active deliveries |
| `CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES` | `allow-private-addresses` | `false` | Explicit operator opt-in for internal receiver addresses |

Durations must be from 1 millisecond to 5 minutes. DNS timeout must not exceed
the delivery timeout. Worker count is limited to 1–8 and queue capacity to
1–1000. Invalid values fail application startup instead of enabling infinite
timeouts or an unbounded executor.

These are **server deployment settings**, loaded at startup. They do not belong
in `conf/clamav-web-client.properties`, which stores the UI's `app.webhook.*`
enable/URL settings. Recreate the UI container after changing deployment values.
The settings form cannot override server address policy.

## Internal receiver example

Public webhooks work with the defaults. To deliberately notify a private LAN
receiver, add the following to the existing Web UI Compose service:

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES: "true"
      CLAMAV_WEBHOOK_CONNECT_TIMEOUT: "5s"
      CLAMAV_WEBHOOK_READ_TIMEOUT: "10s"
      CLAMAV_WEBHOOK_DNS_TIMEOUT: "5s"
      CLAMAV_WEBHOOK_DELIVERY_TIMEOUT: "20s"
      CLAMAV_WEBHOOK_WORKERS: "2"
      CLAMAV_WEBHOOK_QUEUE_CAPACITY: "100"
```

Keep the service's existing image, database configuration, ports and volumes.
Then recreate that service and save its internal receiver URL in Admin Settings:

```bash
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
```

The repository's root Compose and standalone/remote examples also interpolate
these variables from `.env`. An explicit override example is provided in
[`examples/compose/webhook-policy.override.yaml`](../examples/compose/webhook-policy.override.yaml).
For Kubernetes, use the same environment names in the application container.

## Address and protocol safeguards

- Default policy rejects loopback, private IPv4, IPv6 unique-local/site-local,
  shared-address space and special/non-routable ranges. Abbreviated IPv4,
  IPv4-mapped IPv6 and mixed public/private DNS answers are checked too.
- Internal opt-in permits private/shared/loopback receivers. Unspecified,
  link-local, multicast, reserved and listed cloud metadata/platform addresses
  remain denied, including `169.254.169.254`, `169.254.170.2`, `fd00:ec2::254`,
  `100.100.100.200` and `168.63.129.16`.
- The custom resolver validates every DNS address that a new connection might
  use and passes those resolved addresses directly to the HTTP connection
  manager. It does not validate once and then perform an unchecked DNS lookup.
  DNS threads and their queue are bounded even if an OS lookup ignores interruption.
- HTTP redirects and automatic retries are disabled. A 3xx response is a failed
  delivery, so receiver credentials/payloads cannot be redirected elsewhere.
  TLS uses normal certificate/hostname verification. Cookies are disabled.
  No HTTP proxy is configured by the webhook client.
- Response bodies are not buffered or drained; only a 2xx status counts as an
  accepted notification. Logs omit receiver URLs, tokens, payloads, response
  bodies and exception messages.

Use network egress policy as an additional boundary; application address policy
cannot identify every custom metadata service or unusual infrastructure route.
Private receiver opt-in deliberately permits communication with internal services.

## HTTP dependency security

The dedicated transport uses Apache HttpClient **5.6.5** and HttpCore
**5.4.4**, explicitly overriding the older Spring Boot defaults. These versions
include the fixes for CVE-2026-64607 (connection release on decoding failure),
CVE-2026-54399 (HTTP/1 header parser resource use) and CVE-2026-54428 (HTTP/2
header decoder resource use). The refreshed advisory scan also required the
MariaDB JDBC maintenance update **3.5.10 → 3.5.11**, covering CVE-2026-119210,
CVE-2026-119276, CVE-2026-119280 and CVE-2026-119281. It remains on the 3.5
line; all other existing dependency versions are preserved.

References: [Apache security advisories](https://hc.apache.org/security.html),
[MariaDB 3.5.11 release](https://github.com/mariadb-corporation/mariadb-connector-j/releases/tag/3.5.11)
and [MariaDB maintenance compatibility](https://mariadb.com/resources/blog/whats-new-in-mariadb-connector-j-4-0/).

The current runtime dependency inventory and advisory-query scope are recorded
in [`security/webhook-runtime-dependencies.json`](security/webhook-runtime-dependencies.json).
The earlier `runtime.cdx.json` and `osv-runtime-check.json` are retained as
historical evidence for the previous security revision; they cover its original
156 components. A new library scan does not replace scanning the complete
Docker image, including its OS and Java layers.

## Queue, shutdown and failure behavior

Notifications contain an immutable JSON snapshot made before dispatch; delivery
threads never load the scan's JPA entities. Network work runs only on named
`webhook-notify-*` workers. The queue uses immediate rejection rather than a
caller-runs policy. If full or shutting down, the new notification is dropped
with a sanitized warning, while scanning and saved results continue.

Delivery is **best effort and in memory**. There is no durable outbox, automatic
retry/backoff, delivery-history UI or guaranteed ordering with multiple workers.
Process shutdown can discard queued notifications. A receiver accepting a 2xx
response does not prove it has durably processed the event. Executor, resolver,
HTTP client and deadline timer resources are closed during application shutdown.

## Verification and troubleshooting

Follow [Update and verify](UPDATE_AND_VERIFY.md#4-verify-webhook-notifications)
for manual receiver/address checks and the command for the 82 focused webhook
tests. The address policy applies only to outbound webhook destinations; it
does not prevent private-IP access to the Web UI or private ClamD/SQL connections.

Tests cover hanging receivers, response timeouts, total cancellation, queue
saturation, caller isolation, payload snapshots, URL secrecy, redirects,
metadata addresses, mixed DNS answers, DNS changes and server-only internal
opt-in. Existing Discord, null-field payload, UI permission/CSRF, non-root and
proxy TLS behavior is retained. Results are in
[`security/webhook-resilience-validation.json`](security/webhook-resilience-validation.json).

For a missing notification, inspect sanitized application warnings, confirm the
saved URL and server address policy, verify receiver reachability from the
container and check its response status. Increasing scan worker count does not
fix an unavailable receiver. If queue capacity is exhausted, fix receiver
latency/availability or intentionally tune webhook workers/queue separately.
