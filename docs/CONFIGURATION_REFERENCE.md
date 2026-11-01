# Configuration Center field reference

Generated from `src/main/resources/configuration-center/catalog.json`, the same catalogue used by setup and the administration API. All 107 global fields have one GUI editor. Deployment overrides are read-only; blank credential inputs retain saved secrets. See [Configuration Center](CONFIGURATION_CENTER.md) and [the consolidation audit](CONFIGURATION_CONSOLIDATION.md) and [the complete field audit](CONFIGURATION_FIELD_AUDIT.md).

## Connections

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Enable the default engine | `center.clamd.enabled` | — | `true` | Live |
| Endpoint name | `center.clamd.name` | — | `default` | Live |
| ClamAV host or Unix socket path | `clamav.service.host` | `CLAMAV_HOST` | `localhost` | Live |
| ClamAV port | `clamav.service.port` | `CLAMAV_PORT` | `3310` | Live |
| Daemon platform | `clamav.service.platform` | `CLAMAV_PLATFORM` | `UNIX` | Live |
| Enable the external REST scanner | `clamav.rest.enabled` | `CLAMAV_REST_ENABLED` | `true` | Restart |
| External REST URL | `CLAMAV_REST_URL` | `CLAMAV_REST_URL` | Not configured | Restart |
| API key (private) | `CLAMAV_REST_API_KEY` | `CLAMAV_REST_API_KEY` | Not configured | Restart |
| Authentication header | `CLAMAV_REST_AUTH_MODE` | `CLAMAV_REST_AUTH_MODE` | `x-api-key` | Restart |
| Upload form field | `CLAMAV_REST_FORM_KEY` | `CLAMAV_REST_FORM_KEY` | `FILES` | Restart |
| Request timeout (seconds) | `CLAMAV_REST_TIMEOUT_SECONDS` | `CLAMAV_REST_TIMEOUT_SECONDS` | `10` | Restart |
| Upload timeout (seconds) | `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` | `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` | `3600` | Restart |
| Maximum report jobs | `CLAMAV_REST_REPORT_MAX_JOBS` | `CLAMAV_REST_REPORT_MAX_JOBS` | `5000` | Restart |
| Statistics path | `CLAMAV_REST_STATS_PATH` | `CLAMAV_REST_STATS_PATH` | `/api/v1/stats` | Restart |
| Jobs path | `CLAMAV_REST_JOBS_PATH` | `CLAMAV_REST_JOBS_PATH` | `/api/v1/jobs` | Restart |
| History path | `CLAMAV_REST_HISTORY_PATH` | `CLAMAV_REST_HISTORY_PATH` | `/api/v1/history` | Restart |
| Webhook definitions path | `CLAMAV_REST_WEBHOOKS_PATH` | `CLAMAV_REST_WEBHOOKS_PATH` | `/api/v1/webhooks` | Restart |
| Async scan path | `CLAMAV_REST_SCAN_PATH` | `CLAMAV_REST_SCAN_PATH` | `/api/v1/asyncscan` | Restart |
| Scan history path | `CLAMAV_REST_SCAN_HISTORY_PATH` | `CLAMAV_REST_SCAN_HISTORY_PATH` | `/api/v1/scan-history` | Restart |
| Webhook form field | `CLAMAV_REST_WEBHOOK_FIELD` | `CLAMAV_REST_WEBHOOK_FIELD` | `webhookId` | Restart |
| Database connection URL | `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` | Restart |
| Database username | `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `sa` | Restart |
| Database password (private) | `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | Not configured | Restart |
| JDBC driver (automatic when blank) | `spring.datasource.driver-class-name` | `SPRING_DATASOURCE_DRIVER_CLASS_NAME` | Not configured | Restart |
| Pool connection timeout (ms) | `spring.datasource.hikari.connection-timeout` | `SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT` | `10000` | Restart |
| Non-contextual JDBC LOB creation | `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation` | `SPRING_JPA_PROPERTIES_HIBERNATE_JDBC_LOB_NON_CONTEXTUAL_CREATION` | `true` | Restart |
| Baseline a reviewed legacy database | `spring.flyway.baseline-on-migrate` | `SPRING_FLYWAY_BASELINE_ON_MIGRATE` | `false` | Restart |
| Legacy baseline version | `spring.flyway.baseline-version` | `SPRING_FLYWAY_BASELINE_VERSION` | `1` | Restart |

