# ClamAV Web UI MCP server

An HTTPS reverse proxy or Kubernetes Ingress that forwards HTTP to the Web UI
works with the packaged `framework` default; no helper flag is required. Restrict
backend access to that trusted proxy. See [proxy TLS setup](REVERSE_PROXY_TLS.md)
if MCP requests return `403 tls_required`. Keep the public HTTPS URL in the
MCP/`clamctl` configuration.

The official MCP server is included in `clamctl` v2.1.0. It bridges the Web UI's
`/api/v1` REST API using the official Go MCP SDK, pinned to v1.8.0. No Python,
Node.js, Go installation or additional runtime is needed to run the distributed
binary. Deploy the updated Web UI server to obtain the new job diagnostics and
normalized database timestamp.

## Start a local stdio server

Install the executable for your platform from `cli/dist`, then configure its
server and credentials as described in `cli/README.md`. A scoped API token is
recommended for agents; Basic authentication is also supported.

```bash
clamctl config set-server https://clamav.example.com
clamctl login --token-stdin < /secure/web-ui-api-token
clamctl mcp serve --upload-root /srv/artifacts
```

The MCP client launches this command and owns stdin/stdout. Only MCP JSON-RPC
messages go to stdout. Startup and command errors go to stderr. Environment
authentication uses `CLAMAV_API_TOKEN` (alias `CLAMAV_API_KEY`) or
`CLAMAV_USERNAME` plus `CLAMAV_PASSWORD`. Use `--server`, `--config`, `--ca-file`
and `--endpoint` exactly as for other CLI commands. HTTPS upstream certificate
verification, redirect refusal and Basic-auth CSRF sessions remain enabled.

Example for a client accepting the `mcpServers` format, such as Claude Desktop
or Cursor (also provided as `examples/mcp/stdio.json`):

```json
{
  "mcpServers": {
    "clamav": {
      "command": "/usr/local/bin/clamctl",
      "args": ["--config", "/home/operator/.config/clamctl/config.json", "mcp", "serve", "--upload-root", "/srv/artifacts"]
    }
  }
}
```

Replace the command, private configuration path and upload directory with your
actual absolute paths. The client process must be able to read that private
configuration. Windows paths can use forward slashes, for example
`C:/Tools/clamctl.exe` and `C:/Artifacts`. Multiple `--upload-root` arguments are
allowed. A server with no upload roots still exposes every tool but denies local
file uploads. Clients cannot expand these roots through MCP roots notifications.

VS Code's compatible `.vscode/mcp.json` format uses a top-level `servers` object
and `type: "stdio"`; `examples/mcp/vscode.json` supplies that variant. Its newer
portable Copilot configurations use `mcpServers`. Follow the selected client's
configuration format. These examples use saved credentials rather than placing
secrets in a project configuration file.

## Tools

| Tool | Arguments | Result | Required API scopes |
|---|---|---|---|
| `clamav_scan_file` | `file_path`, optional positive `endpoint_id` | Completed job with verdict, signatures, execution duration, quarantine status and safe error | `scan:upload`, `jobs:read` |
| `clamav_scan_path` | `path`, optional positive `endpoint_id` | `jobId` and `status: ACCEPTED` | `scan:path` |
| `clamav_get_job` | `job_id` | Job state, verdict, signatures, duration, quarantine status and safe error | `jobs:read` |
| `clamav_check_health` | none | `health` and `metrics`: connectivity, database version/time/freshness, threads, queue and memory pools | `health:read` |
| `clamav_list_endpoints` | none | `endpoints` array with ID, name and enabled state | `endpoints:read` |

Example tool arguments:

```json
{"file_path":"/srv/artifacts/package.tgz","endpoint_id":1}
```

```json
{"path":"/var/www/uploads","endpoint_id":1}
```

```json
{"job_id":"returned-job-id"}
```

