# clamctl — ClamAV Web UI CLI v2.1.0

`clamctl` talks to the Web UI's `/api/v1` API. Each platform uses one executable;
Python, Java, Go, Docker and ClamAV are not required on the CLI machine. The Web UI
server and its ClamD backend remain separate services.

## Ready-built executables

The full-source distribution includes these files under `cli/dist`:

| Platform | Executable |
|---|---|
| Linux x86-64 | `linux_amd64/clamctl` |
| Linux ARM64 | `linux_arm64/clamctl` |
| macOS Intel | `darwin_amd64/clamctl` |
| macOS Apple Silicon | `darwin_arm64/clamctl` |
| Windows x86-64 | `windows_amd64/clamctl.exe` |
| Windows ARM64 | `windows_arm64/clamctl.exe` |

Copy the matching executable to a directory on your PATH. On Linux/macOS, run
`chmod +x clamctl` if the extraction tool did not preserve executable permissions.
In PowerShell, a binary in the current directory is invoked as `./clamctl.exe`.
The binaries use `CGO_ENABLED=0`; Linux executables are statically linked. macOS
and Windows use their normal operating-system APIs. Apple/Windows code signing
and notarization are not supplied.

Verify the checksums from `cli/dist`:

```bash
sha256sum -c SHA256SUMS         # Linux
shasum -a 256 -c SHA256SUMS     # macOS
```

Windows example:

```powershell
Get-FileHash ./windows_amd64/clamctl.exe -Algorithm SHA256
```

Compare with `SHA256SUMS`. `manifest.json` records each target, compiler version,
version and checksum.

## Configure and authenticate

```bash
clamctl config set-server https://clamav.example.com
clamctl login --username admin --password-stdin < password-file
clamctl status
```

`login --username admin --password 'your-password'` is also supported. Stdin input
avoids putting the credential in the command's arguments. A successful login is
verified against the server before credentials are saved. No token issuance or
privilege change is performed by `login`.

For API tokens, create an appropriately scoped token under Administration → API
Tokens, then either save it with `clamctl login --token-stdin < token-file` or
supply it through the environment without logging in:

```bash
export CLAMAV_SERVER=https://clamav.example.com
export CLAMAV_API_TOKEN="$(cat /secure/path/clamav-token)"
clamctl status --json
```

`CLAMAV_API_KEY` is an alias for the same opaque Bearer token, not a different
server-side key mechanism. If both are set, `CLAMAV_API_TOKEN` takes precedence.

PowerShell example using a token supplied by a CI secret:

```powershell
$env:CLAMAV_SERVER = 'https://clamav.example.com'
$env:CLAMAV_API_TOKEN = $env:CLAMAV_CI_SECRET
./clamctl.exe scan upload ./payload.exe --wait --json
exit $LASTEXITCODE
```

Basic authentication may instead use `CLAMAV_USERNAME` and `CLAMAV_PASSWORD`.
Normal commands prefer environment token, environment Basic credentials, then
saved credentials. Explicit login flags select that login's authentication mode.

Saved credentials are plaintext in the per-user JSON configuration file, written
with `0600` permissions on POSIX. Windows uses the directory's inherited ACLs;
keep custom configuration locations private. For CI, environment credentials
avoid persistence. `config show` never displays passwords or tokens. `logout`
removes local saved credentials; it does not revoke an API token on the server.

Default configuration locations follow the operating system:

- Linux: `$XDG_CONFIG_HOME/clamctl/config.json` or `~/.config/clamctl/config.json`.
- macOS: `~/Library/Application Support/clamctl/config.json`.
- Windows: `%AppData%/clamctl/config.json`.

Override with `--config FILE` or `CLAMAV_CONFIG`. Server selection is `--server`,
then `CLAMAV_SERVER`, then saved configuration. Both a base application URL and a
URL ending in `/api/v1` are accepted, including a reverse-proxy context path.
Changing the configured server clears saved credentials. A one-off server override
cannot silently reuse credentials stored for another server.

## Commands

```bash
clamctl config show
clamctl status --endpoint 1
clamctl endpoints list

clamctl scan upload ./suspicious.bin
clamctl scan upload ./payload.exe --wait --json
clamctl scan upload ./one.bin ./two.bin --wait --endpoint 1
clamctl scan path /var/www/uploads --wait --endpoint 1

clamctl jobs list --status RUNNING
clamctl jobs list --verdict VIRUS_FOUND --limit 25 --page 0 --json
clamctl jobs get JOB_ID
clamctl jobs watch JOB_ID --interval 2s --timeout 30m
clamctl audit list --limit 20
clamctl version
clamctl --help
```

Options may appear before or after filenames and other operands. Use `--` before
an operand whose name starts with a dash; put options before that separator.
Upload files must be nonempty regular files. Multipart file contents are streamed
instead of buffered in memory. Uploading does not delete or modify local files.
Server paths are passed unchanged to the server; they are not local CLI paths.