## Scanning

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Allowed scan roots | `app.allowedScanRoots` | `CLAMAV_ALLOWED_SCAN_ROOTS` | `/scandir` | Live |
| Maximum upload (bytes) | `app.upload.maxBytes` | `CLAMAV_UPLOAD_MAX_BYTES` | `2147483648` | Live |
| Concurrent scan workers | `app.concurrentScans` | `CLAMAV_CONCURRENT_SCANS` | `2` | Restart |
| Upload storage directory | `app.storage.uploadDir` | `CLAMAV_UPLOAD_DIR` | `./data/uploads` | Restart |
| Use native uploaded-path scanning | `app.upload.pathScan.enabled` | `CLAMAV_UPLOAD_PATH_SCAN_ENABLED` | `true` | Live |
| Fall back to INSTREAM | `app.upload.pathScan.fallbackToInstream` | `CLAMAV_UPLOAD_PATH_SCAN_FALLBACK_TO_INSTREAM` | `false` | Live |
| HTTP file size ceiling | `spring.servlet.multipart.max-file-size` | `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` | `2048MB` | Restart |
| HTTP request size ceiling | `spring.servlet.multipart.max-request-size` | `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE` | `2048MB` | Restart |
| Enable quarantine | `app.quarantine.enabled` | `CLAMAV_QUARANTINE_ENABLED` | `false` | Live |
| Quarantine directory | `app.storage.quarantineDir` | `CLAMAV_QUARANTINE_DIR` | `./data/quarantine` | Restart |
| Enable directory watching | `app.watch.enabled` | `CLAMAV_WATCH_ENABLED` | `false` | Live |
| Watch interval (seconds) | `app.watch.pollSeconds` | `CLAMAV_WATCH_POLL_SECONDS` | `30` | Live |

