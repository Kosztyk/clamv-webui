# Upload cleanup, permissions and retention — issue #18

Local uploaded copies are temporary scanner inputs. Their job verdict, hash, size, signatures and audit record remain available after the copy is deleted. This policy covers uploads received by the Web UI's browser and local APIs; storage managed by an external REST scanner is that service's responsibility.

## Completion and rollback

| Outcome | Default behavior |
|---|---|
| Clean upload | Delete its stored copy after the OK verdict is persisted and scan streams are closed |
| Infected upload, quarantine enabled and successful | Move into the private quarantine directory; retain its quarantine path and verdict |
| Infected upload, quarantine disabled or unsuccessful | Delete the uploaded copy after the VIRUS_FOUND verdict is persisted |
| Scan error | Retain the copy for diagnosis until upload retention expires |
| Queued/running job | Preserve its file regardless of age |
| Upload transaction rolls back | Remove complete and partially written copies from that batch |
| Database commit fails | Transaction-completion cleanup removes the batch files on confirmed rollback |
| Enqueue fails after a successful commit | Keep the durable QUEUED job and its copy for the existing queue reconciler |

Failed multi-file requests clean all files created by that request. Both browser and API entry points copy/hash outside a database transaction, then commit all job rows in a separate short transaction. File leases remain active across preparation and that commit, so rollback cleanup and retention protection still apply. A transaction with an unknown commit outcome conservatively retains files for later database-aware retention. Cleanup errors are logged and retried by retention; source files from PATH/WATCH scans are never deleted by upload cleanup. See [long filenames and transaction boundaries](UPLOAD_FILENAMES.md).

If quarantine fails, the job page records a warning and both job APIs expose `quarantineStatus: FAILED` with error code `QUARANTINE_FAILED`. The malware verdict remains `VIRUS_FOUND`. Cleanup still attempts to delete the infected uploaded copy after its result is persisted; this does not delete PATH/WATCH source files. Quarantine filenames are bounded opaque IDs, independent of the submitted filename.

## Configuration Center and environment settings

Open **Configuration Center → Scanning → Upload cleanup and history**. Changes are live and use the normal reviewed administrator verification. Deployment overrides remain authoritative.

| GUI field | Property | Environment variable | Default / range |
|---|---|---|---|
| Delete clean uploads | `app.uploads.deleteClean` | `CLAMAV_UPLOADS_DELETE_CLEAN` | `true` |
| Upload retention (hours) | `app.uploads.retentionHours` | `CLAMAV_UPLOADS_RETENTION_HOURS` | `24`; 1–8760 |
| Job history retention (days) | `app.history.jobsRetentionDays` | `CLAMAV_HISTORY_JOBS_RETENTION_DAYS` | `0` keeps history; 1–3650 enables pruning |
| Audit history retention (days) | `app.history.auditRetentionDays` | `CLAMAV_HISTORY_AUDIT_RETENTION_DAYS` | `0` keeps history; 1–3650 enables pruning |

For deployment-managed settings, add only the settings you want to control in Compose:

```yaml
environment:
  CLAMAV_UPLOADS_DELETE_CLEAN: "true"
  CLAMAV_UPLOADS_RETENTION_HOURS: "24"
  # Optional permanent history pruning; omit these to keep all job/audit records.
  CLAMAV_HISTORY_JOBS_RETENTION_DAYS: "90"
  CLAMAV_HISTORY_AUDIT_RETENTION_DAYS: "365"
```

Recreate the container after changing deployment variables. To use GUI management instead, omit those variables. Disabling immediate clean deletion retains clean copies only until the configured upload retention expires. Infected copies are still quarantined or deleted.

## Retention boundaries

The retention task first runs one minute after startup, then hourly. It considers only regular files directly inside the configured upload directory whose names match the application's UUID-prefixed upload format. It never recursively deletes arbitrary directories, follows file symlinks or deletes unknown/manual filenames. Old versions used the same filename format, so eligible legacy copies and rollback orphans are included.

