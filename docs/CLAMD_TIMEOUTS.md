# Daemon deadlines and scheduler isolation

Issue [#20](https://github.com/Kosztyk/clamv-webui/issues/20) covered a single shared scheduler thread and TCP calls that could block on the operating system's default timeouts. This edition provides a scheduler pool and a bounded socket client for both TCP and Unix endpoints.

## Defaults and deployment settings

| Setting | Property | Environment variable | Default | Allowed range |
|---|---|---|---|---|
| DNS + connect budget | `clamav.client.connect-timeout` | `CLAMAV_CLIENT_CONNECT_TIMEOUT` | `3s` | 1 ms–1 minute |
| Health/command budget | `clamav.client.command-timeout` | `CLAMAV_CLIENT_COMMAND_TIMEOUT` | `5s` | 1 ms–1 minute |
| Total scan budget | `clamav.client.scan-timeout` | `CLAMAV_CLIENT_SCAN_TIMEOUT` | `24h` | 1 ms–7 days |
| Scheduled worker threads | `spring.task.scheduling.pool.size` | `SPRING_TASK_SCHEDULING_POOL_SIZE` | `4` | 4–64 |

For optional deployment overrides:

```yaml
environment:
  CLAMAV_CLIENT_CONNECT_TIMEOUT: "3s"
  CLAMAV_CLIENT_COMMAND_TIMEOUT: "5s"
  CLAMAV_CLIENT_SCAN_TIMEOUT: "24h"
  SPRING_TASK_SCHEDULING_POOL_SIZE: "4"
```

These infrastructure settings apply at application startup; recreate the application after changing them. They add no Configuration Center fields. Invalid/unbounded durations and unsafe pool sizes fail startup. The existing configuration catalogue still has 113 fields. REST scanner HTTP budgets and ClamD's own scan/body limits remain separate settings.

The explicit `ThreadPoolTaskScheduler` keeps fixed-delay work in a real pool even when virtual threads are enabled. A scheduled task does not overlap its own previous fixed-delay invocation. The pool allows other tasks to run while the metrics sampler waits for an endpoint.

## Which operations are bounded

Health clients created by `probeFor` share **one total command deadline** across PING, VERSION and STATS. `/main`, `/api/clamdtop`, v1 ClamD health and metrics snapshots use this mode. A peer that answers PING slowly cannot obtain a fresh five seconds for each remaining command. Individual controls on a regular scan client also have a command deadline.

DNS resolution and connecting share a shorter budget, capped by the remaining operation deadline. DNS uses two daemon workers and a queue of sixteen; calls time out or fail when capacity is unavailable. Native OS resolution can ignore cancellation, but cannot create unbounded resolver threads or queues. Numeric addresses remain usable when hostname DNS is saturated.

INSTREAM and MULTISCAN have a separate total scan budget, including connection, streaming and response reading. Network writes also have a command-sized inactivity budget, capped by the remaining scan time. A daemon that stops reading an upload cannot leave a socket write blocked indefinitely. File streams used by the application are local stored files; this network deadline does not promise to interrupt arbitrary blocking filesystem/InputStream implementations.

The shared protocol implementation uses nonblocking channels and selectors with monotonic deadlines. Reads do not renew the total deadline whenever a byte arrives, so trickled data cannot keep a probe alive forever. Connections/selectors close on success, timeout, interruption or response-limit failure. All replies are capped at **4 MiB**. Oversized or malformed scan replies fail the scan; they do not become clean results.

TCP now uses the same framing/parser as Unix sockets: NUL-framed commands, network-byte-order INSTREAM chunks, all MULTISCAN records and STATS termination at END. TCP retains the endpoint's configured UNIX/Windows path conversion. Native upload path scans also use this client, replacing their separate raw-socket reader. The dependency's result/platform types remain in use; its unbounded `ClamavClient` is no longer constructed.

## Visible failures and practical limits

| Surface | Unreachable or silent daemon |
|---|---|
| `/api/clamdtop` | HTTP 200 with `ok: false` and an error, within the probe budget |
| `/api/v1/health/clamd` | HTTP 503 with `connectivity: DOWN` |
| `/main` | Renders the daemon error instead of waiting for OS TCP timeouts |
| Scheduled ClamD metrics | Endpoint marked unavailable; unrelated scheduled work can proceed |
| Scan job | Persists `ERROR` on timeout, subject to existing database availability/recovery handling |

Timeout scan failures use the existing bounded `TIMEOUT` metrics label. Public job error summaries continue masking private diagnostic text. A native path-scan failure can still use the configured INSTREAM fallback, which receives its own bounded scan operation. Upload cleanup and retention from #18/#19 remain in effect; failed scan copies are retained for diagnosis until retention expires.

Metrics still visits the configured endpoint slots sequentially. Twelve failing endpoints can therefore occupy that sampler for up to roughly twelve probe budgets; this does not reserve all four scheduler threads. The change bounds daemon network work and removes the single-thread coupling; it does not guarantee deadlines for unrelated database outages, slow mounted filesystems or custom scheduler replacements.

## Tests and runtime checks

Run with JDK 21/Maven:

```bash
mvn verify
python3 tools/verify-clamd-timeouts.py --jar target/clamav-web-client-2.1.0.jar
```

`TcpClamdClientTest` uses real local TCP peers for normal framing, streaming, Windows path conversion, multiple detection records, empty/error replies, a peer that never responds, trickled replies, stalled upload writes, response caps, scan deadlines and a shared multi-command probe deadline. A fake native DNS resolver that ignores interruption verifies bounded capacity and continued literal-address resolution.

`ClamdSchedulingTest` checks defaults/overrides and invalid startup settings. Its real metrics collector blocks on a silent TCP peer while an independent Spring `@Scheduled` task keeps ticking before the network timeout. `ClamdProbeTimeoutIntegrationTest` sends MockMvc requests through real TCP clients to a silent peer and a TEST-NET address, checking the legacy API, Main and v1 health failure behavior. Existing lifecycle, lockout, forwarding, provider and watcher regressions remain in the full suite.

`tools/verify-clamd-timeouts.py` starts the actual packaged HTTP application with isolated H2/conf/data and synthetic TCP peers. It verifies real scheduled configuration polling progresses while the metrics probe is blocked, a healthy endpoint remains usable, default five-second failures on the three HTTP surfaces, connection closure and continued application liveness. This fixture does not scan malware.

Separately, `scripts/test-eicar-native.py --isolate` uses real ClamAV 1.4.6 and an EICAR-only signature fixture through native MULTISCAN with fallback disabled. Both clean and infected uploads, 400-character labels, cleanup, audit and private-state/read-only mount isolation are checked. Its `--quarantine-failure` scenario verifies #19 warning behavior with the new transport too. Native Linux namespaces are distinct from Docker image execution.

See [the executed validation record](security/clamd-timeouts-validation.json). No schema migration is needed. Keep the database, conf/data and encryption keys; rebuild/recreate the application with existing deployment settings. Earlier versions' [private native-scanner migration](UPLOAD_RETENTION.md#private-permissions-and-native-scanner-migration) still applies when upgrading from before #18.

Primary references: [ClamD wire protocol](https://docs.clamav.net/manual/Usage/ClamdProtocol.html) and [Spring Boot scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html).