## Identity & API

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Enable LDAP | `clamav.identity.ldap.enabled` | `CLAMAV_IDENTITY_LDAP_ENABLED`, `SECURITY_LDAP_ENABLED`, `security.ldap.enabled` | `false` | Restart |
| LDAP URL | `clamav.identity.ldap.url` | `CLAMAV_IDENTITY_LDAP_URL`, `SECURITY_LDAP_URL`, `security.ldap.url` | Not configured | Restart |
| Base DN | `clamav.identity.ldap.base-dn` | `CLAMAV_IDENTITY_LDAP_BASE_DN`, `SECURITY_LDAP_BASE_DN`, `security.ldap.base-dn` | Not configured | Restart |
| Bind / manager DN | `clamav.identity.ldap.manager-dn` | `CLAMAV_IDENTITY_LDAP_MANAGER_DN`, `SECURITY_LDAP_BIND_DN`, `security.ldap.bind-dn` | Not configured | Restart |
| Bind password (private) | `clamav.identity.ldap.manager-password` | `CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD`, `SECURITY_LDAP_BIND_PASSWORD`, `security.ldap.bind-password` | Not configured | Restart |
| Bind password file | `clamav.identity.ldap.manager-password-file` | `CLAMAV_IDENTITY_LDAP_MANAGER_PASSWORD_FILE` | Not configured | Restart |
| User search base | `clamav.identity.ldap.user-search-base` | `CLAMAV_IDENTITY_LDAP_USER_SEARCH_BASE`, `SECURITY_LDAP_USER_SEARCH_BASE`, `security.ldap.user-search-base` | Not configured | Restart |
| User search filter | `clamav.identity.ldap.user-search-filter` | `CLAMAV_IDENTITY_LDAP_USER_SEARCH_FILTER`, `SECURITY_LDAP_USER_SEARCH_FILTER`, `security.ldap.user-search-filter` | `(uid={0})` | Restart |
| Group search base | `clamav.identity.ldap.group-search-base` | `CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_BASE`, `SECURITY_LDAP_GROUP_SEARCH_BASE`, `security.ldap.group-search-base` | Not configured | Restart |
| Group search filter | `clamav.identity.ldap.group-search-filter` | `CLAMAV_IDENTITY_LDAP_GROUP_SEARCH_FILTER`, `SECURITY_LDAP_GROUP_SEARCH_FILTER`, `security.ldap.group-search-filter` | Not configured | Restart |
| Group member attribute | `clamav.identity.ldap.group-member-attribute` | `CLAMAV_IDENTITY_LDAP_GROUP_MEMBER_ATTRIBUTE` | `member` | Restart |
| Group name attribute | `clamav.identity.ldap.group-name-attribute` | `CLAMAV_IDENTITY_LDAP_GROUP_NAME_ATTRIBUTE` | `cn` | Restart |
| Use StartTLS | `clamav.identity.ldap.start-tls` | `CLAMAV_IDENTITY_LDAP_START_TLS` | `false` | Restart |
| Pool LDAP connections | `clamav.identity.ldap.pooled` | `CLAMAV_IDENTITY_LDAP_POOLED` | `false` | Restart |
| Search nested groups | `clamav.identity.ldap.nested-groups` | `CLAMAV_IDENTITY_LDAP_NESTED_GROUPS` | `false` | Restart |
| Maximum nested-group depth | `clamav.identity.ldap.max-nested-depth` | `CLAMAV_IDENTITY_LDAP_MAX_NESTED_DEPTH` | `3` | Restart |
| LDAP referrals | `clamav.identity.ldap.referrals` | `CLAMAV_IDENTITY_LDAP_REFERRALS` | `ignore` | Restart |
| Connect timeout (ms) | `clamav.identity.ldap.connect-timeout-millis` | `CLAMAV_IDENTITY_LDAP_CONNECT_TIMEOUT_MILLIS` | `5000` | Restart |
| Read timeout (ms) | `clamav.identity.ldap.read-timeout-millis` | `CLAMAV_IDENTITY_LDAP_READ_TIMEOUT_MILLIS` | `5000` | Restart |
| Viewer groups | `clamav.identity.ldap.groups.viewer` | `CLAMAV_IDENTITY_LDAP_GROUPS_VIEWER` | Not configured | Restart |
| Operator groups | `clamav.identity.ldap.groups.operator` | `CLAMAV_IDENTITY_LDAP_GROUPS_OPERATOR` | Not configured | Restart |
| Admin groups | `clamav.identity.ldap.groups.admin` | `CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN` | Not configured | Restart |
| Enable SSO | `clamav.identity.oidc.enabled` | `CLAMAV_IDENTITY_OIDC_ENABLED` | `false` | Restart |
| Issuer URL | `clamav.identity.oidc.issuer` | `CLAMAV_IDENTITY_OIDC_ISSUER`, `spring.security.oauth2.client.provider.oidc.issuer-uri`, `SPRING_SECURITY_OAUTH2_CLIENT_PROVIDER_OIDC_ISSUER_URI` | Not configured | Restart |
| Client ID | `clamav.identity.oidc.client-id` | `CLAMAV_IDENTITY_OIDC_CLIENT_ID`, `spring.security.oauth2.client.registration.oidc.client-id`, `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_ID` | Not configured | Restart |
| Client secret (private) | `clamav.identity.oidc.client-secret` | `CLAMAV_IDENTITY_OIDC_CLIENT_SECRET`, `spring.security.oauth2.client.registration.oidc.client-secret`, `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_CLIENT_SECRET` | Not configured | Restart |
| Client secret file | `clamav.identity.oidc.client-secret-file` | `CLAMAV_IDENTITY_OIDC_CLIENT_SECRET_FILE` | Not configured | Restart |
| Requested scopes | `clamav.identity.oidc.scopes` | `CLAMAV_IDENTITY_OIDC_SCOPES`, `spring.security.oauth2.client.registration.oidc.scope`, `SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_OIDC_SCOPE` | `openid,profile,email,groups` | Restart |
| Groups claim | `clamav.identity.oidc.groups-claim` | `CLAMAV_IDENTITY_OIDC_GROUPS_CLAIM` | `groups` | Restart |
| Login button label | `clamav.identity.oidc.button-label` | `CLAMAV_IDENTITY_OIDC_BUTTON_LABEL` | `Sign in with SSO` | Restart |
| Logout mode | `clamav.identity.oidc.logout-mode` | `CLAMAV_IDENTITY_OIDC_LOGOUT_MODE` | `local` | Restart |
| Viewer groups | `clamav.identity.oidc.groups.viewer` | `CLAMAV_IDENTITY_OIDC_GROUPS_VIEWER` | Not configured | Restart |
| Operator groups | `clamav.identity.oidc.groups.operator` | `CLAMAV_IDENTITY_OIDC_GROUPS_OPERATOR` | Not configured | Restart |
| Admin groups | `clamav.identity.oidc.groups.admin` | `CLAMAV_IDENTITY_OIDC_GROUPS_ADMIN` | Not configured | Restart |
| Scan requests per minute | `api.v1.scan-rate-limit-per-minute` | `API_V1_SCAN_RATE_LIMIT_PER_MINUTE` | `30` | Restart |
| Maximum API page size | `api.v1.max-page-size` | `API_V1_MAX_PAGE_SIZE` | `100` | Restart |
| Maximum signature age (hours) | `api.v1.clamd.max-database-age-hours` | `API_V1_CLAMD_MAX_DATABASE_AGE_HOURS` | `168` | Restart |

