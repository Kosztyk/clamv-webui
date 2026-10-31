# Non-root container runtime

The Web UI Dockerfile runs Java and native ClamdTop as the dedicated
`clamav-web` account, **UID 10001, GID 10001**. Its runtime stage declares
`USER 10001:10001`, so Kubernetes can verify a numeric non-root identity.
Package installation and user creation happen during image build.

`/app/data` and `/app/conf` belong to that account. `/app` and the application
JAR remain owned by root; the JAR is read-only. The account has `/bin/sh` for
the command used by `util-linux script` to allocate ClamdTop's PTY. Java uses
port 8080. No privileged container or runtime privilege escalation is needed.

Compose examples additionally drop all Linux capabilities and set
`no-new-privileges`. Rebuild/publish the image before deploying this change;
editing the Dockerfile does not change an image already pulled from a registry.

## New Linux installation with bind mounts

Run these in the deployment directory containing the Compose file:

```bash
mkdir -p conf data
sudo chown -R 10001:10001 ./conf ./data
sudo chmod -R u+rwX ./conf ./data
docker compose up -d
```

The permissions command grants the owner access without making files
world-writable or replacing existing private modes such as `0600`. Do not use
`chmod 777` for the application volumes.

## Upgrade an existing installation

Back up the database, `conf` (including `auth-settings.key`) and `data` first.
Stop only the Web UI, prepare the actual paths mounted into `/app/conf` and
`/app/data`, then recreate it with the new image:

```bash
docker compose stop clamav-web-client
sudo chown -R 10001:10001 ./conf ./data
sudo chmod -R u+rwX ./conf ./data
docker compose up -d --no-deps --force-recreate clamav-web-client
docker compose logs --tail=100 clamav-web-client
```

Substitute the service name and host paths if different. Apply ownership only
to application-owned folders, not to an external PostgreSQL/MySQL/MariaDB data
directory or unrelated scan roots. No Flyway baseline or database reset is
required by the user-ID change. Preserve the existing database settings and
identity encryption key.

Bind mounts obscure the image directories: ownership assigned in the Dockerfile
cannot change a root-owned host folder. Docker also creates a missing bind
source as root, so prepare the folders before first start. If startup reports
`AccessDeniedException` or cannot create a bootstrap password, H2 database,
configuration file or authentication key, check the mounted folders' owner and
permissions. A mount reported as `writable=true` only means Docker permits
writes; it does not make UID 10001 the owner of an existing file.

`FileSystemException: conf/integrations.properties: Operation not permitted`
from `AtomicPropertiesFile.ownerOnly` means the application could not set the
file's owner-only mode. Group write access alone does not permit `chmod` of a
file owned by another user. Apply the ownership migration above to the existing
configuration files as well as their directory. Do not run the application as
root or make secrets world-writable to work around this error. If ownership is
already correct, inspect the backing filesystem's POSIX permission support,
ACLs and mount policy with the storage administrator.

An Nginx `502` during this failure is a consequence of the backend failing to
start. Confirm a `Started ClamAVWebClientApplication` log entry and check the
upstream address/port after startup. See [upgrade verification](UPDATE_AND_VERIFY.md).

Read the one-time password through the container:

```bash
docker compose exec -T clamav-web-client cat /app/data/bootstrap-admin-password
```

## Docker Desktop and named volumes

For new installations on macOS/Windows, Docker-managed named volumes avoid host
UID mapping differences. Replace the Web UI's two bind mounts and add the
top-level volume definitions to the standalone INSTREAM example:

```yaml
services:
  clamav-web-client:
    volumes:
      - clamav-conf:/app/conf
      - clamav-data:/app/data

volumes:
  clamav-conf:
  clamav-data:
```

Keep the existing ClamAV signature volume definition when combining examples.
Fresh Docker volumes inherit the prepared image directory ownership. Existing
named volumes retain their previous ownership and may need an operator-managed
permission migration. Changing a bind mount into an empty named volume does not
copy your old data; migrate it explicitly before switching an existing install.

## Native scans, quarantine and Unix sockets

Native path scans still require the Web UI and ClamD to see identical absolute
paths. Grant UID 10001 read access and directory traversal on approved scan
roots; ClamD separately needs read access to scanned files. The image keeps the
data directory traversable for shared-path deployments. Moving original files
to quarantine also requires write access to their parent directory; a read-only
scan mount cannot support that operation. The UI's own upload/quarantine folders
must remain writable by UID 10001.

For a mounted ClamD Unix socket, grant the Web UI access through the daemon's
numeric socket group, for example `group_add: ["1234"]` with the actual GID.
Keep appropriate socket/directory permissions. The fixed primary group 10001
does not automatically grant access to another service's socket. Configuration
observer files and custom CA/truststore mounts must also be readable by the
runtime identity. INSTREAM with TCP does not require shared filesystem access.

## Kubernetes

This Pod-spec fragment uses existing PVCs and the image's numeric identity:

```yaml
spec:
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    fsGroup: 10001
    fsGroupChangePolicy: OnRootMismatch
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: clamav-web-client
      image: kosztyk/clamav-web-client:v2
      securityContext:
        allowPrivilegeEscalation: false
        capabilities:
          drop: ["ALL"]
      ports:
        - containerPort: 8080
      volumeMounts:
        - name: conf
          mountPath: /app/conf
        - name: data
          mountPath: /app/data
  volumes:
    - name: conf
      persistentVolumeClaim:
        claimName: clamav-conf
    - name: data
      persistentVolumeClaim:
        claimName: clamav-data
```

Use an image rebuilt with this change. Configure ClamD and the database through
your existing environment/Secrets. `fsGroup` handling depends on the storage
driver; storage that does not support ownership adjustment needs permissions
prepared by its operator. `fsGroup` can grant group access but does not make the
application the owner of existing files; persisted files whose private modes
the application manages must be owned by UID 10001. Prepare ownership using the
storage system's supported provisioning/migration process. `runAsNonRoot` is one part of the Kubernetes
[Restricted Pod Security Standard](https://kubernetes.io/docs/concepts/security/pod-security-standards/);
the fragment also supplies the privilege, capability and seccomp settings.

## Verify

After building and starting the repository's Compose deployment:

```bash
bash scripts/smoke-non-root.sh
bash scripts/smoke-eicar.sh
```

The non-root smoke checks image identity and PID 1, dropped effective
capabilities, no-new-privileges, writable bind mounts, read-only application
code, a real native PTY and fresh anonymous Docker volumes. The EICAR test uses
an authenticated session with CSRF and verifies detection, persisted results and
audit records. CI runs both against the built image.
