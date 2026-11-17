# Issue #39 — source changes

See [docs/ISSUE39_VIEWER_WEBHOOK_SECURITY.md](docs/ISSUE39_VIEWER_WEBHOOK_SECURITY.md) for behavior, configuration, upgrade steps and acceptance tests.

Changed/additions:
- `src/main/java/.../service/ClamdMetricsView.java` (new safe metrics projection)
- `src/main/java/.../api/ApiController.java` (role-aware legacy metrics/endpoints/health)
- `src/main/java/.../api/v1/ApiV1Controller.java` (shared safe projection and viewer endpoint labels)
- `src/main/java/.../service/ClamdTopService.java` (generic public errors, details in server logs)
- `src/main/java/.../web/WebUiController.java` (safe main/dashboard/native page selection)
- `src/main/java/.../security/SessionWebSocketGuard.java` and `config/SecurityConfig.java` (raw PTY access restricted)
- `src/main/resources/templates/pages/clamdtop-viewer.html` (new safe viewer page)
- `src/main/java/.../service/NotificationService.java` (safe structured payload and signing)
- `src/main/java/.../service/WebhookSignature.java` (HMAC-SHA256)
- `src/main/java/.../service/WebhookDeliveryClient.java` (mandatory signed transport)
- `src/main/java/.../service/SettingsService.java`, `configuration/ConfigurationCatalog.java`, `configuration/ConfigurationEngine.java`, `src/main/resources/configuration-center/catalog.json` (GUI settings)
- `src/main/resources/static/js/clamdtop.js` (supports the sanitized VIEWER API projection)
- Regression tests under `src/test/java` and accompanying docs/README.

No H2/Flyway schema changes, PostgreSQL/MySQL/MariaDB driver changes, or previous issue #36–#38 reversions.