## Notifications

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Enable notifications | `app.webhook.enabled` | `CLAMAV_WEBHOOK_ENABLED` | `false` | Live |
| Webhook URL (private) | `app.webhook.url` | `CLAMAV_WEBHOOK_URL` | Not configured | Live |
| Connect timeout | `clamav.webhook.connect-timeout` | `CLAMAV_WEBHOOK_CONNECT_TIMEOUT` | `5s` | Restart |
| Response timeout | `clamav.webhook.read-timeout` | `CLAMAV_WEBHOOK_READ_TIMEOUT` | `10s` | Restart |
| DNS timeout | `clamav.webhook.dns-timeout` | `CLAMAV_WEBHOOK_DNS_TIMEOUT` | `5s` | Restart |
| Overall active delivery deadline | `clamav.webhook.delivery-timeout` | `CLAMAV_WEBHOOK_DELIVERY_TIMEOUT` | `20s` | Restart |
| Delivery workers | `clamav.webhook.workers` | `CLAMAV_WEBHOOK_WORKERS` | `2` | Restart |
| Pending delivery capacity | `clamav.webhook.queue-capacity` | `CLAMAV_WEBHOOK_QUEUE_CAPACITY` | `100` | Restart |
| Allow internal webhook receivers | `clamav.webhook.allow-private-addresses` | `CLAMAV_WEBHOOK_ALLOW_PRIVATE_ADDRESSES` | `false` | Restart |

