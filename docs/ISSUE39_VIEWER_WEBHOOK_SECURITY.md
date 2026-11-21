# Issue #39 — Viewer privacy and authenticated webhooks

## Changes

- **VIEWER** users now receive the same allowlisted numeric ClamD STATS projection from `/api/clamdtop` as `/api/v1/metrics/clamd`: `ok`, `endpointId`, validated thread/queue/memory numbers, `pools` and `checkedAt`. No host, port, endpoint name, raw STATS, commands, queued filenames, arbitrary `unparsed` content, or exception text is serialized.
- `/main` displays counts for VIEWER rather than raw STATS/version/errors. `/api/endpoints`, `/api/health`, v1 endpoint metadata and v1 ClamD health use anonymous endpoint labels for VIEWER sessions, and the dashboard no longer shows other users' scan targets.
- Native `/ws/clamdtop` terminal is **OPERATOR/ADMIN-only**, including the handshake interceptor; `/clamdtop` remains available to VIEWER as a safe live summary. This is necessary because the real ncurses program itself shows queued filenames.
- Diagnostics for OPERATOR and ADMIN remain available over raw legacy `/api/clamdtop` and the native terminal. Connection failures report generic text to clients; full exceptions go only to server logs.
- Managed-scan webhooks no longer send scan targets, quarantine paths, raw errors, endpoint names, submitters, or original `foundViruses` path keys by default. `foundViruses` is a structured JSON object (or `null`), with path keys replaced by `item-1`, `item-2`, etc.; suspicious free-text/non-signature values are excluded. Discord uses the same safe rendering and disables mentions.
- Every webhook HTTP delivery uses `X-Signature: sha256=<lowercase-hex>` calculated with HMAC-SHA256 over **the exact UTF-8 bytes of the JSON HTTP body**. The transport rejects missing/invalid signatures. One configured webhook URL uses one secret; keys are write-only in Configuration Center.

## Configuration Center

Open **Administration → Configuration Center → Notifications → Webhook notifications**. New settings:

| UI field | Configuration key | Docker variable | Default | Activation |
| --- | --- | --- | --- | --- |
| Webhook signing secret (write-only) | `app.webhook.signing-secret` | `CLAMAV_WEBHOOK_SIGNING_SECRET` | empty; required for enabled delivery | live |
| Include scan targets and server paths | `app.webhook.include-paths` | `CLAMAV_WEBHOOK_INCLUDE_PATHS` | `false` | live |
| Include raw scan error messages | `app.webhook.include-error-details` | `CLAMAV_WEBHOOK_INCLUDE_ERROR_DETAILS` | `false` | live |
| Include endpoint name and submitted username | `app.webhook.include-identity` | `CLAMAV_WEBHOOK_INCLUDE_IDENTITY` | `false` | live |

All fields can be configured in the GUI; deployment overrides are locked in Configuration Center and must be removed in Compose to restore UI control. Sensitive privacy overrides require administrative confirmation. To enable a webhook, configure a URL and a **unique secret of at least 32 UTF-8 bytes**. Use a password generator or `openssl rand -hex 32` (64 random printable characters). The GUI never returns the saved key and stores it encrypted in the existing Configuration Center encrypted settings store. Replacing a signing secret applies immediately to new notifications.

**Upgrade note:** previously enabled webhooks without a key **stop sending** until a signing secret is configured. This is intentionally fail-closed; no unsigned notifications leave the application. Existing saved destination URLs are kept, and background scan completion is never failed by a delivery rejection. Clearing either the URL or signing secret also disables delivery.

Example Compose deployment overrides (omit these if you want to edit settings in the GUI):

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_WEBHOOK_ENABLED: "true"
      CLAMAV_WEBHOOK_URL: "https://receiver.example.net/clamav"
      CLAMAV_WEBHOOK_SIGNING_SECRET: "${CLAMAV_WEBHOOK_SIGNING_SECRET:?define-a-long-random-secret}"
      CLAMAV_WEBHOOK_INCLUDE_PATHS: "false"
      CLAMAV_WEBHOOK_INCLUDE_ERROR_DETAILS: "false"
      CLAMAV_WEBHOOK_INCLUDE_IDENTITY: "false"
```

For sensitive secrets, Configuration Center or a properly secured deployment secret store is preferable to a literal Compose value, which may be visible in `docker inspect`. Keep webhook receivers on HTTPS with their existing URL/address restrictions.

## Receiver verification

Read the **raw body bytes**, compute `HMAC_SHA256(secret, body_bytes)`, convert to lowercase hexadecimal, and compare to the value after `sha256=` in `X-Signature` with a constant-time comparison. Reject absent or malformed signatures. For example, Python receiver verification:

```python
import hmac, hashlib

def verified(raw_body: bytes, signature: str, secret: str) -> bool:
    if not signature.startswith("sha256="):
        return False
    digest = hmac.new(secret.encode("utf-8"), raw_body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature, "sha256=" + digest)
```

**Replay caveat:** authenticating a request's body does not prevent replay. The receiver should deduplicate by `jobId` for normal JSON deliveries (or by a delivery identifier maintained on the receiver) and apply its own time/rate limits. Discord webhook URLs do not offer custom request HMAC validation; the application still supplies the signed header, but Discord authentication is the webhook URL token.

## Validation

- `Issue39MetricsProjectionTest`: synthetic raw STATS paths, hostnames, injected fields and exception text are excluded from VIEWER responses.
- `Issue39ViewerHttpPrivacyIntegrationTest`: real MVC responses for `/api/clamdtop`, `/api/endpoints`, `/main`, `/clamdtop`, `/dashboard` and `/ws/clamdtop` with viewer/operator roles.
- `Issue39WebhookPrivacySignatureTest`: default/opt-in payloads, structured signatures, fail-closed key policy, live HTTP receiver verifying HMAC over the exact body.
- `Issue39WebhookConfigurationCenterTest`: encrypted signing-secret persistence, GUI editability, Compose override locking, and privacy opt-in defaults.
- Existing webhook dispatch, Configuration Center, ClamdTop timeout and role tests updated for new security policy.

Recommended runtime test commands:

```sh
mvn -B '-Dtest=Issue39*Test,NotificationServiceTest,NotificationDispatchTest,WebhookDeliveryClientTest,ConfigurationCenterUiTest,ConfigurationAllFieldsTest,ClamdProbeTimeoutIntegrationTest' test
mvn -B verify
```

Docker/runtime execution should be performed before closing #39. No database migration is needed.
