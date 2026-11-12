# ClamAV Web UI — secure upload scanning deployment

## Changes in this full-source candidate

This is a **directly edited source tree, not a patch**. Existing application logic for issues
#13–#24 is retained. The new-install defaults are `app.upload.pathScan.enabled=false`
and `app.upload.pathScan.fallbackToInstream=true` (INSTREAM). Configuration Center
hints explain the operational dependency for native pathname scans.

The source does not alter `PrivateFiles` (directory `0700`, file `0600`), scan cleanup,
quarantine, session revocation, rate limiting, LDAP, API scope, audit, or reverse proxy
security. No database migration is required for these two defaults.

## Fresh installation

1. Use `docker-compose.yml` as a secure **example** and merge your own DB, volumes,
   and trusted TLS-terminating reverse proxy configuration. Do NOT expose Spring Boot's
   plain HTTP listener to untrusted networks.
2. Pin `CLAMAV_BASE_IMAGE` to a tested immutable image digest in `.env`.
3. No shared upload mount is required for INSTREAM. The bundled `clamav-server` has no
   public port and no access to application data.
4. Start the containers, verify the `clamav-server:3310` endpoint, and test clean and
   EICAR uploads. With quarantine disabled, completed infected uploads are deleted by
   the existing code; deletion can fail and be retried by the retention janitor.

## Upgrade from an existing installation

**Do not replace your live Compose file with the sample.** Back up your PostgreSQL/H2
DB, `conf`, and `data`. Retain NGINX TLS and the correct **site-specific** trusted
proxy peer CIDR; never copy `172.18.0.1/32` to another host without checking.

Old installations can have saved GUI values `pathScan.enabled=true` and
`fallbackToInstream=false`. New defaults **do not overwrite saved GUI values**.
Before removing clamd's full data mount or public scanner port, set native path
scanning OFF and INSTREAM fallback ON in Configuration Center; remove conflicting
Compose environment overrides. Update the existing scanner endpoint to
`clamav-server:3310` if using the bundled daemon. No credentials or volumes should
be deleted as part of this step.

PATH/WATCH jobs still require that the clamd daemon can access those scan roots.
INSTREAM upload scanning does not make remote filesystem PATH/WATCH scans portable;
configure only necessary read-only mounts and allowed roots for those features.

## Optional native pathname / `clamdtop` filenames

The `docker-compose.native-path.yml` overlay and `Dockerfile.clamd-uid` are a **candidate**
implementation. They align clamd's numeric UID to the web container's UID 10001 and
mount only `./data/uploads` read-only, retaining `0700`/`0600`. Verify the actual
ClamAV entrypoint and file-access behavior with `verify-native.sh` before enabling
native path mode in Configuration Center. `clamd` requires write access to its *own*
signature/working directories; this overlay does not make those read-only.

Run the appropriate combined Compose command (not a blind overwrite of a running
production stack), verify the daemon process runs as UID 10001 and can traverse/read
private test uploads, and test EICAR. Keep fallback ON. If native mode is not proven,
leave native scanning OFF.

**Fundamental limitation:** native `clamdtop` cannot show an original upload filename
for an INSTREAM scan. The application records the job's uploaded filename for its own
scan history; it cannot force the remote daemon's native `clamdtop` to show the name
without daemon filesystem access.

## Production gates

- Maven verification (`mvn -B verify`) and the existing issue #13–#24 regression suite.
- Fresh H2 + PostgreSQL upgrade tests with saved Configuration Center settings.
- EICAR and clean file uploads; expected deletion after successful scans.
- No public clamd port, no scanner mount of the H2 DB/bootstrap password/LDAP secrets.
- Native overlay runtime test, including daemon UID and read-only upload share.
- NGINX, CSRF, local/LDAP sign-in, session revocation and proxy-spoofing regression tests.

This candidate has been **statically** inspected only; Docker/Maven execution and
release CI are required before production publication. Do not publish the server's
existing Compose, `conf/`, `data/`, credentials, or TLS truststore.
