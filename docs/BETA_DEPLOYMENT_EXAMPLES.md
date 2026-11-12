# v2.1beta1 Docker Compose examples

Use [beta release notes](releases/v2.1beta1.md) and [complete configuration reference](CONFIGURATION_REFERENCE.md) alongside these examples. These are **examples for fresh installs**; do not replace an existing configured stack, volumes or PostgreSQL database.

## Secure local scanner: default INSTREAM mode

```yaml
services:
  clamav-web-client:
    build: .
    image: clamav-web-client:v2.1beta1-local
    ports:
      - "127.0.0.1:8081:8080"
    environment:
      CLAMAV_HOST: "clamav-server"
      CLAMAV_PORT: "3310"
      # Leave native path settings UNSET; Configuration Center defaults to INSTREAM.
    volumes:
      - ./conf:/app/conf
      - ./data:/app/data
    depends_on: [clamav-server]
    restart: unless-stopped
  clamav-server:
    image: "${CLAMAV_BASE_IMAGE:?Set a tested immutable clamav digest in .env}"
    restart: unless-stopped
    # No host-published 3310 or application data mount.
```

The Web UI listens on loopback for a same-host HTTPS reverse proxy. If your proxy is elsewhere, adapt binding and firewall it. Never expose the backend plain HTTP listener for credentials. Set `CLAMAV_BASE_IMAGE` to a verified immutable reference; a literal example placeholder will not start.

## PostgreSQL behind NGINX
Merge into the **existing** web-service `environment:` section:

```yaml
SPRING_DATASOURCE_URL: "jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT:-5432}/${POSTGRES_DB}"
SPRING_DATASOURCE_USERNAME: "${POSTGRES_USER}"
SPRING_DATASOURCE_PASSWORD: "${POSTGRES_PASSWORD}"
SERVER_FORWARD_HEADERS_STRATEGY: "native"
SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES: "${TRUSTED_PROXY_CIDR:?Set actual proxy peer CIDR}"
SERVER_SERVLET_SESSION_COOKIE_SECURE: "true"
```

Store actual credentials outside Git. Use the **IP that Spring Boot observes as the connecting proxy**, not the public IP or somebody else's `172.18.0.1/32`. NGINX must overwrite untrusted forwarding headers and restrict backend access.

## Remote ClamAV
Use `CLAMAV_HOST` and `CLAMAV_PORT` for the remote scanner, without a bundled `clamav-server`. Leave native path mode off; uploads use INSTREAM. Firewall `clamd` TCP because it has no application-layer authentication.

## Optional native clamdtop filenames
Use the optional `docker-compose.native-path.yml` overlay and UID-matched `Dockerfile.clamd-uid` **only after** verifying the daemon process UID, the readonly upload-only mount and private `0700`/`0600` access with `verify-native.sh`. Keep fallback enabled. Do not mount the full `data/` into clamd or publish 3310. Native `clamdtop` cannot recover original upload filenames from INSTREAM.

## GUI settings and cleanup
Under Administration → Configuration Center → Scanning, choose native path scanning, INSTREAM fallback, quarantine, clean-upload deletion and retention. When quarantine is OFF, infected uploaded files are deleted after successful scanning; clean uploads are deleted by default, with eligible leftovers removed after 24 hours. Configuration Center cannot change Docker UID/mount permissions.

For reverse proxies, startup-bound cookie and trusted-proxy settings are available in Network; Docker environment overrides may lock the UI fields. Preserve local/LDAP access and the `conf/` keys during upgrades.

## Verification
Test clean/EICAR uploads and their deletion; verify PostgreSQL migration on a backup, local and LDAP sign-in, HTTPS recognition, proxy-spoofing protections, watcher functionality, and that clamd has no application DB or secrets mount.
