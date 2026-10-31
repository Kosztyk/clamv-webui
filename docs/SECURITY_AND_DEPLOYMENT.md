# Security and deployment

## Credentials / secrets

- A new database causes the server to create `admin` with a **cryptographically random password** saved **once** to `data/bootstrap-admin-password` (`0600` permissions on POSIX volumes). Read it with `docker compose exec -T clamav-web-client cat /app/data/bootstrap-admin-password` (the host user may not have permission to read its `0600` owner-only file). Do **not** publish this file or upload it with support requests.
- Instead you may set `CLAMAV_BOOTSTRAP_ADMIN_PASSWORD` (12–128 characters) **before the first boot**. Supply this from an uncommitted `.env` or an environment secret manager; the Compose file passes it through when set. Note that `docker compose config` may print interpolated secrets, so do not publish its full output. The env var does not overwrite existing users.
- After first login, update the administrator password at **Admin > Users**, then securely delete the bootstrap password file. The file is not regenerated if an account already exists.
- This change does **not** silently reset existing accounts. Older deployments that still use `admin/admin` log an urgent warning and must rotate the credential manually.
- The application prohibits deleting its last enabled admin account; create a second admin before removing the first.
- Do not keep webhook URLs with embedded bearer tokens in public source control; they are persisted in `./conf/clamav-web-client.properties`. Restrict that file/directory and back up separately from the image.
- `/api/v1/**` supports scoped Bearer tokens over HTTPS; browser sessions and legacy Basic-auth routes retain their own role/CSRF rules. Local, LDAP/AD and OIDC sign-in are supported. See the [API and authentication guide](COMPLETE_USER_GUIDE.md).

## Transport security / reverse proxies

For Bearer/CLI/MCP HTTPS recognition behind a trusted TLS-terminating proxy,
see [issue #11 configuration and regression coverage](REVERSE_PROXY_TLS.md).

- This application does **not** terminate HTTPS itself in the supplied Compose example. Bind port 8080 to `127.0.0.1` and front it with a trusted NGINX, Traefik, or Caddy reverse proxy that serves HTTPS and handles certificates/HSTS.
- Basic authentication and the web login session must never transit unencrypted public connections. Configure `Secure`, `HttpOnly`, and `SameSite` cookies through the TLS-terminating proxy and Spring configuration appropriate to deployment; avoid mixed HTTP/HTTPS links.
- The app packages `server.forward-headers-strategy=${SERVER_FORWARD_HEADERS_STRATEGY:framework}` by default, so TLS termination works with normal Docker/JAR builds. Restrict backend connections to the trusted proxy, which must **strip all inbound forwarded headers and replace them with its own**. Forwarded headers are trusted input in this mode. A deployment exposed directly without a trusted proxy can explicitly set `SERVER_FORWARD_HEADERS_STRATEGY=none` to ignore them; machine credentials still require HTTPS.
- `/actuator/**` should be restricted to a management network or disabled if not needed. Spring Actuator metrics/health exposure does not imply a public, authenticated metrics endpoint.
- The H2 web console is **disabled** by default. The H2 database file is inside the mounted data directory.

## Webhook egress policy

Direct notifications use bounded background delivery, explicit connection/read/DNS/total
timeouts and address validation at connection-time resolution. Private destinations
are denied unless `CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES=true` is set by the
server operator; listed metadata/platform and link-local addresses remain denied.
Redirects, automatic retries and receiver-body buffering are disabled. URL
credentials and response/exception content are not logged. See
[Webhook resilience](WEBHOOK_RESILIENCE.md) for exact policy, settings and limits.

## Volumes / file access

The container runs as UID/GID `10001:10001`. Prepare writable host `conf` and
`data` folders before first start or upgrading an existing root-owned install;
see [non-root deployment](NON_ROOT_CONTAINER.md) for the commands, Docker
Desktop/named-volume options, scan/socket permissions and Kubernetes settings.

| Path | Web UI | ClamAV daemon | Use |
|---|---|---|---|
| `./data:/app/data` | Read/write | `./data:/app/data:ro` | Job DB, uploads, quarantine, bootstrap secret; upload files must be readable by clamd. |
| `./conf:/app/conf` | Read/write | Not mounted | Persisted web UI settings, including webhook URL. |
| `/scandir` (optional) | Read for path/watcher policy | Read at **same absolute path** | Native path scans and watched directories. |

- Do **not** mount Docker socket, host root filesystem, or broad host secrets into either container.
- The standalone deployment example keeps ClamAV TCP port 3310 inside the Compose network. The root development Compose publishes it for local testing; remove or firewall that publication for production.
- Back up H2 while the application is stopped or use a database-consistent backup method. Never share the file-backed H2 volume between replicas.
- Filesystem symlinks and scan roots are resolved by `Path.toRealPath()` and checked against canonical, existing allowed directories when a job is queued; the scanner also rechecks the target at execution. An operator with write access to target directories can still race a filesystem pathname between validation and ClamD access; use read-only, trusted mounts for path scans.

## Scanning mode: native MULTISCAN vs INSTREAM

**Native upload path scanning** (`CLAMAV_UPLOAD_PATH_SCAN_ENABLED=true`) sends `nMULTISCAN /app/data/uploads/<file>\n` to clamd. ClamD must see **the exact same filesystem path** as the web client. This produces file paths in clamdtop, while allowing the daemon to work directly on files. The supplied Compose mounts `./data` at `/app/data` in both containers. If mount paths or daemon permissions differ, the scan fails unless fallback is explicitly enabled.

**INSTREAM** (`CLAMAV_UPLOAD_PATH_SCAN_ENABLED=false`) sends file *bytes* to the daemon and works when the scanner is remote or cannot see the web container volume. ClamdTop then typically displays `INSTREAM` rather than the original filename; this is expected protocol behavior.

For a remote daemon where shared storage cannot be mounted, set:

```yaml
CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "false"
CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM: "true"
```

To retain filenames in clamdtop, use **true / false** with identical mount paths. `CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM=true` explicitly enables a fallback when native scanning fails but means the filenames may disappear from ClamdTop.

For PATH/WATCH scans the user-supplied target must also be readable by **both** the web container (for root-policy validation) and the ClamD container (for native path execution). Configure `app.allowedScanRoots` and consistent readonly volumes (e.g. `- /srv/media:/scandir:ro` in both services).

## Verification

```bash
mvn -B -ntp verify
docker compose config --quiet
docker compose build
docker compose up -d
scripts/smoke-eicar.sh
docker compose down
```

The integration smoke script uploads the harmless EICAR test signature and requires `VIRUS_FOUND`, a persisted signature and a visible audit entry. It needs a clean database (or provide `CLAMAV_SMOKE_PASSWORD` for an existing administrator).

See [CI workflow](../.github/workflows/phase0-ci.yml) for automatic execution on pushes and pull requests.
