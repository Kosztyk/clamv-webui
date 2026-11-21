# Issue #33 — Configuration polling and ClamAV client reuse (v2.1.0beta2)

## Problem

`EndpointService.scheduledConfigurationPoll()` previously ran every 1000 ms with `@Transactional` and returned early most of the time. Spring transaction interception could still start/close a database transaction for these no-op cycles. When a refresh was due, `ClamdConfigurationObserver.refresh()` parsed mounted files inside that same transaction. `ClamavClientProvider.clientFor()` also allocated a new immutable client wrapper for every scan.

## Changes

1. `EndpointConfigurationPollScheduler` registers a Spring `Trigger` task rather than a fixed one-second heartbeat. It reads `ClamdConfigurationObserver.pollMillis()` after each completion; this resolves the current `IntegrationSettingsService` value (Docker env, Configuration Center, or existing observer setting). Existing `clamav.config.initial-delay-ms` is honored. The normal interval is 30,000 ms, bounded to 5,000–3,600,000 ms. Invalid legacy values are logged and use a safe 30-second interval rather than cancelling future scheduling.
2. `EndpointService.pollConfiguration()` is **not transactional**. It reads/parses mounted clamd.conf/freshclam.conf first. If discovery has no valid endpoint candidate or a connection override controls the default endpoint, it exits with **no database transaction**. Only default-endpoint persistence is executed via `TransactionTemplate` (which uses `PlatformTransactionManager`). `ensureDefaultEndpoint()` and CRUD transaction semantics remain unchanged.
3. `ClamavClientProvider.clientFor()` reuses immutable `ClamdClient` wrappers by **persistent endpoint ID and connection key** (trimmed host, port, platform, connect/command/scan timeouts). Changed settings automatically invalidate the signature. Endpoint updates, deletes and observed default-endpoint changes explicitly evict the affected entry. Unsaved endpoints are not cached.
4. `probeFor()` intentionally stays **uncached**: probes have a new overall deadline per request. No active TCP connection is pooled or reused; every ClamAV command still creates and closes a bounded socket. Existing timeout, DNS, SSRF and transport behavior is unchanged.

## Configuration Center

**Administration → Configuration Center → Monitoring → Configuration observation → Configuration interval (ms)**

- Key: `clamav.config.poll-ms`.
- Alias: `CLAMAV_CONFIG_POLL_MS`.
- Default: `30000` (30 seconds).
- Valid range: `5000`–`3600000` ms.
- Apply mode: live. The next due time is recalculated after the previously scheduled poll finishes. An interval reduction therefore may take one old interval to become effective.
- Docker Compose overrides take precedence over saved GUI settings.

No new settings or database migrations are needed. Normal scan and upload behavior, quarantine, session security and the fixes for issues #13–#32 are unchanged.

## Tests and verification

New tests:

- `Issue33ConfigurationPollingTest`: no transaction when no candidate; observer refresh before transaction start; dynamic, bounded rescheduling and malformed saved values; explicit endpoint update/delete invalidation.
- `Issue33ClientReuseTest`: reuse, concurrent callers, live endpoint signature changes, invalidation and fresh probe deadlines.

Run:

```bash
mvn -B -Dtest=Issue33ConfigurationPollingTest,Issue33ClientReuseTest,ConfigurationEndpointSyncTest,ClamdSchedulingTest test
mvn -B verify
```

To verify the acceptance criterion on a live instance, enable transaction tracing temporarily (do **not** log secrets):

```properties
logging.level.org.springframework.transaction=TRACE
logging.level.org.springframework.orm.jpa.JpaTransactionManager=DEBUG
```

With a 30-second observation interval, there should be no `EndpointService.scheduledConfigurationPoll` transaction every second and no database transaction during mounted-file parsing. Measure a full minute on a test system with discovery enabled and disabled; check that the default endpoint still updates when an eligible clamd.conf changes. Confirm no query regression across PostgreSQL and H2.

**Validation boundary:** isolated Java 17 compilation and behavior smoke test passed for the three changed production classes, static source checks passed, but the full Maven/Spring Boot integration suite and live database transaction logging were not executed in the packaging environment.


**Cumulative audit:** EndpointService now skips its short database transaction when the discovered endpoint is identical to the last successfully committed candidate. The marker is invalidated on primary endpoint edits/deletions, override activation, and observation pause. The observer also reuses unchanged policy objects to avoid false directory-scan interruptions. See [audit remediation](CUMULATIVE_AUDIT_REMEDIATION_13-35.md).
