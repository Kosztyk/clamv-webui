# Issue #43 validation report — 2026-11-13

## Implemented

- Shared `ApiEndpointRules.resolve()` used by legacy and v1 controllers: missing endpoint IDs return HTTP 404; no default returns HTTP 503.
- Shared `requireEnabled()` is used before upload/path job creation by both controller generations; disabled endpoints return HTTP 400.
- Legacy `/api/clamdtop` validates endpoint selection before probing; existing numeric VIEWER projection and sanitized ClamD connection failures are retained.
- Legacy exception advice handles invalid arguments, malformed JSON, missing input/parts, missing job IDs, unauthorized access, oversized uploads and unexpected errors with fixed JSON responses and `Cache-Control: no-store`. Exception contents are never returned.
- Legacy audit failures use `FAILED` with endpoint ID when available, not Java exception class names; queued scans retain successful `QUEUED` audit attempts. Auditing remains best effort, independent of the submission outcome.
- README, OpenAPI and user guide explicitly deprecate legacy routes with a planned 2027-12-31 sunset, no earlier than a major-version release. This is a planned date, not an automatic removal in beta2.

## Executed checks

- `scripts/check-issue43-legacy-api.py`: **31/31 pass** (offline static source/contract checks).
- Previously included offline checks for issues #36–#42: **pass**; the #38 Configuration Center checks remain intact.
- Java 21 compilation/behavior checks of the **actual** `ApiEndpointRules.java` using isolated stub interfaces: **7 checks pass** (known/unknown/default/disabled endpoints).
- Java 21 compilation/behavior checks of the **actual** `ApiExceptionHandler.java` using isolated stub interfaces: **5 checks pass** (400/404/503/500 mapping and response sanitization).
- `tools/audit-source-package.py`: passed on the completed source tree.
- New GitHub Actions YAML and OpenAPI YAML: parsed successfully.
- ZIP integrity, SHA-256 manifest and publisher archive `--check`: verified after final packaging.

## Not executed in this environment

- **Maven/Boot integration suite** (`mvn -B -ntp verify`), because Maven and the dependencies are unavailable here.
- **Docker image build and live ClamD network tests**, because Docker is unavailable here.

The isolated Java checks prove compilation and specific behavior of those classes against small test stubs; they do **not** replace Maven/Spring integration tests. GitHub Actions includes a dedicated MVC integration test for this issue. Close issue #43 only after the full Maven/CI suite and deployment smoke test pass.

## Recommended commands

```sh
python3 scripts/check-issue43-legacy-api.py
mvn -B -ntp '-Dtest=Issue43LegacyApiIntegrationTest,ApiV1ContractIntegrationTest,PathPrivacyIntegrationTest' test
mvn -B -ntp verify
docker build --pull -t clamav-web-client:issue43 .
```

No new database migration, Configuration Center setting, or Docker environment variable is needed.
