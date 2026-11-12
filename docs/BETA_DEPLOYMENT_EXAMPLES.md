# v2.1beta1 deployment examples and Configuration Center

The repository's root `docker-compose.yml` is a **fresh-install, host-network NGINX** example; it deliberately binds the Web UI to `127.0.0.1:8081`, leaves ClamD port 3310 unpublished, and shares no application data with ClamD. Copy/adapt it **only for a new installation**.

## A. Local ClamD, secure INSTREAM default

```yaml
services:
  clamav-web-client:
    build: .
    image: clamav-web-client:2.1beta1-local
    ports: ["127.0.0.1:8081:8080"] # for HTTPS-terminating proxy on Docker host
    environment:
      CLAMAV_HOST: "clamav-server"
      CLAMAV_PORT: "3310"
      # Leave native-path scan variables UNSET for safe INSTREAM defaults.
    volumes:
      - ./conf:/app/conf
      - ./data:/app/data
    depends_on: [clamav-server]
    restart: unless-stopped
  clamav-server:
    image: "${CLAMAV_BASE_IMAGE:?Set a verified image digest in .env}"
    restart: unless-stopped
    # No ports or application-data volumes
```

Set `CLAMAV_BASE_IMAGE` in `.env` to a verified immutable ClamAV image digest, then `docker compose config -q` and `docker compose up -d --build`. This example requires you to configure a trusted HTTPS proxy before remote clients use HTTP Basic or Bearer authentication. Direct HTTP must never be used for secrets on an untrusted network.

## B. Add PostgreSQL and trusted HTTPS headers

Merge this block into the **web service** environment of your own Compose file (no credentials in Git):

```yaml
services:
  clamav-web-client:
    environment:
      SPRING_DATASOURCE_URL: "jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT:-5432}/${POSTGRES_DB}"
      SPRING_DATASOURCE_USERNAME: "${POSTGRES_USER}"
      SPRING_DATASOURCE_PASSWORD: "${POSTGRES_PASSWORD}"
      SERVER_FORWARD_HEADERS_STRATEGY: "native"
      SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: "${TRUSTED_PROXY_CIDR:?Set real backend peer IP/CIDR}"
      SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
```

Set values privately in your deployment's `.env` or secrets manager, and ensure host port 8081 stays bound to loopback if NGINX runs on that same host. Replace the proxy CIDR with the **actual source IP the Java container observes**, not the public address. NGINX must overwrite `X-Forwarded-For` and `X-Forwarded-Proto` and remove untrusted `Forwarded` headers. Retain all existing PostgreSQL, LDAP, OIDC, data and `conf` volumes during upgrades.

## C. Remote daemon

Point the web container's `CLAMAV_HOST` and `CLAMAV_PORT` at your remote daemon on a restricted internal network; do not deploy the bundled `clamav-server` in that stack. Use INSTREAM; a remote daemon cannot scan container-local paths it cannot see. `clamd` TCP is not authenticated: firewall it or otherwise isolate its network.

## D. Native path-based scanning and clamdtop filenames (optional)

```sh
# From the repository root; tested images only
# Prepare the upload dir with UID 10001 and private permissions.
./prepare-upload-dir.sh .
docker compose -f docker-compose.yml -f docker-compose.native-path.yml config -q
docker compose -f docker-compose.yml -f docker-compose.native-path.yml up -d --build
./verify-native.sh
```

The optional native scanner receives only `./data/uploads` read-only and runs as UID 10001 to match `0700`/`0600` ownership. After runtime verification, enable **Native uploaded-path scanning** in Configuration Center and keep **INSTREAM fallback** enabled. If verification fails, disable native scanning until the UID/mount problem is resolved. The group-readable permission workaround is *not* required.

## E. Settings, cleanup and user interface

In **Administration → Configuration Center**, `Scanning` exposes native uploaded-path scanning, fallback, quarantine, clean-upload deletion and retention. By default, clean and infected uploaded files are removed after successful scans (infected only if quarantine is disabled). Failed/orphaned uploads are eligible for retention cleanup after 24 hours. The Config Center does not create Docker mounts or change the running `clamd` UID.

`Network` exposes forwarded-header strategy, trusted proxy lists, secure cookies and SameSite. Startup-bound network settings require restarting the application. Docker environment overrides always take precedence over saved GUI fields and may lock controls.

## F. Upgrading from v1 / an existing v2 release

Back up the current database and volumes first. Use [PRODUCTION_SCANNING.md](PRODUCTION_SCANNING.md) and the database migration docs. The one-time Flyway baseline flags apply only when intentionally baselining a legacy non-Flyway schema; they are **not** generally required for every database vendor or for new installations. Test migrations on a copy before deploying.

## Smoke tests

- Login through HTTPS using local credentials and verify `/login?error` remains reachable anonymously; test LDAP separately with an actual directory account and mapped role.
- Submit a clean test upload and EICAR; both must finish with correct verdicts, and completed uploads must be removed when cleanup is enabled and quarantine is off.
- Confirm port 3310 is not publicly exposed and the scanner cannot access `/app/data` DB/secrets.
- If native mode is selected, confirm `clamd` UID `10001`, readonly upload-only mount, and no INSTREAM fallback warnings.
- Check PATH/WATCH functionality separately when removing any legacy full-data mounts.