## Network

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Forwarded-header strategy | `server.forward-headers-strategy` | `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Restart |
| Application HTTPS base URL | `clamav.public-url` | `CLAMAV_PUBLIC_URL` | Not configured | Restart |
| URL prefix | `server.servlet.context-path` | `SERVER_SERVLET_CONTEXT_PATH` | Not configured | Restart |
| Secure session cookies | `server.servlet.session.cookie.secure` | `SERVER_SERVLET_SESSION_COOKIE_SECURE` | `false` | Restart |
| Cookie SameSite policy | `server.servlet.session.cookie.same-site` | `SERVER_SERVLET_SESSION_COOKIE_SAME_SITE` | `lax` | Restart |
| Java truststore path | `javax.net.ssl.trustStore` | `CLAMAV_TRUSTSTORE_PATH` | Not configured | Restart |
| Truststore password (private) | `javax.net.ssl.trustStorePassword` | `CLAMAV_TRUSTSTORE_PASSWORD` | Not configured | Restart |
| Truststore type | `javax.net.ssl.trustStoreType` | `CLAMAV_TRUSTSTORE_TYPE` | `PKCS12` | Restart |

## Monitoring

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Enable Prometheus scraping | `clamav.metrics.enabled` | `CLAMAV_METRICS_ENABLED` | `false` | Live |
| Scrape token (private) | `clamav.metrics.scrape-token` | `CLAMAV_METRICS_SCRAPE_TOKEN` | Not configured | Live |
| ClamD telemetry interval (ms) | `clamav.metrics.clamd-poll-ms` | `CLAMAV_METRICS_CLAMD_POLL_MS` | `30000` | Restart |
| Maximum polled endpoints | `clamav.metrics.clamd-max-endpoints` | `CLAMAV_METRICS_CLAMD_MAX_ENDPOINTS` | `12` | Restart |
| Separate management listener port (optional) | `management.server.port` | `MANAGEMENT_SERVER_PORT` | Not configured | Restart |
| Enable configuration observation | `center.observer.enabled` | `CLAMAV_CONFIG_OBSERVER_ENABLED` | `true` | Live |
| Mounted clamd.conf path | `clamav.config.clamd-path` | `CLAMAV_CONFIG_PATH` | Not configured | Restart |
| Mounted freshclam.conf path | `clamav.config.freshclam-path` | `FRESHCLAM_CONFIG_PATH` | Not configured | Restart |
| Synchronize discovered endpoint and policy | `clamav.config.discovery-enabled` | `CLAMAV_CONFIG_DISCOVERY_ENABLED` | `true` | Live |
| Configuration interval (ms) | `clamav.config.poll-ms` | `CLAMAV_CONFIG_POLL_MS` | `30000` | Live |

## System

| Field | Application key | Environment / compatibility input aliases | Default | Activation |
|---|---|---|---|---|
| Default theme | `clamav.ui.default-theme` | `CLAMAV_UI_DEFAULT_THEME` | `system` | Restart |
| Default language | `clamav.ui.default-language` | `CLAMAV_UI_DEFAULT_LANGUAGE` | `en` | Restart |
| Daemon database timestamp timezone | `api.v1.clamd.database-timezone` | `API_V1_CLAMD_DATABASE_TIMEZONE` | `UTC` | Restart |

## Identity validation and display

LDAP contains all 20 runtime options plus the inline and mounted-file credential sources. OIDC contains all 10 runtime options plus its two credential sources. All former Authentication form options appear together when a provider is enabled. Nested depth appears when nesting is enabled; secret-file paths remain under Advanced.

- LDAP nesting depth: **1–8**; connect/read timeouts: **1000–30000 ms**.
- Group filter: blank uses the member attribute; `{0}` is the user DN and `{1}` is the login name.
- Group member/name attributes, pooling, referrals, StartTLS and exact role maps share runtime validation.
- OIDC scopes accept commas or spaces, require `openid`, and are saved in canonical form.
- Group claim, button label and local/provider logout behavior are editable in the same central section.
- Standard `SPRING_SECURITY_OAUTH2_*` aliases remain accepted deployment inputs. GUI values bind only their application namespace, so partial disabled drafts cannot create incomplete Spring registrations.
- Connection tests validate drafts and transport, not actual end-user authorization. Verify real LDAP/SSO sign-in and group mappings separately.

See [the complete guide](COMPLETE_USER_GUIDE.md) for per-field meaning and [validation](CONFIGURATION_CENTER_VALIDATION.md) for the tested scope.

## Validated limits

API page size: 1–500. Metrics endpoint slots: 1–12. Watch interval: 30–3600 seconds, checked with a one-second scheduler tick. REST multipart field names must start with a letter and contain at most 51 letters, digits, underscores or hyphens. REST paths require valid absolute URI path syntax without traversal or query components. Upload and quarantine directories cannot be blank. Flyway baseline versions must be numeric; enabling legacy baselining still requires version 1 and an acknowledged backup.

## API and external REST deployment

See [built-in API and external REST setup](REST_API_DEPLOYMENT.md) for token examples, a complete REST-service Compose, GUI/client-environment mappings, upload-size units and endpoint compatibility. The REST backend's own `APP_*` and `CLAMD_*` inputs are configured in that container's deployment.

## Database test budgets

The pool connection timeout also supplies the database Test connection budget, clamped to 1–30 seconds for interactive tests. The outer test deadline adds five seconds; the default is 15 seconds. PostgreSQL uses seconds, while MySQL/MariaDB use milliseconds. New H2 databases are reported as not initialized, not falsely verified. See [database connection tests](DATABASE_CONNECTION_TESTS.md).
