# Daemon configuration synchronization validation — v2.0.0

Validated on 30 October 2026 (Europe/Bucharest), against the complete source
containing the preceding LDAP, OIDC and Prometheus/Grafana updates.

## Build and regression results

`mvn clean verify` completed successfully on Java 17: **197 JUnit cases,
192 passed, 5 skipped, 0 failures and 0 errors**. The executable Spring Boot JAR
was packaged successfully. There are 44 new JUnit cases compared with the previous
153-case source: 39 ran successfully here and five require real Unix sockets.

| Test class | Cases | Coverage |
|---|---:|---|
| `ClamdConfigurationObserverTest` | 21 | Four prior checks plus include changes without mtime changes, FreshClam refresh/redaction, regex escaping, localhost/wildcard/IPv6 discovery, Unix discovery, invalid updates, missing-file recovery and inspection-only mode |
| `ConfigurationEndpointSyncTest` | 5 | Initial seed, persisted-default updates, unchanged-write avoidance, enabled-state retention, Unix/TCP switching, explicit host/port precedence and disabled/invalid configuration |
| `DaemonScanPolicyTest` | 7 | Minimum upload ceiling, refreshed limits, zero values, POSIX classes and regex escaping, excluded subtrees, symlink omission, per-file signatures, empty filtered directories, file-size failures and invalid initial policy |
| `DaemonPolicyScanBoundaryTest` | 3 | Batch rejection before storage/queueing, path/watch submission checks, allowed-root enforcement and execution-time rejection of queued uploads |
| `UnixClamdProtocolTest` | 4 | Production command framing/parsing with partial writes and fragmented reads, INSTREAM chunk lengths/terminator, all MULTISCAN responses, clean/error distinction and resource closure |
| `UnixClamdClientTest` | 5 skipped | Real AF_UNIX server/client exchanges, upload streaming, multiple detections and timeout handling; this environment rejects AF_UNIX creation with `Operation not permitted` |
| `NativeClamdtopConnectionTest` | 2 | Literal socket arguments containing spaces/quotes/shell metacharacters, ordinary TCP, IPv6 and invalid host rejection |
| `DaemonConfigurationUiTest` | 1 | Real Spring binding of both public path variables, H2 persistence updates retaining endpoint ID, admin-only MVC routes, raw sampled STATS, secret redaction, API upload rejection, corrected/invalid updates and outage clearing |

The feature-focused suite runs 43 cases successfully here; the five real-socket
cases are reported as skipped in the full build. The previous identity, API,
database, security, metrics and UI regression cases passed too.

The synthetic protocol tests use the same production encoder/decoder and a
scripted byte channel. They do not substitute for a live ClamD integration test.
The real-socket cases remain enabled and run on hosts that permit AF_UNIX; only
the specific environment permission failure causes their capability skip.

## Browser-side and static checks

`node scripts/test-configuration-observer.js`: **4 passed**. The checks exercise
current-content replacement, polling while visible, deferral while hidden,
navigation cancellation and preservation of the prior snapshot on errors or an
expired session. They use a small simulated DOM, not Chromium.

JavaScript syntax checks passed for the new refresher and existing PJAX code.
The six-language catalog check passed with 13 new phrases translated into
Romanian, Ukrainian, French, Spanish and German; English remains the source.
Phase 0/1, Phase 2 and Phase 4 source inventories passed.

The combined `scripts/release-gate.sh --static-only` reaches the LDAP/OIDC browser
stage and stops because Playwright is not installed. Its earlier inventory,
refresher and translation checks pass. Chromium rendering/browser acceptance and
the complete release gate are **not certified** by these results.

## Deployment acceptance still required

- Rebuild the existing deployment with its existing database, volumes, LDAP/OIDC
  and proxy settings. No migration, driver, Maven dependency or app version change
  was introduced by this feature.
- Mount actual config directories, enable synchronization, and confirm default
  endpoint updates after a file/include edit. Confirm explicit connection
  overrides and inspection-only mode if used.
- Confirm socket permissions and matching paths, actual ClamD INSTREAM/MULTISCAN
  results, native ClamdTop and daemon startup/restart behavior on the target host.
- Confirm the auto-refresh UI in the target browser and compare displayed desired
  values with real VERSION/STATS output. ClamD and FreshClam reloads remain the
  operator's responsibility.

No live ClamD/FreshClam server, Docker build, deployed Prometheus/Grafana instance
or external database was tested during this update. Existing vendor Flyway
migrations and all identity implementation files were retained unchanged.
