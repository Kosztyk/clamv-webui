# Upload filenames, quarantine evidence and database transactions

Issue [#19](https://github.com/Kosztyk/clamv-webui/issues/19) covers oversized filesystem components, silent quarantine errors and database connections retained during large upload copies. This edition addresses all three and preserves the upload cleanup/retention behavior from [issue #18](UPLOAD_RETENTION.md).

## Display names and storage names

The submitted filename is a job label, separate from its storage component:

| Use | Rule |
|---|---|
| Job `target` | Strip client path components, replace control characters, use `upload` for an empty/invalid label, and cap at 1,024 UTF-16 units without splitting a surrogate pair |
| Upload disk component | A 32-character hex ID, `-`, and at most 120 ASCII characters from `[a-zA-Z0-9._-]`; at most 153 bytes in total |
| Extension | Preserve the last extension when it is at most 20 characters, including the dot, within the 120-character name bound |
| Uploaded copy in quarantine | The job's 32-character hex ID followed by `.bin`; 36 ASCII bytes |
| PATH/WATCH file in quarantine | A fresh 32-character hex ID followed by `.bin` for each file |

A 400-character ASCII or Unicode filename is preserved in the job's display label and does not create an oversized filesystem component. Duplicate labels have distinct ID-prefixed storage names. Quarantine uses a no-clobber move and owner-private permissions; it cannot exceed a filesystem component limit merely because the submitted name was long.

Existing stored and quarantined files keep their names. The UUID-prefixed upload format remains compatible with the retention task. Job labels longer than 1,024 UTF-16 units are shortened. Storage limits, allowed paths, upload byte limits and scan policy still apply.

## Copy first, commit metadata afterward

Both browser and API uploads follow the same sequence:

1. Validate the full batch, reserve private upload files and hold in-flight storage leases.
2. Copy each stream while computing SHA-256 with `DigestInputStream`, without a second file read. No upload database transaction surrounds this phase.
3. Open a separate short transaction through `UploadJobCommitter`, save/flush all prepared job rows, and commit the batch.
4. Enqueue scans only after successful commit. Release leases after the durable outcome is known.

The upload entry point rejects an already-active caller transaction before any copy begins. `spring.jpa.open-in-view=false` is also the application default, so a request-scoped persistence context does not retain a connection acquired earlier in the request. Preserve this default when overriding Spring settings.

If copying, hashing or the job insert fails, the batch's files are removed. Confirmed commit rollback also removes them. If enqueue fails after commit, durable jobs and copies remain for queue reconciliation. Unknown commit outcomes conservatively retain the files for database-aware retention. These behaviors are tested against real H2/JPA transactions, including commit-time constraint failure. Source files from PATH/WATCH scans are outside upload deletion.

## Visible quarantine failures

When quarantine is enabled but a move or permission-hardening step fails, the job detail page displays a warning. Both the legacy job API and `/api/v1/jobs/{id}` return safe evidence such as:

```json
{
  "verdict": "VIRUS_FOUND",
  "quarantineStatus": "FAILED",
  "error": {
    "code": "QUARANTINE_FAILED",
    "message": "Malware was detected, but quarantine could not be completed. An administrator can inspect this job."
  }
}
```

This is a partial response example. The malware verdict, signatures and timestamps remain available. Raw filesystem paths and exception text are not added to the public DTO. An administrator can inspect private job details and server logs. A move followed by a permission-hardening failure retains its recorded location while still reporting `FAILED`.

Disabling quarantine is not a quarantine failure; an infected upload without a recorded move remains `NOT_RECORDED`. Historical evidence is not retroactively invented. PATH/WATCH jobs without per-file evidence may remain `UNKNOWN` unless a failure was explicitly recorded. API/MCP consumers must allow `error` alongside `VIRUS_FOUND` and continue treating it as a malware detection.

After a persisted infected upload result, upload cleanup still attempts to remove any copy left in upload storage. Quarantined copies remain operator-managed. A failed quarantine attempt on a PATH/WATCH source does not authorize upload cleanup to delete that source. See [retention boundaries](UPLOAD_RETENTION.md#retention-boundaries).

## Verification

Run the Java suite with JDK 21:

```bash
mvn verify
```

`UploadBatchPersistenceTest` covers 400-character ASCII and Unicode labels, bounded storage components, copied bytes, hashes, rollback and the transaction boundary. Its application Hikari pool has exactly **one connection**. Three concurrent uploads block mid-copy after a real pre-copy SQL query; all three observe no active transaction, the pool reports zero borrowed connections, and an independent `SELECT 1` succeeds while they remain blocked. On release, all three job batches persist and enqueue. A separate direct Flyway migration datasource prepares the same H2 database without enlarging the tested application pool. Another test rejects an ambient transaction before opening the upload stream.

`UploadStorageTest` moves a file with a 255-byte source component into a short quarantine name and covers failed-quarantine evidence/cleanup. `ApiV1CliSupportIntegrationTest` verifies both APIs and the administrator job page expose failure while preserving `VIRUS_FOUND` and masking server paths. Existing watcher, authentication and lifecycle regressions remain in the full suite.

`scripts/test-eicar-native.py --isolate` runs the packaged application and real ClamAV through native MULTISCAN with streaming fallback disabled. It submits a clean upload with a 400-character name and checks persisted verdicts, audit, completed-copy deletion and Linux filesystem isolation. Add `--quarantine-failure` to force an unwritable quarantine destination and verify the public `FAILED` / `QUARANTINE_FAILED` outcome for EICAR. This runner needs the real engine, shared libraries and a packaged JAR as described by its arguments; it does not execute a Docker image.

For an existing smoke environment, `scripts/smoke-eicar.sh` also accepts an optional safe ASCII `CLAMAV_SMOKE_FILENAME` and `CLAMAV_SMOKE_EXPECT_QUARANTINE_FAILED=true`. The latter requires an enabled, deliberately failing quarantine destination in an isolated test deployment. Do not change production quarantine settings merely to run this check.

See [executed results and limits](security/upload-filenames-validation.json). No database schema migration or new Configuration Center field is required. Rebuild and recreate the application container using the existing deployment settings; retain its database, conf/data and encryption keys. Follow the existing [scanner migration instructions](UPLOAD_RETENTION.md#private-permissions-and-native-scanner-migration) when upgrading from a version before #18.
