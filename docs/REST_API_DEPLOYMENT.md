# Web UI API and optional external REST scanner

This guide accompanies application **2.1.0**, including the Configuration Center, automatic Scan Jobs updates, proxy/non-root/webhook fixes and corrected database connection tests. All examples use placeholders, Docker service names or reserved documentation domains. Supply your own deployment values privately.

## Which API to use

| Interface | Runs in | Configuration | Jobs and authentication |
|---|---|---|---|
| Built-in `/api/v1` | The Web UI container, application port 8080 | **Configuration Center → Identity & API** for global API limits; **API Tokens** for issuing/revoking individual tokens | The Web UI's managed Scan Jobs database; scoped HTTPS Bearer tokens |
| External ClamAV REST API | A separate REST container, port 3000 in this example | Its own Compose `APP_*` / `CLAMD_*` environment; configure the Web UI client under **Connections → External REST scanner** | Its own API contract, queue/history and authentication, when supported |

The built-in API works with the managed ClamD connection. It needs no REST container or `CLAMAV_REST_URL`. CLI and MCP use this built-in API. The separate REST service is useful for clients such as File Browser and the Web UI's **API Scan / API Reports** pages.

## Built-in API quick start

Complete setup and start the application, then sign in as an administrator and open **API Tokens**. Create a token with the scopes your client needs, an expiry, and optional endpoint restrictions. A path-scanning token also needs allowed server path roots. Copy the secret when issued; only its hash is retained.

| Operation | Route | Token scope |
|---|---|---|
| Web process liveness | `GET /api/v1/health/live` | `health:read` |
| Database readiness | `GET /api/v1/health/ready` | `health:read` |
| ClamD health and signature freshness | `GET /api/v1/health/clamd` | `health:read` |
| Available engines | `GET /api/v1/endpoints` | `endpoints:read` |
| Submit files | `POST /api/v1/scans/uploads` — multipart field `files` | `scan:upload` |
| Submit an approved server path | `POST /api/v1/scans/paths` | `scan:path` |
| List/read jobs and progress | `GET /api/v1/jobs`, `/api/v1/jobs/{id}`, `/api/v1/jobs/{id}/progress` | `jobs:read` |
| Audit events | `GET /api/v1/audit` | `audit:read` |
| OpenAPI contract | `GET /api/v1/openapi.yaml` | `endpoints:read` |

Use your actual HTTPS application address and load the issued token privately into `CLAMAV_API_TOKEN`. These examples contain no usable credential:

```bash
WEBUI_URL='https://clamav.example.test'
curl --fail-with-body \
  -H "Authorization: Bearer ${CLAMAV_API_TOKEN:?Set CLAMAV_API_TOKEN privately first}" \
  "$WEBUI_URL/api/v1/health/ready"
curl --fail-with-body \
  -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  -F 'files=@./sample.txt' "$WEBUI_URL/api/v1/scans/uploads"
curl --fail-with-body \
  -H "Authorization: Bearer $CLAMAV_API_TOKEN" \
  "$WEBUI_URL/api/v1/jobs?page=0&size=25"
```