Without `--wait`, scans return their accepted job IDs and exit immediately.
With `--wait`, the CLI polls until a final verdict and fetches signature details.
The default polling interval is two seconds and the overall wait limit is 30
minutes. Set `--interval` and `--timeout` to positive Go duration values such as
`500ms`, `10s` or `1h`. Each HTTP request has its own `--request-timeout` (default
two minutes); increase it for slow or large uploads.

`jobs watch` reports state changes and the final job. This is status polling,
not raw daemon log tailing or a byte-level scan percentage. Ctrl+C cancels the
active request/wait; accepted server jobs continue to run.

## Output and CI exit codes

Human output uses tables. `--json` emits one JSON document per normal command;
errors are JSON on stderr. `scan upload --wait --json` emits one job object for a
single upload, or `{"jobs":[...]}` for several uploads. It does not mix progress
text into stdout. `jobs watch --json` emits newline-delimited `progress` events
and one final `result` event.

| Exit code | Meaning |
|---|---|
| `0` | Successful command, clean completed scan, or accepted asynchronous scan |
| `1` | API/network error, scan error or unhealthy/stale daemon |
| `2` | Invalid arguments, missing/invalid configuration or unreadable local input |
| `3` | Completed scan contains malware |
| `4` | Request or polling timeout |
| `130` | Interrupted/cancelled |

`jobs get` and `jobs watch` also return `3` for a malware-positive finished job.
A multi-upload wait returns `1` if any job errors, otherwise `3` if any job finds
malware. `jobs list` and `audit list` return `0` when the query succeeds, regardless
of historical verdicts. `--limit` supports 1–100 records per page; `--page` starts
at zero. Scan jobs expose deduplicated signatures and execution duration, without
internal stored paths, quarantine paths or raw daemon error messages.

## Permissions and TLS

- `status`: `health:read`; endpoint listing: `endpoints:read`.
- Upload: `scan:upload`; server path: `scan:path` with explicit authorized path roots.
- Job listing/details/watch and `--wait`: `jobs:read`.
- Audit: `audit:read` (or a local administrator's Basic credentials).

Endpoint restrictions and existing application scan policy remain authoritative.
The CLI does not grant roles or bypass upload limits/exclusions. Basic-auth writes
fetch the normal session CSRF token and retain its session cookie. Bearer writes
use the existing scoped machine-authentication flow. Browser CSRF protection
remains enabled.

HTTPS and certificate verification are required. Enterprise/private CAs can be
added with `--ca-file /path/to/ca.pem` or `CLAMAV_CA_FILE`; a CA supplied during
`config set-server` or `login` is saved as an absolute path. There is no certificate
verification bypass. HTTP redirects are refused to avoid forwarding credentials
or replaying scan requests. HTTPS terminated at a reverse proxy requires the
server's documented trusted-forwarded-header configuration.

## Build, test and release

Go 1.25+ is required only when building from source. Release binaries in this
distribution were built with Go 1.26.8. MCP support uses the pinned official Go
MCP SDK and its dependencies in `go.mod`/`go.sum`; the executable still needs no
additional runtime. See `THIRD_PARTY_NOTICES.md` for dependency licenses.

```bash
cd cli
go test -race -count=1 ./...
go vet ./...
go run ./tools/build.go
```

The builder compiles all six targets with `CGO_ENABLED=0`, stripped symbols and
trimmed source paths, then writes checksums and a manifest. A single target can
be selected with `-targets linux/amd64`. Source builds do not require the server
JAR. `.github/workflows/clamctl.yml` defines native Linux/macOS/Windows tests,
cross-platform builds and the real Spring/native CLI integration check.

The optional full pipeline test requires the built server JAR, Go and the native
Linux CLI, then runs from the source root:

```bash
python3 scripts/test-clamctl-integration.py
```

It starts an isolated Spring application, temporary H2/settings/files, local TLS
proxy and a synthetic ClamD wire fixture. It does not connect to a production
server. See `docs/CLI_VALIDATION.md` in the source root for recorded results and
remaining platform checks.

The CLI follows the parent project's declared LGPL 2.1 license (`LICENSE`).
Distributed binaries also contain the Go runtime/standard library under the
Go Authors' BSD license (`GO-LICENSE.txt`).

## MCP server

Run `clamctl mcp serve --upload-root /srv/artifacts` as a local MCP stdio server.
It exposes `clamav_scan_file`, `clamav_scan_path`, `clamav_get_job`,
`clamav_check_health`, `clamav_list_endpoints`, and the `clamav://stats`,
`clamav://jobs/recent`, `clamav://audit/recent` resources. Credentials and TLS
settings are shared with normal CLI commands. Local uploads require explicit
roots. `--transport http` offers a loopback Streamable HTTP endpoint with a
separate Bearer token. See `docs/MCP.md` in the source root for the complete
setup, permissions, remote proxy guidance and client examples.
