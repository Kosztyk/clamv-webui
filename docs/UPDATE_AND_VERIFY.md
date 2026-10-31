# Update an existing deployment and verify issues #10, #11 and #12

The fixes are in the application source, packaged configuration and Dockerfile.
A standard build applies all three; no helper script or proxy build flag is
needed. New environment fields are optional tuning, not activation switches.

## 1. Build and deploy

Use a clean source directory separate from your live deployment's `conf`,
`data` and Compose configuration. From a Git checkout:

```bash
git clone https://github.com/Kosztyk/clamv-webui.git clamv-webui-source
cd clamv-webui-source
docker build --pull -t clamav-web-client:2.0.0-local .
```

If you already have the latest complete source archive, extract it and run the
same Docker build from the directory containing `Dockerfile`, `pom.xml` and
`src/`. An image built on another machine must be transferred to the Docker
host or published to your chosen registry before deployment.

Back up the database and the existing `conf`/`data` mounts. In your **existing
deployment's Compose file**, select the tested local image for the Web UI:

```yaml
services:
  clamav-web-client:
    image: clamav-web-client:2.0.0-local
    pull_policy: never
    # Preserve the rest of your existing configuration.
```

Keep database settings, ports, secrets, identity settings and volume mounts.
Use the actual Compose service name in the following commands. After the build
succeeds, run these from the live deployment directory, substituting the actual
host paths mounted at `/app/conf` and `/app/data`:

```bash
docker compose stop clamav-web-client
sudo chown -hR 10001:10001 ./conf ./data
sudo chmod -R u+rwX ./conf ./data
docker compose config --quiet
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
docker compose logs --tail=100 clamav-web-client
```

Do not apply these ownership commands to database server storage or unrelated
scan roots. For new installations, create the two directories before assigning
ownership. For named volumes/PVCs, follow your storage system's migration
procedure and [non-root deployment guide](NON_ROOT_CONTAINER.md).

Wait for `Started ClamAVWebClientApplication` before testing through Nginx.
Startup duration depends on the machine and database. A `502` while the backend
is starting does not establish a proxy failure. If it persists, check backend
logs and the proxy's configured upstream. For the Docker container name used in
the remaining examples:

```bash
docker port clamav-web-client 8080/tcp
docker inspect clamav-web-client --format '{{range .Mounts}}{{println .Source "->" .Destination "writable=" .RW}}{{end}}'
```

Replace the container name if your deployment uses another name. A mount with
`writable=true` can still contain files owned by root. An `Operation not
permitted` failure from `AtomicPropertiesFile.ownerOnly` usually means UID 10001
cannot change a file's private permissions; correct file ownership as well as
directory access. These changes do not require Flyway baselining or a database
reset.

## 2. Verify non-root execution

```bash
docker exec clamav-web-client sh -eu -c '
  id
  test "$(id -u):$(id -g)" = "10001:10001"
  for directory in /app/conf /app/data; do
    probe=$(mktemp "$directory/.permission-test.XXXXXX")
    rm "$probe"
  done
  test -r /app/clamav-web-client.jar
  test ! -w /app/clamav-web-client.jar
  echo "PASS: non-root user, writable configuration/data, protected application JAR"
'
```

Expected: UID/GID `10001:10001` and the `PASS` line. The probes are removed
without changing existing settings or scan data.

The repository's stricter `scripts/smoke-non-root.sh` also checks capability
dropping, no-new-privileges, native PTY operation and fresh anonymous volumes.
It targets the included Compose deployment; a custom deployment may omit those
additional hardening settings even when the numeric non-root check passes.

## 3. Verify HTTPS through the reverse proxy

Use your deployment's HTTPS address. **Public DNS is not required.** A local IP
or local DNS name works. Set the URL without a trailing slash:

```bash
APP_URL='https://clamav.example.com'
curl -sS -i --max-time 15 \
  -H 'Authorization: Bearer deliberately-invalid' \
  "$APP_URL/api/v1/health/live"
```

| Result | Meaning |
|---|---|
| `401` and `invalid_token` | Expected: the application recognizes HTTPS and rejects the deliberately invalid credential |
| `403` and `tls_required` | Check that the trusted proxy sends the original HTTPS scheme and that no `none` configuration overrides the packaged default |
| curl error 60 | Certificate verification stopped the request before API authentication; configure client trust and check the URL's certificate SAN |
| `502` from Nginx | Check application startup, backend connectivity and upstream address/port |

### Local address and self-signed certificates

Replace the example IP with your own LAN address. This one diagnostic uses only
a fake token and skips certificate verification:

```bash
APP_URL='https://192.168.1.50'
curl -k -sS -i --max-time 15 \
  -H 'Authorization: Bearer deliberately-invalid' \
  "$APP_URL/api/v1/health/live"
```

Expected: `401` with `invalid_token`. This verifies forwarding, not successful
authentication with a valid token. Keep certificate verification enabled for
real credentials. Obtain the trusted local CA certificate, or the server's
certificate if it is directly self-signed, through your own certificate
management process. For example:

```bash
TLS_CA='/absolute/path/to/your/trusted-certificate.pem'
curl --cacert "$TLS_CA" -sS -i --max-time 15 \
  -H 'Authorization: Bearer deliberately-invalid' \
  "$APP_URL/api/v1/health/live"
```

