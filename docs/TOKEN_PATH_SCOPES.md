# Token path roots, job visibility and canonical paths

Issue [#21](https://github.com/Kosztyk/clamv-webui/issues/21) is resolved by applying path restrictions to job history as well as submission. Endpoint restrictions and capabilities still apply independently.

## Visibility rules

| Caller | PATH/WATCH jobs | Uploaded jobs | Audit events |
|---|---|---|---|
| Bearer token with nonempty roots | Recorded canonical target must be under one of its roots | Only uploads recorded as submitted by this token with `API_TOKEN` transport | Only events linked to visible jobs; missing-job and unlinked events hidden |
| Bearer token with empty roots | Endpoint-scoped history when `jobs:read` is granted | Endpoint-scoped history when `jobs:read` is granted | Endpoint-scoped events when `audit:read` is granted |
| Browser/session or Basic identity | Existing role and global policy | Existing role policy | Existing administrator policy |

All rows must also satisfy endpoint grants. Empty roots **deny path submission**, even if `scan:path` is selected; they do not turn a history credential into a tenant-isolated credential. Set roots when issuing a history credential that should be limited. `jobs:read` and `audit:read` remain separate capabilities. Granting neither is still the way to prevent history access entirely.

For a token rooted at `/scandir/a`, PATH/WATCH targets `/scandir/a` and `/scandir/a/file` are eligible; `/scandir/b/file` and `/scandir/a-other/file` are not. The owner of a PATH/WATCH job need not be this token; the path grant determines its visibility. Uploads have no server-path target, so a root-constrained token can read only its own uploaded jobs. Legacy uploads without verifiable token/transport metadata remain hidden from such tokens.

`GET /api/v1/jobs`, job details and `/progress` use the same policy. A known but forbidden job returns 403. List records and `totalItems`/`totalPages` exclude unauthorized jobs. `/api/v1/audit` and the MCP recent-audit resource cannot provide an alternate read of hidden job IDs/verdicts. Sanitized DTOs continue omitting raw targets, stored/quarantine paths, raw exception text and detection-map filenames.

## Root issuance and symlinks

Roots must be existing absolute directories within the application's global allowed scan roots. Issuance resolves the supplied directory and configured global roots before comparing them, and stores the canonical directory name. A symlink alias therefore works even if its text is outside the physical root's lexical prefix. Both containment checks use filesystem path components rather than string-prefix matching.

Roots containing commas/control characters, resolved names incompatible with the existing CSV format, non-directories, missing paths or grants exceeding the 2,048-character storage limit are rejected before a secret is issued. The administrator token table and token metadata show the stored roots.

If `/scandir/alias` points to `/scandir/a` at issuance, the grant is stored as `/scandir/a`. Retargeting the original alias to `/scandir/b` does not move the grant. Roots must remain available as canonical directories; missing/inaccessible roots or a stored root now resolving through a symlink fail closed for path operations and PATH/WATCH history. Ownership-based upload visibility remains independent of directory availability.

Existing tokens with unresolved alias roots from an earlier version are **not silently expanded or rewritten**. Revoke and reissue them after reviewing the intended physical directory. Existing physical canonical roots continue working. No database migration or new configuration field is required.

## Submission and queued work

The bearer access check returns its authorized canonical `Path`. Job creation receives that value and does not reopen the original submitted alias or replace it with a newly resolved target. Current global and daemon policy still applies, and the canonical target is recorded on the job.

Before dispatching PATH/WATCH work, the worker resolves the saved target for validation and rejects it if it now resolves to a different path. Redirecting it to another globally allowed directory therefore produces a visible scan error instead of scanning the replacement. Noncanonical legacy queued targets may need to be resubmitted. Historical job visibility uses the recorded canonical target, so deleting or quarantining a scanned file does not erase access to its history while the root remains available. Malformed/relative/non-normalized stored targets fail closed for root-scoped reads.

Canonical paths are pathname grants, not inode or content snapshots. Operator-controlled roots and remote ClamD filesystem mappings must remain trustworthy. A remote daemon's later path lookup is not an atomic filesystem snapshot; the authorization handoff and worker check do not claim otherwise.

## SQL filtering, pagination and resource use

Queries first narrow candidates by endpoint, job type, escaped component-boundary path prefixes and upload ownership. `%`, `_` and the SQL escape character in directory names are treated literally. A final Java path/ownership check prevents case-insensitive database collations from widening filesystem grants.

Restricted pagination walks all candidate **metadata** in batches of 500 with a stable timestamp/ID cursor, counts only authorized records and loads full job/audit entities only for the selected page. This avoids short pages or inflated counts from SQL overmatches and avoids a file-count cap. Memory remains bounded by a metadata batch and requested page; exact counts require visiting the matching candidate history. Very large authorized histories can therefore cost more than a broad SQL count. Concurrent updates retain normal history-pagination consistency limits. Unrestricted/session history keeps the existing query path.

## Verification and upgrade

```bash
mvn verify
python3 tools/verify-token-path-scopes.py --jar target/clamav-web-client-2.1.0.jar
```

`ApiV1PathScopeIntegrationTest` uses real H2/JPA with case-insensitive string columns; it verifies that SQL equality actually overmatches case before checking that API results/counts remain correct. It covers lists, details, progress, own/foreign uploads, endpoint intersections, audit filtering, escaped roots, 1,201 visible jobs across metadata batches, 501 audit events, symlink issuance/retargeting, alias changes immediately after authorization, deleted-file history, unavailable/legacy roots and invalid issuance inputs. Source-worker tests cover both PATH and WATCH redirection before any daemon command.

The packaged runner uses the real HTTP application and isolated H2/files with a **synthetic ClamD verdict fixture**, including real token issuance, path submissions, uploads, page totals, forbidden reads, linked audit and the administrator page. Its loopback transport simulates a trusted TLS-terminating proxy; it does not claim external TLS, real malware-engine or Docker validation. See [executed results and limits](security/token-path-scopes-validation.json).

Rebuild/recreate the application with the existing deployment settings; keep its database, conf/data and encryption keys. No schema migration is added. Review and reissue legacy unresolved alias grants, and verify a restricted token can read its own allowed history while sibling-root and foreign-upload reads return 403. Earlier [private scanner migration](UPLOAD_RETENTION.md#private-permissions-and-native-scanner-migration) remains applicable to upgrades from before #18.

Primary reference: [Java Path canonicalization and component containment](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html).

## Scan rejection privacy (issue #24)

Untrusted submissions undergo global lexical screening before target resolution
and a final canonical containment check. Configured global root aliases and
permitted in-root aliases continue to work; an arbitrary alias outside every
configured/canonical global root is rejected even if it points inward.
Administrator root selection still resolves a chosen alias and validates its
canonical global containment before issuing a pinned grant.

All restricted Bearer path failures now return the same generic **403 forbidden**,
including missing targets inside a grant, globally denied paths and paths outside
the token roots. This prevents error-status differences from revealing another
tenant's file existence. Session/Basic validation keeps its generic 400 response.
Canonical handoff and queued-target revalidation remain active. See
[full behavior and tests](SCAN_PATH_PRIVACY.md).
