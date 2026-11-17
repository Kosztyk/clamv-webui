# Issue #40 verification report — 13 November 2026

This report distinguishes executed checks from Maven/Spring and Docker checks still required on a build host.

## Executed successfully

- `python3 scripts/check-issue40-rest-security.py`: **31/31 offline source-contract checks passed**.
- Previous regression scripts passed: issue #36 (10), issue #37 (15), issue #38 ClamdTop (22), issue #38 Configuration Center (17) and issue #39 (35).
- `python3 tools/audit-source-package.py .`: source package audit passed; no prohibited runtime data or recognizable private credentials.
- YAML/JSON/XML parsing: Compose/YAML, Configuration Center catalog JSON and Maven POM all parsed successfully.
- Isolated Java 21 compilation of actual production `RestTransportPolicy`, `RestApiBridge`, `RestApiConfig` and `RestApiClient` against minimal interface stubs: **passed**. This is a Java compiler/runtime smoke test, **not a Maven/Spring integration test**.
- Isolated Java 21 HTTP smoke using a disposable `HttpServer`: rejected nonloopback HTTP with credentials at construction in both clients; rejected spoofed loopback hostname; allowed HTTPS, anonymous HTTP, explicit opt-in and loopback URLs; discarded upstream 502 JSON; refused 302 redirect; bounded a >10 MiB chunked response in the secondary client; sent authentication only to the intended loopback receiver. **8/8 checks passed**.
- `bash -n publish-clamav-webui.sh` passed and `SOURCE_MANIFEST.sha256` generated for every source file (excluding the manifest itself).
- Final ZIP integrity, source manifest verification and publisher `--check` checked after packaging.

## Tests written but not executed here

- `Issue40RestSecurityTest`: real JUnit coverage of fail-fast security, opt-in compatibility, loopback, upstream errors and chunked/declared response size limits.
- `Issue40RestConfigurationCenterTest`: catalog field, GUI save/restart behavior, Compose precedence, Spring injection and cross-field validation.
- `RestMonitorIntegrationTest`: hostile mocked upstream error body never appears in the browser response.
- Existing `RestApiBridgeTest`, `RestApiClientTest`, `ConfigurationAllFieldsTest`, `ConfigurationConsumerWiringTest`, `RestMonitorIntegrationTest` and full Maven test suite.
- Docker image build, actual container startup failure under an unsafe HTTP+key configuration, real HTTPS external REST service and browser access controls.

Maven and Docker are **not installed** in this packaging environment; these runtime checks must pass in CI or on the deployment host before closing issue #40.

## Suggested build-host commands

```bash
python3 scripts/check-issue40-rest-security.py
mvn -B '-Dtest=Issue40*Test,RestApiBridgeTest,RestApiClientTest,RestMonitorIntegrationTest,ConfigurationAllFieldsTest,ConfigurationConsumerWiringTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue40 .
```

**Upgrade notice:** Any `CLAMAV_REST_URL=http://remote-host:port` plus a nonempty `CLAMAV_REST_API_KEY` now fails application startup by default. Configure HTTPS (preferred) or explicitly set `CLAMAV_REST_ALLOW_INSECURE=true` through the GUI (restart required) or deployment environment. Do not commit real keys or private URLs.
