# Multipart temporary directory in Configuration Center (v2.1.0beta2)

**Location:** Administration → Configuration Center → Scanning → Scan policy & upload storage → Advanced → **Multipart temporary directory**.

| Field | Value |
|---|---|
| Spring property | `spring.servlet.multipart.location` |
| Docker Compose override | `SPRING_SERVLET_MULTIPART_LOCATION` |
| Docker image fallback | `/app/data/tmp/multipart` |
| Standalone JAR fallback | Servlet container / operating system temp directory |
| Apply | **Restart required** |

## Docker setup

The Docker image now sets `CLAMAV_CONTAINER_MULTIPART_DEFAULT=/app/data/tmp/multipart`, **not** `SPRING_SERVLET_MULTIPART_LOCATION`. The startup script still creates the private default spool (`0700`), and the packaged Spring property uses that fallback when no GUI or deployment override is present. This means the field is no longer locked merely because the user started the official Docker image.

**To configure in the GUI:** Do not set `SPRING_SERVLET_MULTIPART_LOCATION` in Docker Compose. The default image spool is already `/app/data/tmp/multipart`. To select a different directory, create it in advance in the **Web UI** container and ensure it is an absolute, existing, writable, non-symlink directory with private `0700` access and ownership suitable for UID 10001. It must not overlap the upload or quarantine directories. Save in Configuration Center, then recreate/restart the application container. Changing this setting does not create a Docker volume or mount.

Example for administrators who prefer immutable deployment configuration instead of GUI management:

```yaml
services:
  clamav-web-client:
    build: .
    image: clamav-web-client:2.1.0beta2-local
    environment:
      SPRING_SERVLET_MULTIPART_LOCATION: "/app/data/tmp/multipart"
    volumes:
      - ./data:/app/data
      - ./conf:/app/conf
```

The image shown here must be built **from this updated source ZIP**, since an older published tag does not contain this new GUI control.

With the explicit Compose variable above, the Configuration Center field is **locked** and displays **Controlled by deployment configuration**. Remove that entry and recreate the container to manage it in the GUI.

**Blank saved path:** Explicitly saving a blank path uses the servlet container's platform temp directory, potentially disabling the same-volume rename optimization. Clearing a saved field is not the same as leaving a new install unconfigured (where the Docker fallback applies).

## Security and compatibility

- Existing uploaded files and ClamAV directory-scanning permissions remain unchanged (`0700`/`0600`), and the clamd container still receives only its explicitly configured upload-only mount.
- This is a Tomcat **startup-bound** property, not a live setting. The UI displays pending restart.
- Only the Web UI container needs the multipart spool; never mount this directory into the ClamAV daemon.
- Keep the spool and uploads on the same filesystem to make a servlet-backed `transferTo(File)` move possible. Containers may still copy in some environments.
- This source update does not modify the runtime version string or any issue #25–#30 security logic.
- Before release, verify a real Docker restart and a clean/EICAR upload using a saved GUI path; the test suite is not a substitute for that runtime check.