The IP/hostname in `APP_URL` must match the certificate's SAN. If the certificate
only contains a local name such as `clamav.home`, you can test that name without
public DNS or editing `/etc/hosts`:

```bash
APP_URL='https://clamav.home'
curl --cacert "$TLS_CA" --resolve 'clamav.home:443:192.168.1.50' \
  -sS -i --max-time 15 \
  -H 'Authorization: Bearer deliberately-invalid' \
  "$APP_URL/api/v1/health/live"
```

Keep the same `--resolve` option on subsequent commands if the local name is
not otherwise resolvable. See [curl certificate verification](https://curl.se/docs/sslcerts.html).
The Java LDAP truststore and your client's trust of the Nginx certificate are
separate settings; a working LDAPS connection does not configure curl's trust.

### Positive token and plain HTTP checks

In **Admin → API Tokens**, create a temporary token with only `health:read`.
For a trusted certificate already in your system store:

```bash
read -r -s -p 'Temporary API token: ' API_TOKEN
printf '\n'
printf 'header = "Authorization: Bearer %s"\n' "$API_TOKEN" |
  curl -sS -i --max-time 15 --config - \
    "$APP_URL/api/v1/health/live"
unset API_TOKEN
```

For your local certificate, add `--cacert "$TLS_CA"` to that curl command, and
the matching `--resolve` option if needed. Expected: `200` and JSON containing
`"status":"UP"`. Revoke the temporary token after verification. Do not paste it
into issue reports.

For the negative test, use the published backend port returned by `docker port`
and **only the fake token**. Example for a deployment publishing port 8081:

```bash
curl -sS -i --max-time 15 \
  -H 'Authorization: Bearer deliberately-invalid' \
  'http://127.0.0.1:8081/api/v1/health/live'
```

Expected: `403` and `tls_required`. Send no forwarding headers on this test.
Keep backend access restricted to the trusted proxy; do not expose it to perform
the test. These statuses concern `/api/v1` API tokens; the separate Prometheus
scrape-token gate at `/actuator/prometheus` is unchanged.

## 4. Verify webhook notifications

1. In **Admin → Settings → Webhook notifications**, enable delivery and save a
   Discord webhook or another public receiver you control. A blank replacement
   retains the current saved URL; the Remove checkbox explicitly clears it.
2. Upload a harmless small text file through **Scan**, then check that the job
   completes and the receiver gets the notification. **API Scan** uses a separate
   external REST service whose webhook system is not changed here.
3. Try saving `http://127.0.0.1:18080/hook`: the default policy rejects the URL.
   Try `http://169.254.169.254/latest/meta-data/`: it remains rejected even if
   internal receivers are permitted. Failed validation preserves the saved URL.
   You do not need to contact either address to test form rejection.
4. For a manual latency test, use a public receiver you control that waits 30
   seconds before responding. Submit at least three small scans. Scan completion
   should proceed independently of delivery; active deliveries have a default
   total deadline of 20 seconds. Queue waiting time is separate from that bound.

Restore your intended notification settings after testing. Inspect sanitized
warnings without printing the receiver URL or token:

```bash
docker logs --since 5m clamav-web-client 2>&1 | grep -i webhook
```

An empty result does not prove delivery: success is not logged at warning level.
Confirm receipt at the receiver. Queue-full and timeout warnings are expected
when those failures are deliberately exercised by the automated tests below.

The private-address policy applies only to outbound notifications. The UI can
remain on a private IP, and private ClamD/PostgreSQL connections are unaffected.
For an internal receiver, opt in at startup using
`CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES: "true"`, recreate the UI service, then
save its receiver URL. Metadata and other permanently prohibited addresses
remain blocked. See [all webhook settings and limitations](WEBHOOK_RESILIENCE.md).

### Automated timeout, dispatch and address tests

Run from a **separate source copy**, preferably on your build/test machine,
not from the live deployment's configuration/data directory:

```bash
docker run --rm \
  -v "$PWD":/src -w /src \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -ntp \
  -Dtest=NotificationServiceTest,NotificationDispatchTest,WebhookAddressPolicyTest,WebhookDeliveryClientTest,WebhookNotificationPropertiesTest \
  test
```

Expected: **82 tests, zero failures/errors, `BUILD SUCCESS`**. These focused
tests use mocks and local HTTP receivers, not your production database or saved
webhook. They cover slow receivers, total cancellation, queue saturation, scan
caller isolation, redirects, DNS address checks, payload snapshots and defaults.
The Maven container downloads dependencies and writes build output under
`target`; it does not update the running application image.

## 5. Optional settings and complete verification

You do not need new Compose environment fields for the three fixes. The
defaults live in the application and Dockerfile. An old explicit
`SERVER_FORWARD_HEADERS_STRATEGY=none` still overrides the proxy default.
For intentional direct access, see [the proxy opt-out](REVERSE_PROXY_TLS.md).

The current UI controls scan settings and webhook URL/enable state. Database
bootstrap, identity truststores, proxy trust and webhook timeout/address policy
remain deployment settings.

For full source verification, run `mvn -B -ntp clean verify` and
`bash scripts/release-gate.sh --static-only` on a machine with the documented
tools. The included CI workflow also builds the Docker image and exercises the
non-root and EICAR checks. See [the complete feature acceptance checklist](COMPLETE_USER_GUIDE.md#25-end-to-end-verification-and-eicar-detection)
and [reported field validation](FIELD_VALIDATION.md) for coverage and limits.
