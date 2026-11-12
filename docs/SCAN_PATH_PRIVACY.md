# Scan path validation privacy (issue #24)

An OPERATOR's scan submission no longer distinguishes an existing path outside
allowed scan roots from a missing or inaccessible target. The shared validator
returns one message:

> Path is not allowed or does not exist

The error omits the rejected path and filesystem exception cause. Directory
traversal, sibling-prefix tricks, missing targets, dangling/looping links and
symlink escapes remain denied. Rejected submissions create no scan job.

## Validation order

1. Normalize the submitted path lexically to an absolute path. This does not
   inspect the submitted target on disk.
2. Resolve the trusted, administrator-configured roots and retain both their
   configured spellings and canonical directory paths.
3. Check that the lexical target is below one of those root spellings. Reject an
   outside target immediately, without calling its `toRealPath()` or inspecting
   its filesystem metadata.
4. Resolve an eligible target, then re-check its canonical path against the
   canonical roots. Reject symlinks that escape them.

Only configured roots are inspected before the prefix check. Missing, unreadable
or non-directory roots do not expand access. Normalization handles `..`, and
path-component comparison prevents `/scandir-other` matching `/scandir`.

Configured root symlinks remain usable through their configured or canonical
spelling. In-root aliases resolving to another permitted path remain usable.
An **arbitrary alias outside every configured/canonical root is rejected even
when it points inward**. Use the configured root spelling or the real in-root
path for scan submission.

Administrator-only token root selection can still resolve a chosen directory
alias before checking its canonical containment in the global roots. Issued roots
remain canonical and pinned. This is separate from untrusted scan submission;
OPERATOR users cannot issue or change token grants. The canonical authorization
result is still passed directly to job creation, preserving the #21 protection
against re-resolving the original submitted alias. Queued PATH/WATCH work retains
its execution-time containment/redirect checks.

## Public responses

| Entry point | Rejected target response |
|---|---|
| Browser `/scan/path` | Same generic `errorMsg`, without path/cause text |
| Legacy `/api/scan/path`, session or Basic | Existing generic `400 invalid_request` response |
| v1 session/Basic path submission | Existing generic `400 invalid_request` response |
| Restricted v1 Bearer path submission | Generic `403 forbidden` for missing, globally denied, symlink-denied and token-root-denied targets alike |

Bearer path failures now use one forbidden status, including missing targets
within a grant. This closes the secondary existence oracle between a token's
roots and other globally permitted paths. Clients should treat the rejection as
a failed authorization/validation and check their configured roots and mounts.
The v1 envelope retains its normal timestamp, which is not compared as error
content. Required-field/body validation, authentication, TLS, CSRF and rate-limit
responses retain their respective behavior.

Daemon exclusions and file-size checks apply after global path containment.
The existing exclusion check for the submitted spelling is retained after that
boundary, as well as the canonical exclusion check. Filesystem errors for
unsubmitted, inaccessible engine data or an execution-time change remain subject
to the existing job/error policy; the change does not promise filesystem snapshot
isolation or retroactively stop work already executing.

## Deployment and verification

Rebuild/recreate the application. No configuration flag, schema migration or new
dependency is required. Keep the intended read-only scan mounts and explicit
`app.allowedScanRoots`. The error deliberately does not reveal whether a denied
path exists: administrators should diagnose mounts/permissions from the host and
configured roots, rather than probing private container paths through scans.

```sh
mvn verify
python3 tools/verify-path-privacy.py --output /tmp/path-privacy.json
```

Unit tests verify lexical rejection before any target resolution, uniform errors,
root aliases, symlink escapes, and pinned canonical results. Integration tests use
real operator form authentication and the actual path/job services for UI,
legacy session/Basic and v1 session/Bearer requests; job execution is mocked to
verify denial before queueing. The packaged runner uses the real executable JAR,
Tomcat and isolated H2/files, with a synthetic ClamD peer and explicitly trusted
loopback proxy headers for machine HTTPS checks. See [current results and
limits](security/path-privacy-validation.json).