An accepted upload returns HTTP **202** with `created` and `jobIds`. Poll `/api/v1/jobs/{id}/progress` or read the job for its final verdict; HTTP 202 means queued, not a clean result. Self-signed deployments can use `curl --cacert /path/to/your-ca.crt` with the trusted local CA. Direct HTTP GUI setup remains available, while API Bearer authentication requires HTTPS. Trusted ingress TLS termination is recognized by the packaged forwarded-header strategy. See [API scope and security details](COMPLETE_USER_GUIDE.md#16-api-tokens-apiv1-legacy-api-scope-and-curl-examples), [CLI](CLI.md), [MCP](MCP.md) and [the OpenAPI source](../src/main/resources/openapi/clamav-v1.yaml).

## Deploy the optional external REST service

The complete, sanitized service example is [examples/compose/clamav-rest-api.yaml](../examples/compose/clamav-rest-api.yaml). It uses the supplied public image `kosztyk/clamav-rest-api:renovate`, an existing daemon and a named temporary-upload volume. The external REST image is not built by the Web UI's Dockerfile.

1. Put your own reachable ClamD host in a private `.env` alongside the deployment Compose. The following values are placeholders:

   ```dotenv
   CLAMAV_HOST=clamd.example.test
   CLAMAV_PORT=3310
   ```

   Replace the hostname, restrict the populated file with `chmod 600 .env`, and keep it outside Git. The example uses these names for Compose interpolation into the REST service's `CLAMD_IP` and `CLAMD_PORT`.
2. For a fresh GUI-managed Web UI plus the REST service on the same Docker network, build the application and start both files together:

   ```bash
   docker build --pull -t clamav-web-client:2.1.0-local .
   docker compose --env-file .env -f docker-compose.minimal.yaml \
     -f examples/compose/clamav-rest-api.yaml config --quiet
   docker compose --env-file .env -f docker-compose.minimal.yaml \
     -f examples/compose/clamav-rest-api.yaml up -d
   ```

   Use this same file pair for subsequent Compose operations. For an existing installation, add the REST service to its existing deployment and keep its original conf/data volumes and database selection.
3. Obtain this Web UI instance's owner credential on its Docker host:

   ```bash
   docker exec clamav-web-client sh -c 'cat /app/conf/configuration-recovery-token; echo'
   ```

   Open `/setup/`, unlock with that credential, complete the managed ClamD/database settings, review/apply and select **Start application**. Normal administrator login uses its own password. See [the complete first-run procedure](../README.md#configure-the-app-with-configuration-center).

The REST service's published port defaults to host loopback. Containers in the same Compose project reach it by the service name `clamav-rest-api` without using the published port. For a remote REST client, choose an appropriate bind address using the private Compose variable `REST_API_BIND` and provide the network/authentication protection required by that REST service. The Web UI's token does not secure a separate container. If the REST service runs on another host or Docker network, use its reachable address; a service name on one Docker network is not automatically visible on another.

The image-specific `APP_TEMP_FILE_DIR`, `APP_UPLOAD_TIMEOUT` and `APP_MAX_CONCURRENT_SCANS` entries are retained from the supplied REST deployment example. Their semantics depend on the selected REST image; the public upstream README does not document them, and that image was not executed during this source-package update. Ensure its configured temporary directory is writable by its runtime user.

## Connect through Configuration Center

Open **Administration → Configuration center → Connections → External REST scanner**, enable it, and configure:

| GUI field | Example / meaning | Deployment override |
|---|---|---|
| Enable the external REST scanner | Enabled | `CLAMAV_REST_ENABLED=true` |
| External REST URL | `http://clamav-rest-api:3000` when both services share the Compose network | `CLAMAV_REST_URL` |
| API key | Leave blank only when the external service requires none | `CLAMAV_REST_API_KEY` |
| Authentication header | Match the server's `X-API-Key` or Bearer contract | `CLAMAV_REST_AUTH_MODE` |
| Upload form field | `FILES`, matching the REST service's `APP_FORM_KEY` | `CLAMAV_REST_FORM_KEY` |
| Request timeout (seconds) | `10` seconds | `CLAMAV_REST_TIMEOUT_SECONDS` |
| Upload timeout (seconds) | `3600` seconds | `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` |
| Maximum report jobs | `5000` | `CLAMAV_REST_REPORT_MAX_JOBS` |

Check the Advanced path fields against the selected REST server:

| Operation | Web UI default | Deployment override |
|---|---|---|
| Statistics and the GUI connection probe | `/api/v1/stats` | `CLAMAV_REST_STATS_PATH` |
| Jobs | `/api/v1/jobs` | `CLAMAV_REST_JOBS_PATH` |
| Finished history | `/api/v1/history` | `CLAMAV_REST_HISTORY_PATH` |
| Async upload | `/api/v1/asyncscan` | `CLAMAV_REST_SCAN_PATH` |
| Scan-history summary | `/api/v1/scan-history` | `CLAMAV_REST_SCAN_HISTORY_PATH` |
| Webhook listing | `/api/v1/webhooks` | `CLAMAV_REST_WEBHOOKS_PATH` |
| Multipart webhook selector | `webhookId` | `CLAMAV_REST_WEBHOOK_FIELD` |

**Test connection** calls the configured statistics path with the draft server-side credentials and saves nothing. Review/apply the configuration and restart when indicated. Then use **API Scan** and **API Reports**. These pages require the corresponding async, jobs, reports and webhook capabilities in the external server. A basic synchronous-only REST server is insufficient for the full workflow; the public upstream project explicitly excludes its async/statistics implementation. A successful version endpoint alone does not establish compatibility. Do not configure the Web UI's own `/api/v1` as its external REST URL.

## Connect through docker-compose.yaml

The equivalent client-side settings can be placed in the **Web UI service's** environment mapping:

```yaml
environment:
  CLAMAV_REST_ENABLED: "true"
  CLAMAV_REST_URL: "http://clamav-rest-api:3000"
  CLAMAV_REST_FORM_KEY: "FILES"
  CLAMAV_REST_TIMEOUT_SECONDS: "10"
  CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS: "3600"
  CLAMAV_REST_REPORT_MAX_JOBS: "5000"
```

This URL assumes both services share a network. Set an API credential/authentication mode and path overrides only as required by the external server. Keep actual credentials in private deployment inputs. Recreate the Web UI container after editing its environment. These entries become authoritative, read-only fields in Configuration Center; remove an entry and recreate the container to return that field to GUI management. Unspecified settings remain GUI-editable. The GUI configures the Web UI's REST client; the REST container's own `APP_*` and `CLAMD_*` settings remain in its deployment.

## Verify uploads and limits

From the Docker host, a server supporting the upstream synchronous scan contract can be checked with:

```bash
curl --fail-with-body http://localhost:3000/api/v1/version
curl --fail-with-body -F 'FILES=@./sample.txt' \
  http://localhost:3000/api/v1/scan
```

Add the authentication required by that REST server. Its multipart field is uppercase `FILES`; the Web UI's built-in upload endpoint uses lowercase `files`. For the Web UI's asynchronous integration, also verify the configured stats/jobs/async endpoints and their response format.

The upstream `APP_MAX_FILE_SIZE` is a byte limit per file. The example uses **262144000 bytes = 250 MiB**; `190` would represent 190 bytes under that contract. A 25 GiB byte value would be `26843545600`, but changing this one setting does not establish support for files that large. Browser upload limits, the Web UI's upload policy and multipart file/request limits, proxy body/deadline limits, REST limits, ClamD `StreamMaxLength`/`MaxFileSize`/`MaxScanSize`, available temporary storage and timeouts must all permit the intended workload. `CLAMD_TIMEOUT=3600000` is one hour in milliseconds; the Web UI REST upload timeout is expressed in seconds.

References: [upstream REST configuration/endpoints](https://github.com/benzino77/clamav-rest-api/blob/master/README.md), [REST image](https://hub.docker.com/r/kosztyk/clamav-rest-api), [all Web UI field mappings](CONFIGURATION_REFERENCE.md), [current database test behavior](DATABASE_CONNECTION_TESTS.md) and [complete guide](COMPLETE_USER_GUIDE.md).
