# Issue #40 — REST API credential transport and upstream response isolation

## Security behavior

Both external REST clients (`RestApiBridge` and `RestApiClient`) and the Configuration Center connection test enforce the same rule:

- `https://` with an API key: supported (recommended); TLS peer verification remains enabled.
- `http://localhost`, `http://127.0.0.0/8` and `http://[::1]` with an API key: supported for loopback test/same-network-namespace proxy endpoints. Hostname resolution never grants a loopback exemption.
- `http://` with **no** API key: allowed; no credentials are sent. HTTP is nevertheless unencrypted and should be used only on trusted networks.
- `http://` to a **non-loopback** host with a nonempty API key: **refused at application startup** unless explicitly opted in with `CLAMAV_REST_ALLOW_INSECURE=true`.
- Even with the opt-in, all API routes remain restricted to configured origins and allowlisted paths; HTTP redirects are never followed. The opt-in prints a prominent server-side security warning and does **not** encrypt HTTP.

The policy covers both `X-API-Key` and `Authorization: Bearer` modes. No key or URL values are included in policy errors. An explicitly enabled insecure transport is a conscious administrator exception for legacy scanners, **not** the recommended configuration.

## Configuration Center

Go to **Administration → Configuration Center → Connections → External REST scanner → Advanced**:

| Field | Setting | Default | Activation |
| --- | --- | --- | --- |
| Allow API key over insecure HTTP | `CLAMAV_REST_ALLOW_INSECURE` | `false` | Restart |

Configuration Center validates the URL/key/opt-in combination **during Review/Apply**. The value is persisted with other settings and takes effect after restart. The field is locked when the same value is set through Docker/Podman/Kubernetes environment configuration. The key itself remains masked/encrypted in Configuration Center.

## Docker Compose examples

**Preferred: authenticated HTTPS**

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_REST_URL: "https://scanner.internal.example:3000"
      CLAMAV_REST_API_KEY: "${CLAMAV_REST_API_KEY}"
      CLAMAV_REST_ALLOW_INSECURE: "false"
```

**Legacy external HTTP scanner — opt in knowingly**

```yaml
services:
  clamav-web-client:
    environment:
      CLAMAV_REST_URL: "http://rest-scanner:3000"
      CLAMAV_REST_API_KEY: "${CLAMAV_REST_API_KEY}"
      CLAMAV_REST_ALLOW_INSECURE: "true"  # credentials traverse the network unencrypted
```

If you want to manage the opt-in in Configuration Center instead of Compose, **omit** `CLAMAV_REST_ALLOW_INSECURE` from Compose entirely, save the field in Configuration Center, and restart/recreate the application container. The opt-in is not applied live.

Existing deployments that set both an API key and an HTTP REST URL to a Docker bridge/LAN server will now fail startup until migrated to HTTPS or explicitly allowed. This is intentional, and is not a change to the ClamD TCP connection. `http://localhost` inside a container refers to that container, not necessarily the Docker host.

## Upstream response isolation

- Non-2xx upstream HTTP responses are not forwarded verbatim to browsers. Browser errors have a fixed `error` / `message` JSON shape and do not include response bodies, network addresses, stack traces or request credentials.
- `404` remains distinguishable for the existing jobs/history fallback.
- Both clients read upstream response streams with a fixed **10 MiB** limit. `Content-Length` is checked when present; chunked bodies are bounded independently, and oversized/malformed JSON is rejected. Stream cleanup happens on all paths.
- Redirect following is disabled; an upstream HTTP 3xx response is treated as a failed operation without contacting its `Location`.
- On success, trusted upstream API data is still shown to authorized operators as required by API Scan/Reports. This change does not redact successful job payloads and is not a substitute for role-based access controls.

## Verification and deployment

Run before deploying:

```sh
python3 scripts/check-issue40-rest-security.py
mvn -B '-Dtest=Issue40*Test,RestApiBridgeTest,RestApiClientTest,RestMonitorIntegrationTest,ConfigurationAllFieldsTest,ConfigurationConsumerWiringTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue40 .
```

Check fail-fast startup with a non-loopback HTTP URL and nonempty synthetic key (without opt-in), then verify successful HTTPS or deliberate opt-in operation. Do not use a real credential in CI or test logs. Do not claim the issue complete until full Maven tests and a container deployment verify startup and error isolation.
