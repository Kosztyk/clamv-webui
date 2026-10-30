# Mounted ClamAV configuration synchronization — v2.0.0

An explicitly configured `CLAMAV_CONFIG_PATH` enables observation of `clamd.conf`
and synchronization of the endpoint named `default`. Existing records are updated
in place, retaining their IDs, scan history and enabled/disabled state. Other
named endpoints retain their settings. No database migration is required.

## Enable it in an existing deployment

Merge these environment variables and mounts into the existing web service:

```yaml
environment:
  CLAMAV_CONFIG_PATH: /etc/clamav/clamd.conf
  FRESHCLAM_CONFIG_PATH: /etc/clamav/freshclam.conf
  CLAMAV_CONFIG_DISCOVERY_ENABLED: "true"
  CLAMAV_CONFIG_POLL_MS: "30000"
volumes:
  - ./clamav-config:/etc/clamav:ro
  # Required only when clamd.conf uses this LocalSocket path:
  - /run/clamav:/run/clamav:ro
```

Use the actual config and socket directories from your installation. A read-only
socket-directory mount does not grant socket permissions: the web process must
have permission to connect. Share the socket directory rather than a single
socket file so daemon socket replacement remains visible.

`CLAMAV_HOST` or `CLAMAV_PORT`, when nonblank, prevents file-driven changes to the
endpoint connection. Remove those overrides if the file should own the connection.
Keep an explicit reachable host for ClamD in a separate container network; the
mounted file's policy still applies to the endpoint named `default`.

With no `CLAMAV_CONFIG_PATH`, synchronization and inherited policy are disabled.
`FRESHCLAM_CONFIG_PATH` alone enables inspection of that file. Set
`CLAMAV_CONFIG_DISCOVERY_ENABLED=false` to inspect both files without applying
their settings. File values are never written back to either config file.

## Endpoint discovery and ongoing updates

- `LocalSocket` takes priority when specified. Its absolute path is stored as
  the endpoint host, with port `0` (ignored for Unix sockets). The same socket
  path must be available in the web process's filesystem namespace. A daemon
  may start later; connection failure is reported through ordinary health checks.
- Otherwise `TCPSocket` supplies the port. `TCPAddr` supplies the address; when
  it is absent, `127.0.0.1` is used. Wildcard `0.0.0.0` and `::` become loopback
  `127.0.0.1` and `::1`. If multiple TCPAddr directives exist, the first is used.
- Polling re-reads the main file and its includes, even if a timestamp is
  unchanged. A changed endpoint updates the existing `default` record; subsequent
  commands use the new connection. An already-running daemon command is not moved.
- Native Unix transport supports PING, VERSION, STATS, INSTREAM and MULTISCAN.
  Native ClamdTop also accepts the socket path as one safely quoted argument.

## Inherited scanning policy

Policies apply only to the endpoint named `default`, including browser, legacy API,
versioned API and directory-watcher jobs. Mounted configuration does not grant
access outside the application's canonical allowed roots.

| Directive | Client behavior |
|---|---|
| `StreamMaxLength` | Lowers the upload ceiling, including uploads configured for path scanning or INSTREAM fallback |
| `MaxScanSize` | Lowers upload and individual path/watch input-file ceilings |
| `MaxFileSize` | Also lowers upload and individual path/watch input-file ceilings |
| Repeated `ExcludePath` | Rejects directly excluded targets and omits matching files/directories from recursive scans; checks stored paths for uploads |

The application upload ceiling always remains in force. Sizes accept bytes and
binary K/M/G suffixes, with checked arithmetic. `MaxScanSize=0` and
`MaxFileSize=0` disable those extra ceilings; they never remove the application
ceiling. `StreamMaxLength=0` accepts no nonempty INSTREAM data.

Only explicitly specified limits are inherited; omitted directives do not invent
additional client defaults. ClamD remains responsible for its own defaults and
expanded archive limits. The client can check an archive's input size; it cannot
predict how much ClamD will extract.

ExcludePath expressions preserve regex backslashes and quoted values, use search
matching and support standard POSIX character classes. The client compiles them
using Java's regex engine. An expression outside its supported syntax is rejected
instead of silently ignored. Recursive managed scans visit eligible regular files
individually, do not follow directory/file symlinks, and retain per-file signature
results. An oversized member fails the scan; a directory with no eligible regular
files is not reported clean. Other named endpoints keep the existing daemon-side
recursive scan behavior.

Upload batches are checked before any file is copied or job queued. Path/watch
targets are checked at submission, and queued work is checked again at execution
against the latest successfully observed policy. Watcher exclusions are checked
before files enter the processed-file cache.

## Invalid or unavailable files

Includes stay within the mounted configuration directory, with limits of 1 MiB
per file, 40 files and six include levels. Escaping includes, missing includes,
oversized/truncated input, malformed quotes, invalid limits, duplicate scalar
directives and invalid exclusion expressions prevent a partial policy from being
activated. Secret-like directives and credential-bearing URLs remain redacted.

A bad update retains the entire last valid policy and connection configuration in
memory. The inspection page shows the current read error and the older policy's
observation timestamp. A corrected file is picked up on the next poll. If the app
starts without any valid mounted configuration, managed default-endpoint scans
are rejected until a valid file appears. This in-memory fallback is rebuilt from
the mounted file after a restart.

## Inspection and live telemetry

Administration → Configuration (`/admin/configuration`) is administrator-only and
shows desired `clamd.conf` and `freshclam.conf` values, applied client limits and
exclusions, actual database endpoint connections, VERSION, and raw STATS sampled
by the existing bounded collector. Failed daemon polls clear the displayed STATS
instead of presenting old output as current. The last successful poll remains
visible. Telemetry covers at most 12 enabled endpoints, as configured for metrics.

The read-only view refreshes at the configured file-poll interval while visible.
Opening it does not cause an additional daemon connection. File polling and daemon
sampling have independent schedules, so their displayed timestamps can differ.
The refresh link requests the current view immediately.

Changing a file does **not** restart/reload ClamD or FreshClam. The page distinguishes
desired file values from sampled live daemon output; STATS cannot prove which
configuration file the daemon has loaded.

Validation and remaining deployment checks are recorded in
[DAEMON_CONFIGURATION_VALIDATION.md](DAEMON_CONFIGURATION_VALIDATION.md).
