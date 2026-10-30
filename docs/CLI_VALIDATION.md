# clamctl validation — v2.0.0

This records the original CLI implementation baseline. The subsequent MCP
extension and current combined validation are recorded in `MCP_VALIDATION.md`.

Validated on 30 October 2026 (Europe/Bucharest), retaining the previous LDAP,
OIDC, Prometheus/Grafana and mounted-configuration synchronization changes.

## Results

| Check | Result |
|---|---|
| Server `mvn clean verify` | 207 cases: **202 passed, 5 skipped**, zero failures/errors; executable JAR packaged |
| CLI `go test -race -count=1 ./...` | **16 top-level tests passed**, including 14 named subtests; no race reports |
| Optional Spring test in default Go suite | Skipped there, then **executed successfully** through the integration runner below |
| CLI `go vet ./...` | Passed |
| Six release targets | Built successfully with Go 1.26.8 and `CGO_ENABLED=0` |
| Binary verification | Checksums, executable formats and target architectures checked; Linux binaries have no ELF dynamic interpreter |
| Linux native executable | Version/help and real Spring/API pipeline checks passed |
| OpenAPI and GitHub workflow YAML | Parsed successfully |
| Phase 0/1, Phase 2 and Phase 4 source inventories | Passed |
| Existing configuration-view JavaScript checks | Four passed |
| Existing six-language catalog checks | Passed |

The five skipped Java cases are the existing real-AF_UNIX transport tests:
this execution environment rejects Unix socket creation. The CLI uses HTTPS/TCP;
its TLS network and native-process integration checks ran successfully.

## CLI test coverage

The Go suite covers configuration persistence and POSIX permissions, login before
credential persistence, both API token environment names, Basic and stdin login,
flag-like password values, CSRF cookies/headers, table and JSON output, status
failures, endpoint/job/audit queries and filters, streamed multipart uploads,
waited clean/infected/error verdicts and exit codes, NDJSON watching, duplicate
state suppression, request/wait timeouts, cancellation (including credential
input), redirect refusal, invalid input, credential binding to the saved server,
TLS trust, malformed/oversized responses, secret redaction and terminal controls.

Ten Java cases were added: five DTO cases for safe signature values/duration and
five real Spring MVC cases for authentication/CSRF helpers, Basic and Bearer scan
authorization, safe pool statistics and endpoint-scoped job details. The existing
API, identity, database, metrics, policy and UI regression cases still pass.

## Native executable against the real Spring server

`python3 scripts/test-clamctl-integration.py` was run with the built server JAR
and `cli/dist/linux_amd64/clamctl`. It creates an isolated Spring application with
temporary H2/config/data, a local certificate-verified HTTPS proxy and a synthetic
ClamD protocol server. No production service or database is contacted.

The native executable completed:

1. Configuration and verified Basic login.
2. Live daemon status (version, queue, threads and pools) and endpoint listing.
3. A clean multipart upload, through the real Java worker/INSTREAM pipeline.
4. A synthetic malware-positive upload, returning exit code `3`, signature name
   and execution duration through the real persisted job/result DTO.
5. Job get/watch/list and audit listing.
6. Login with a real scoped token issued by the isolated Spring application.
7. An authorized server-path scan through the real API/path policy/worker pipeline.
8. Status with the saved Bearer credential and local logout.

Both Basic-auth CSRF sessions and the scoped Bearer flow were exercised over TLS.
The synthetic ClamD returns defined test verdicts; this verifies protocol/job/API
integration, not the detection accuracy of a real ClamAV engine.

## Remaining platform and deployment checks

- macOS, Windows and Linux ARM64 binaries were cross-compiled and inspected but
  were not executed on those operating systems/architectures here. The included
  GitHub workflow defines native Linux/macOS/Windows tests for a future push/run;
  that workflow was not published or executed on GitHub during this task.
- A production ClamD/SSO/proxy environment and real malware-signature engine were
  not used by the integration runner.
- The combined `scripts/release-gate.sh --static-only` stops at the existing
  Playwright authentication-browser stage because Playwright is absent. Its
  preceding inventory, JavaScript and translation checks pass. Chromium rendering
  and the complete combined release gate are not certified by this report.
- Cross-platform binaries are not Apple/Windows signed or notarized.

No database migrations or Maven dependencies changed. The original CLI used
only the standard library; the subsequent MCP extension adds the official Go
MCP SDK and pinned dependencies. The source distribution includes binaries,
checksums, the build tool, tests, workflow and applicable dependency licenses.
