# Issue #40 — changed files

- `src/main/java/.../service/RestTransportPolicy.java` — shared fail-fast key-over-HTTP policy, explicit opt-in and warning.
- `src/main/java/.../service/RestApiBridge.java` — secure startup-bound configuration (both bearer and X-API-Key), preserve bounded HTTP responses.
- `src/main/java/.../config/RestApiConfig.java` — same fail-fast policy for the secondary REST client and Configuration Center-aware bindings.
- `src/main/java/.../service/RestApiClient.java` — streaming 10 MiB cap for content-length and chunked upstream responses, no raw error payloads.
- `src/main/java/.../web/RestMonitorController.java` — fixed upstream error response body, even for malformed future upstream adapter errors.
- `src/main/java/.../configuration/ConfigurationCatalog.java` — cross-field URL/key/opt-in validation during Review/Apply.
- `src/main/java/.../configuration/ConfigurationConnectionTester.java` — no secret-bearing probe without satisfying the transport policy.
- `src/main/resources/configuration-center/catalog.json` — new security-sensitive, restart-required `CLAMAV_REST_ALLOW_INSECURE` setting.
- `src/test/java/.../service/Issue40RestSecurityTest.java` — fail-fast, HTTPS/loopback/anonymous, opt-in, error and response-size tests.
- `src/test/java/.../configuration/Issue40RestConfigurationCenterTest.java` — GUI catalog, persistence, env precedence, constructor binding and cross-field tests.
- `src/test/java/.../web/RestMonitorIntegrationTest.java` — mocked hostile upstream response never reaches browser.
- `scripts/check-issue40-rest-security.py`, `ISSUE40_VALIDATION_RESULTS.md`, `docs/ISSUE40_REST_TRANSPORT_SECURITY.md` — release verification and deployment instructions.
- `publish-clamav-webui.sh` and `SOURCE_MANIFEST.sha256` — restore self-contained archive preflight for the beta2 ZIP root (the inherited publisher referred to an older root and missing manifest).
- Updated `README.md`, `docs/RELEASE_NOTES_V2_1_0.md`, `docs/REST_UI_REINTEGRATION.md`, `docs/CONFIGURATION_FIELD_AUDIT.md`, and root `docker-compose.yml` example comments.

No modifications to ClamD TCP transport, Flyway migrations, database drivers, issue #36–#39 fixes or the existing REST endpoint path allowlist.
