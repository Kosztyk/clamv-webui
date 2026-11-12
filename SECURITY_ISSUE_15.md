# Security issue #15 — bundled scanner exposure

This complete source retains fixes #13 and #14 and adds scanner isolation:

- No host port is published by the default ClamD service. An optional override binds only to 127.0.0.1.
- ClamD sees only read-only data/uploads and its independent signature volume. The Web UI database, bootstrap password, quarantine and configuration are not mounted.
- Native upload scans use the explicit /app/data/uploads path on both sides.
- The scanner image is pinned by version and verified registry digest.
- The separate REST bridge URL is optional and blank by default.
- EICAR smoke assertions use the current API signatures field, with CI checks for live private-state isolation.

Before starting the narrowed native-path mount, create data/uploads for UID/GID 10001; see docs/CLAMD_ISOLATION.md. Keep existing database settings, conf/data contents and keys. No database migration or application version change is required.

Build from the extracted root using the existing command:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

The pinned Alpine scanner image contains linux/amd64. Validation records distinguish the Compose configuration checks and real native EICAR execution from Docker runtime checks, which require a Docker-enabled host/CI runner. CHANGED_FILES.md lists cumulative and incremental file changes.
