# MCP implementation validation — v2.0.0

Validated on 30 October 2026 (Europe/Bucharest). The source retains the earlier
LDAP/OIDC compatibility, Prometheus/Grafana, mounted daemon configuration and
standalone CLI implementations.

| Check | Result |
|---|---|
| Java `mvn clean verify` | 211 cases: **206 passed, 5 skipped**, no failures/errors; application JAR packaged |
| Go `go test -race -count=1 ./...` | **25 top-level tests and 18 named subtests passed**, no race reports |
| Optional Spring integration in default Go suite | Skipped by default, then executed successfully through the integration runner |
| `go vet ./...` | Passed |
| `go mod verify` | Passed |
| Cross-platform standalone binary builds | All six Linux/macOS/Windows amd64/arm64 targets built with Go 1.26.8 and CGO disabled |
| Native MCP stdio against real Spring | All five tools and three resources passed with both Basic/CSRF and real scoped Bearer authentication |
| Streamable HTTP | Real local HTTP listener startup/shutdown, authenticated tool execution and protocol revisions `2025-11-25` and `2026-07-28` passed |
| HTTP access checks | Missing/wrong tokens, invalid Origin, hostile Host and oversized requests rejected |
| Local file confinement | Default denial, size/type restrictions, symlink escape, FIFO nonblocking rejection and directory replacement checks passed |
| OpenAPI/workflow YAML, client JSON and integration Python syntax | Parsed successfully |

The five skipped Java cases are the existing native AF_UNIX transport tests:
this execution environment rejects Unix socket creation. The MCP adapter uses
HTTPS/TCP to the Web UI and did not require Unix sockets locally.

## Actual application integration

`python3 scripts/test-clamctl-integration.py` started an isolated Spring Boot
application with temporary H2/settings/files, a certificate-verified local HTTPS
proxy and a synthetic ClamD TCP protocol fixture. It launched the actual native
Linux `clamctl` executable as an MCP subprocess through the official SDK client.

For both Basic and scoped Bearer credentials the check initialized the MCP
session, discovered the five tools and three resources, streamed clean,
malware-positive and scanner-error uploads through the real Java worker and job
database, read their final results, submitted an authorized path scan, checked
health/database timestamp/threads/queue, listed endpoints and read every
resource. It verified safe error codes, signatures, durations and quarantine
status, and rejected an unauthorized server path and a local file outside the
configured upload root. Closing stdin shut down the native MCP process cleanly.
Existing native CLI login, status, upload/path, get/watch/list and audit checks
also passed in this run.

The ClamD fixture returns predefined observations. This validates the protocol,
streaming, security and persistence integration, not real malware-detection
accuracy. No production service or database was contacted.

## Scope and remaining deployment checks

macOS, Windows and Linux ARM64 executables were cross-compiled and inspected but
not executed on their target operating systems/architectures here. The included
workflow defines future native Linux/macOS/Windows tests; it was not published
or executed on GitHub. Desktop assistant UIs were not used for the protocol
tests. Client configuration examples follow the linked primary documentation
in `MCP.md` and require actual installation paths and private credentials.

The bridge supplies stdio and a loopback stateless HTTP endpoint. Its HTTP
authentication is a separate static Bearer token, not an OAuth authorization
server. Remote clients requiring OAuth need an appropriate gateway and TLS
proxy. Every client of one bridge uses its configured upstream identity;
per-user authorization requires separate identities/processes or a suitable
gateway. No legacy SSE-only endpoint or resource subscriptions are provided.

Quarantine reporting is limited by stored job evidence: older PATH/WATCH
detections can have UNKNOWN status. Raw server filesystem paths and private
exception messages are intentionally excluded from API/MCP results.

The earlier combined release gate's Playwright browser stage remains unrun
because Playwright is absent. The MCP changes do not alter UI templates, browser
authentication routes, schema migrations or Maven dependencies. They add the
official Go MCP SDK and pinned module dependencies, with full license notices.
Production proxy/OAuth/ClamD configurations and external RDBMS profiles were
not exercised by these checks.
