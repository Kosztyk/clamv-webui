# Cumulative v2.1.0beta2 audit compatibility corrections

This patch is based on the complete issue #35 audit-remediated source, and addresses **three** regressions identified after that remediation. Earlier issue #13–#35 fixes, migrations V1–V7, Docker settings and application branding remain unchanged.

## API v1 history: diagnostic contract + bounded TEXT access

`GET /api/v1/jobs` originally returned safe signatures from `found_viruses_json`, a precise quarantine status from `quarantine_path`/quarantine failure messages and stable public error codes derived from `error_message`. The issue #28 scalar-history optimization inadvertently replaced those with generic placeholders.

The main `ScanJobSummary` projection remains **exactly as before**: no TEXT fields, no private upload/quarantine paths or eager endpoints, for `/dashboard`, `/scan`, `/jobs`, and initial API list filtering/counting. After applying token endpoint/path/ownership restrictions and pagination, the API list uses bounded supplemental queries:

1. `findApiListQuarantine(ids)` reads just `(id, quarantine_path)` for the authorized current page. The internal path is used **only to calculate a public status**; it is never serialized.
2. `findApiListDiagnostics(ids, verdicts)` is called **only if the page contains `VIRUS_FOUND` or `ERROR` jobs**. It loads `found_viruses_json`, `error_message`, and quarantine metadata for those visible page IDs only. Clean/queued page entries do not cause TEXT reads.

Both the list and detail DTO share the **same parsing/sanitization logic**, preserving the 1 MiB JSON parse cap, 1,000 validated signature-name cap and fixed public error codes. Stored paths and raw daemon/exception details remain private. The number of supplemental rows is at most the configured API page size (maximum 500); pages may require up to two additional database queries. Concurrent scan updates between the summary and diagnostic queries may create briefly inconsistent responses, just as with ordinary paged reads; clients can re-read the detail endpoint for an authoritative current state.

Tests: `ApiV1DtoTest`, `ApiV1ContractIntegrationTest`, `ApiV1PathScopeIntegrationTest`, `Issue28JobHistoryTest`. The SQL inspector test still requires the original summary SQL to **exclude** `found_viruses_json`, `error_message` and `quarantine_path`.

## Configuration Center: observation time independent of policy identity

`ClamdConfigurationObserver` now publishes a separate `lastSuccessfulObservedAt` in the same volatile immutable observation snapshot. Every successful config parse updates it even if `DaemonConfiguration.samePolicyAs` preserves the existing policy object. Invalid/inaccessible config reads **do not** advance the successful timestamp; the effective last-known policy is retained. The Configuration page's *Observed* row consumes the new timestamp. No change to directory-scan policy pinning or validation is necessary.

Tests: `ClamdConfigurationObserverTest` and actual observer/path-policy offline behavior check.

## Endpoint service: periodic external-drift reconciliation

After a successful synchronization, repeated scheduled checks with an unchanged candidate continue to skip database access and Spring write transactions. Once per **five minutes** (monotonic clock), the next ordinary poll performs a read-only lookup of the managed `default` endpoint. If the persisted connection already matches the discovered candidate, the check exits without starting a write transaction. If another instance or administrator changed/deleted the row, the existing short `TransactionTemplate` synchronization restores the managed host, port and platform. The scan-client cache is invalidated on an actual update. Explicit connection overrides retain precedence, and local endpoint modifications invalidate the in-memory marker.

The interval is fixed at five minutes to avoid adding a new configuration layer for this rare reconciliation. Detection occurs at the **next scheduled poll after the interval**, not immediately. With a 30-second observer poll interval, expected detection is within roughly 5–5.5 minutes. Multiple application instances controlling the *same* endpoint from different config files are an unsupported configuration and can still compete.

Tests: `Issue33ConfigurationPollingTest` and isolated actual `EndpointService` Java 17 behavior check.

## Validation and release conditions

Offline tests in the source root:

```sh
python3 scripts/check-audit-policy-offline.py
python3 scripts/check-audit-endpoint-offline.py
python3 scripts/check-audit-api-v1-offline.py
python3 scripts/check-safe-scanning-source.py
python3 scripts/check-phase1-static.py
python3 scripts/check-phase2-static.py
python3 scripts/phase4-static-check.py
python3 scripts/check-i18n.py
```

On a Java 17+ machine with Maven and Docker/Testcontainers:

```sh
mvn -B -Dtest=ClamdConfigurationObserverTest,Issue33ConfigurationPollingTest,ApiV1DtoTest,ApiV1ContractIntegrationTest,ApiV1PathScopeIntegrationTest,Issue28JobHistoryTest test
mvn -B verify
mvn -B -Pdb-integration verify
```

**Limitations:** The packaging environment lacks Maven, Docker, database servers and a runnable ClamAV daemon. Passing isolated compiler tests and static checks is not equivalent to passing Spring Boot/Micrometer/Flyway integration suites. Execute the Maven and native scanner tests before promoting to production. No schema changes are introduced by this patch, and V1–V7 checksums must remain intact.