`file_path` is on the **MCP server host**, including in HTTP mode. It must be
absolute and beneath a configured local upload root. File handles are opened
with Go's `os.Root`, preventing symlink escape and path replacement races.
Nonregular, empty and oversized files are rejected. The default bridge limit is
100 MiB; `--max-upload-bytes 209715200` changes it. The Web UI's own upload limits,
daemon limits and scan exclusions also apply. File data is streamed as multipart,
without buffering the entire file. Uploading leaves the local original intact;
the server may quarantine its uploaded copy.

`path` is on the **Web UI/ClamD host**, subject to the Web UI allowed scan roots,
the API token's path roots, endpoint restrictions and daemon policy. The bridge
does not remap it to the MCP host. Server-side path scans may quarantine source
files under the existing server policy, so both scan tools advertise that they
can modify server state. They create new jobs on each invocation and are not
idempotent. Read tools advertise read-only behavior.

`clamav_scan_file` waits up to `--timeout 30m`, polling at `--interval 2s` by
default. Each REST request additionally uses `--request-timeout 2m`; increase it
for large/slow uploads. MCP cancellation interrupts the request/wait. A job that
was accepted continues running on the server; timeout and polling failures
include its ID so the client can resume using `clamav_get_job`. If an upload
connection is lost before the response arrives, acceptance is unknown; inspect
recent jobs before resubmitting.

Tool results contain both `structuredContent` and equivalent JSON text for older
clients, with advertised input/output schemas. Completed scan verdicts `OK`,
`VIRUS_FOUND` and `ERROR` are observations returned in a successful MCP tool
result. API/authentication/validation failures set MCP `isError: true`. Scan
errors do not become a clean verdict. Daemon `DOWN`, stale definitions and
unknown statistics are returned as observations by the health tool.

## Resources

| URI | Content | Required API scope |
|---|---|---|
| `clamav://stats` | Live safe STATS snapshot: threads, queue, pools, memory and check time | `health:read` |
| `clamav://jobs/recent` | Latest 20 authorized jobs, newest first, with pagination metadata | `jobs:read` |
| `clamav://audit/recent` | Latest 20 authorized security audit events, newest first | `audit:read` |

Resources have MIME type `application/json`. Each read queries the API. Reads
are not cached and subscriptions are not advertised. `--endpoint ID` selects
the endpoint for health/statistics and filters recent jobs. Endpoint overrides
on scan tools do not change this default. With nonempty token path roots, recent
jobs include only in-root PATH/WATCH jobs and this token's own uploads; recent
audit includes only events linked to those visible jobs. Page totals exclude
hidden records too. Empty-root history credentials retain endpoint-wide reads,
while still denying path submissions. See [root semantics and legacy alias migration](TOKEN_PATH_SCOPES.md).
A resource read without its required
scope fails rather than returning another identity's data.

## Job diagnostics and timestamps

The REST job DTO now adds `quarantineStatus` and a nullable `error` object.
It continues to omit server paths, raw detection JSON and raw exception text.

| `quarantineStatus` | Meaning |
|---|---|
| `PENDING` | The job has not finished and no quarantine location is recorded |
| `RECORDED` | A successful quarantine operation recorded a location; this is not a current disk-presence check |
| `FAILED` | A quarantine error was recorded; the infected verdict remains `VIRUS_FOUND` |
| `NOT_RECORDED` | A finished infected upload has no recorded quarantine operation, for example when quarantine is disabled |
| `NOT_APPLICABLE` | A finished clean job has nothing to quarantine |
| `UNKNOWN` | No reliable per-job evidence, including older PATH/WATCH detections that can quarantine files without recording their locations |

`error` contains fixed `code`/`message` values for verdict `ERROR` or a recorded
quarantine failure alongside verdict `VIRUS_FOUND`.
Codes are `POLICY_REJECTED`, `NO_ELIGIBLE_FILES`, `CONFIGURATION_UNAVAILABLE`,
`LIMIT_EXCEEDED`, `QUARANTINE_FAILED`, or the fallback `SCAN_FAILED`. An administrator can use the job
ID to investigate private diagnostic details in server logs. The bridge does not
make historical quarantine state more certain than the server record allows.

An error object alongside `VIRUS_FOUND` reports incomplete quarantine, not a
replacement scan verdict. CLI/MCP consumers must continue treating the malware
detection as positive. See [upload and quarantine behavior](UPLOAD_FILENAMES.md).

