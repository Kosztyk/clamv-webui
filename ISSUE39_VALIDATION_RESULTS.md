# Issue #39 validation — source candidate (2026-11-13)

This report records checks actually executed in the packaging environment. It does **not** imply that the Maven/Spring application suite or Docker tests ran here.

## Executed successfully

- `python scripts/check-issue39-security.py`: **35/35 offline source-contract checks passed**.
- `python scripts/check-issue38-config-center.py`: **17/17 offline checks passed**.
- `python scripts/check-issue38-clamdtop-security.py`: **22/22 offline checks passed**.
- `python scripts/check-issue37-maintenance.py`: **15/15 offline checks passed**.
- `python scripts/check-issue36-h2-source.py`: **10/10 offline checks passed**.
- `python tools/audit-source-package.py .`: no prohibited runtime state, private key material or recognizable credential strings detected in source.
- `node --check src/main/resources/static/js/clamdtop.js`: passed.
- Standard JSON parser: Configuration Center catalog parsed; XML parser: Maven POM parsed; YAML parser: Compose files parsed.
- `javac --release 21` compiled the unmodified production `WebhookSignature.java` and `ClamdMetricsView.java` classes into a separate disposable test directory.
- A **separate actual Java runtime smoke test** independently recomputed the HMAC digest and verified its equality, rejected a too-short key, and checked that synthetic queued filenames, internal hostnames and raw STATS were absent from the VIEWER metrics projection while numeric queue counts remained available.
- Final ZIP integrity and source audit are checked after creation.

## Tests included, but not executed here

- `Issue39MetricsProjectionTest` (JUnit).
- `Issue39WebhookPrivacySignatureTest` (including independent HMAC verification by a real loopback HTTP receiver).
- `Issue39ViewerHttpPrivacyIntegrationTest` (MockMvc with VIEWER and OPERATOR credentials).
- `Issue39WebhookConfigurationCenterTest` (secret-at-rest encryption, GUI field persistence and Docker override precedence).
- Existing webhook, roles, Configuration Center and ClamdTop regression suites.
- Docker image build and real ClamD/WebSocket monitoring integration.

**Reason:** The packaging environment has Java 21, Node and Python, but no Maven or Docker installed. Do not close #39 until the Maven suite, live receiver verification, production-role negative tests and Docker smoke tests pass on a suitable host.

## Suggested runtime commands

```bash
mvn -B '-Dtest=Issue39*Test,NotificationServiceTest,NotificationDispatchTest,WebhookDeliveryClientTest,ConfigurationCenterUiTest,ConfigurationAllFieldsTest,ClamdProbeTimeoutIntegrationTest' test
mvn -B verify
docker build --pull -t clamav-web-client:issue39 .
```

Webhooks **fail closed** after upgrade if notifications are enabled but no signing key has been configured. Before deployment, configure the secret in Administration → Configuration Center → Notifications → Webhook notifications, or provide `CLAMAV_WEBHOOK_SIGNING_SECRET` securely. Never place signing secrets in Git or issue comments.