A candidate must be older than the configured retention. A corresponding job protects it while QUEUED/RUNNING, and a recently finished job protects it for the full retention period after completion too. In-progress copies remain protected before their job rows commit. If the database cannot be checked, the file is retained. Changing the configured upload directory does not authorize sweeping a former directory; review old storage separately after its jobs finish.

Job and audit history pruning are independent and **disabled by default**. When enabled, cleanup advances through all old records in 500-row primary-key batches. It removes only FINISHED jobs whose stored artifacts are confirmed absent. Jobs with quarantine paths remain for evidence tracking, even if their history age exceeds the chosen duration. Quarantine files are not automatically removed. Audit retention applies to SQL `audit_events`; the independent configuration audit file and server log rotation remain deployment-managed. It permanently removes old SQL audit records regardless of job retention, so choose it to match your recordkeeping needs.

## Private permissions and native scanner migration

On POSIX filesystems, new upload/quarantine directories use `0700` and files use `0600`. Files are created privately before bytes are written; cleanup also tightens eligible legacy upload permissions. Filesystems without POSIX attributes retain their native ACL policy and must have restricted ACLs configured by the operator.

Native path scanning requires both identical paths and filesystem read permission. The bundled root Compose now builds `clamav-server:1.4.6-uid10001-local` from [Dockerfile.clamd](../Dockerfile.clamd). Its official ClamAV 1.4.6 base remains pinned by digest. The build changes the `clamav` account to UID/GID 10001, matching the Web UI's private upload owner, while retaining the upstream entrypoint and daemon configuration. Signature/log ownership is adjusted; application configuration, database and quarantine are still not mounted into ClamD. Port 3310 remains unpublished, and only uploads are shared read-only.

Rebuild **both services** when using the bundled native scanner:

```bash
sudo install -d -o 10001 -g 10001 -m 0700 data/uploads
docker compose config --quiet
docker compose up -d --build --force-recreate clamav-server clamav-web-client
```

Keep existing database credentials, conf/data volumes and encryption keys. No schema migration is required. An old prebuilt ClamD image with a different daemon UID cannot read new private uploads just because a path is shared. For a separately managed scanner, align its daemon UID with the upload owner or use INSTREAM:

```yaml
environment:
  CLAMAV_UPLOAD_PATH_SCAN_ENABLED: "false"
```

INSTREAM does not need an upload mount or matching filesystem ownership. Native ClamdTop filenames remain available in the UID-aligned bundled setup. The standalone INSTREAM example keeps the official scanner image directly. The optional shared-path override builds the matching-UID variant; use a recent Compose version supporting `dockerfile_inline` and `!reset`, and validate the merged configuration before starting it.

## Verification

`UploadStorageTest` exercises the real scan worker's completion logic with synthetic scan results, clean retention opt-out, infected deletion/quarantine, failed quarantine, active/recent file protection, orphan expiration, leases, symlinks, containment, private modes and collision safety. `UploadBatchPersistenceTest` uses real H2/JPA transactions to verify browser/API multi-file rollback, commit-time database failure, durable queue recovery and multi-batch history pruning. The four fields also participate in the generic configuration review/persistence and deployment override tests.

`scripts/test-eicar-native.py --isolate` runs the rebuilt application with real ClamAV 1.4.6 in separate restricted Linux filesystems. It scans EICAR and a clean fixture through native MULTISCAN with streaming fallback disabled, checks persisted verdicts and audit, waits for both stored copies to disappear, and rechecks private-state/read-only mount isolation. This is not a Docker image execution test. CI builds the derived scanner image and runs the same clean/infected smoke and live isolation checks. See [the delivery validation record](security/upload-retention-validation.json) for executed results and limits.

Upstream references: [official ClamAV Docker documentation](https://docs.clamav.net/manual/Installing/Docker.html), [official 1.4 Alpine Dockerfile and daemon account](https://github.com/Cisco-Talos/clamav-docker/blob/main/clamav/1.4/alpine/Dockerfile).
