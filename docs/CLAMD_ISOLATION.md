# Bundled ClamD isolation — issue #15

The default Compose services keep ClamD on the Compose network. The Web UI connects to `clamav-server:3310`; the scanner publishes no host port. ClamD has no authentication. Never expose port 3310 to an untrusted LAN or the Internet, even when the Web UI itself is authenticated or behind HTTPS.

## Private application state and shared inputs

| Storage | Web UI | ClamD |
|---|---|---|
| `./conf` | Read/write | Not mounted |
| `./data` (H2, bootstrap password, quarantine) | Read/write | Not mounted |
| `./data/uploads` | Read/write through `./data` | Read-only at `/app/data/uploads` |
| `clamav-signatures` | Not mounted | Read/write at `/var/lib/clamav` |
| Optional `./scandir` | Read-only | Read-only at the same `/scandir` path |

`CLAMAV_UPLOAD_DIR=/app/data/uploads` explicitly matches the native scan path in both containers. Upload path scanning stays enabled in the root Compose, with INSTREAM fallback disabled. ClamdTop can therefore continue to show native uploaded-file paths. The standalone INSTREAM example shares no application storage; its optional shared-path override uses the same narrowed upload mount.

The scanner must never receive `./data:/app/data`, a parent of the private state, or `./conf`. A read-only whole-data mount still reveals password hashes and bootstrap credentials. Read-only alone is not sufficient isolation.

Prepare the upload directory before starting the narrowed bind mount:

```bash
sudo install -d -o 10001 -g 10001 -m 0700 data/uploads
```

The default mount uses `bind.create_host_path: false` so a missing path fails clearly rather than being silently created with root ownership. Existing uploads remain subject to the configured retention; see [upload lifecycle and migration](UPLOAD_RETENTION.md). The optional deployment helper also prepares the upload directory before starting a fresh bundled scanner. Files in that directory must be readable by ClamD; new uploads use private `0700` directories and `0600` files; the native scanner build maps its `clamav` account to the matching UID/GID 10001. Retain all existing database settings, encryption keys and conf/data volumes when updating.

## Update an existing bundled scanner

Remove the old `3310:3310` publication and `./data:/app/data:ro` mount from the
**ClamD service**. Keep the Web UI's own data mount and database settings. Adding
an upload-only override does not remove an older parent mount: Compose merges
volumes by target, so both would remain unless the old `/app/data` target is
removed from the base/override that declared it.

After preparing `data/uploads`, applying the new daemon image pin and matching
upload setting, recreate the affected containers. Editing YAML does not change
an already running container:

```bash
docker compose config --quiet
docker compose up -d --build --force-recreate clamav-server clamav-web-client
```

Use your existing Compose files/service names and retain the existing database
credentials and Web UI conf/data contents. Never remove the data volume to
apply this change.

## Optional host-only diagnostics

Use the loopback override only when a host process needs direct scanner access:

```bash
docker compose -f docker-compose.yml \
  -f examples/compose/clamd-loopback.override.yaml up -d
```

It publishes `127.0.0.1:3310:3310`. This limits network reachability and does not add authentication; local processes can still issue ClamD commands. Remote scanners should use a separately controlled private network or tunnel, with no untrusted ingress.

## Image pin and optional REST bridge

The native-path scanner is now a local UID-aligned build from `Dockerfile.clamd`, with its official base pinned to:

```text
clamav/clamav:1.4.6@sha256:cbc30de94b6e9b33ac14136ff45306cee02a1f9ae522394a3cc5b9215d1affe9
```

The digest was verified against the upstream Docker registry manifest. This Alpine image manifest contains `linux/amd64`; select and verify a suitable upstream image for other architectures. The upstream Debian image is multi-architecture, but is a separate image choice rather than an implicit platform substitution. Pin metadata is in `docs/security/clamd-image-pin.json`.

The standalone INSTREAM example uses that official image directly. The root/native setup derives `clamav-server:1.4.6-uid10001-local` from the pinned base, changing only the scanner account IDs and signature/log ownership. Rebuild both services when upgrading to private uploads. A digest fixes the upstream base image. FreshClam still updates signature databases in the independent volume during production operation. Review and refresh the image pin when applying upstream engine or base-image security updates.

`CLAMAV_REST_URL` is optional and blank by default. Set it only for an existing separate REST scanner reachable from the Web UI container, with `CLAMAV_REST_API_KEY` when required. It is unrelated to the internal ClamD TCP connection. No private author's LAN URL is preconfigured.

## Checks and CI

```bash
python3 scripts/test-clamd-compose-isolation.py
bash scripts/smoke-eicar.sh
python3 scripts/test-clamd-compose-isolation.py --runtime
```

The Compose checks use Docker Compose's own rendered JSON, including root/standalone and shared-path override combinations. They check host port exposure, narrow read-only upload mounts, the application upload path, the digest pin, optional REST settings and the explicit loopback override.

The runtime check is intended for the fresh H2 CI deployment after the smoke test: it confirms that the Web UI's actual H2 file and bootstrap password exist, that the live scanner cannot read either, that its mount is read-only and that it has no host port bindings. Do not run this CI setup against a production deployment with a different database layout.

`.github/workflows/phase0-ci.yml` builds the app image, starts the root Compose with an isolated H2 database, runs the EICAR smoke and then performs the live isolation checks. Its CI-only override disables FreshClam because the pinned non-base image includes signatures; production updater behavior is unchanged. The EICAR test reads the API's `signatures` field and resolves the actual published Web UI port, so the root Compose's port 8081 is handled automatically.

For hosts without Docker, `scripts/test-eicar-native.py` can run the same EICAR shell smoke against a real native ClamD and executable JAR. That verifies native scan execution with fallback disabled. With `--isolate` and bubblewrap, it also runs Web UI and ClamD in separate restricted Linux filesystems with the Compose-style mounts, verifies ClamD cannot scan the actual Web UI H2/bootstrap files, and verifies the upload mount rejects writes. These checks are explicitly not a Docker image/network test. See `docs/security/clamd-isolation-validation.json` for the checks actually executed and their limits.

References: [official ClamAV Docker documentation](https://docs.clamav.net/manual/Installing/Docker.html), [ClamAV 1.4 support policy](https://docs.clamav.net/faq/faq-eol.html), [upstream image repository](https://github.com/Cisco-Talos/clamav-docker).