The health DTO adds `signatureDatabaseTimestamp`, an RFC 3339 UTC timestamp
parsed using the server's `api.v1.clamd.database-timezone` (default UTC). It is
null for missing/unparseable daemon timestamps. The original daemon timestamp
string, signature version and freshness remain available. Missing pool/thread/
queue values remain absent or null rather than invented zeros.

## Optional Streamable HTTP transport

For a client using HTTP, run a dedicated bridge identity on a host with access to
the required files. Generate a separate random MCP token, for example with
`openssl rand -hex 32`, store it in a private file, and start:

```bash
chmod 600 /secure/mcp-http-token
clamctl mcp serve --transport http --listen 127.0.0.1:8090 \
  --http-token-file /secure/mcp-http-token --upload-root /srv/artifacts
```

Alternatively set `CLAMAV_MCP_HTTP_TOKEN` in the service environment. The file
option takes precedence. Tokens must be at least 32 characters, without
whitespace, and different from the upstream API credential. All `/mcp` requests
require `Authorization: Bearer <MCP token>`, including discovery. This token is
validated by the bridge and is never passed to the Web UI; the upstream API
credential is configured separately. All clients of one bridge use that one
API identity. Run separate bridge processes for different privileges.

HTTP binds only to literal loopback addresses. For remote use, put it behind a
TLS reverse proxy on the same host. Proxy `/mcp` without buffering, set an
upstream Host of `127.0.0.1:8090` (or the bound loopback address), preserve the
client's MCP Authorization header and set a response timeout longer than the
scan wait. Do not expose the plaintext listener through a public port mapping.
The normal SDK localhost Host protection stays enabled.

The bridge uses stateless Streamable HTTP with JSON responses, supporting the
SDK's current `2026-07-28` protocol and older negotiated versions. Standalone
GET event streams and DELETE sessions return 405. There is no legacy `/sse`
transport, resumable event store or OAuth authorization server in this bridge.
Clients accepting static Bearer headers can connect directly; remote clients
requiring OAuth need a compatible OAuth gateway in front of it. Examples do not
claim a completed sign-in setup for every hosted assistant.

Requests with an Origin header are rejected by default. Add an exact trusted
HTTPS origin using repeatable `--allowed-origin https://assistant.example.com`
only if the selected client sends one. Wildcards, null origins and origins with
paths are refused. Browser CORS access is not enabled automatically. Native
server-to-server MCP clients normally omit Origin. Both transports limit inbound
MCP messages to 1 MiB and allow up to four active API operations per process.

`examples/mcp/http.json` illustrates a client with URL/custom-header support.
Replace its placeholder token using the client's private secret mechanism. Its
MCP URL is distinct from the upstream Web UI REST URL.

## Build and validate

Building requires Go 1.25+; the release builder uses Go 1.26.8. Dependencies are
pinned in `cli/go.mod` and `cli/go.sum`; third-party notices and license texts are
under `cli/THIRD_PARTY_NOTICES.md` and `cli/third_party/licenses`.

```bash
cd cli
go test -race -count=1 ./...
go vet ./...
go run ./tools/build.go
```

From the source root, after packaging the Java JAR and CLI binaries:

```bash
python3 scripts/test-clamctl-integration.py
```

The integration runner includes native MCP stdio discovery, all five tools and
all three resources, with Basic/CSRF and real scoped Bearer authentication
against the isolated Spring application. ClamD is a synthetic wire fixture.
See `docs/MCP_VALIDATION.md` for recorded checks and deployment limits.

Reference documentation used for the protocol and examples:

- [Official Go MCP SDK](https://github.com/modelcontextprotocol/go-sdk/tree/v1.8.0).
- [MCP transports](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports).
- [Local client configuration](https://modelcontextprotocol.io/docs/develop/connect-local-servers).
- [Cursor MCP configuration](https://cursor.com/docs/mcp).
- [VS Code MCP configuration](https://code.visualstudio.com/docs/agent-customization/mcp-servers).
