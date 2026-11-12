Issue #23 adds two restart-bound limiter capacities, bringing the current catalogue to **117 fields** (23 live, 94 restart-bound). Generic persistence/precedence and actual consumer checks cover both. [Bounded limiter validation](security/bounded-limiters-validation.json) records the current 832-test build and packaged HTTP checks. Historical counts and browser audits below retain their edition scope.

Issue #22 adds two restart-bound session durations, bringing the current catalogue to **115 fields** (23 live, 92 restart-bound). Both join the generic review/persistence and override checks. Current Java and packaged HTTP evidence, together with the Chromium environment limitation, is in [session revocation validation](security/session-revocation-validation.json). The historical browser field audit below retains its original scope.

Issue #13 adds two proxy allowlist fields, bringing the current catalogue to **109 fields** (19 live, 90 restart-bound). The original audit below covered 107 fields. Current forwarding regressions and all-fields persistence/precedence checks are recorded in [issue #13 validation](security/forwarded-header-trust-validation.json).

Current issue #18 adds four live cleanup/history fields, bringing the catalog to **113**. They participate in the generic field review/persistence and override tests; [upload-specific behavior and results](UPLOAD_RETENTION.md) cover their runtime use. Historical baseline counts below describe the earlier audit.

# Configuration Center: complete field audit (v2.1.0)

The audit covers all **107 fields**, across seven categories: **19 live** and **88 restart-bound**. It checks the global editor, review/apply boundary, encrypted credential storage, deployment precedence, startup snapshots and runtime consumers. The application still builds directly with:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

## Corrections found by the audit

- Disabled engines were still accepted by browser and watcher submission paths. The shared scan service now rejects them before writing files or jobs; execution also rechecks queued jobs before contacting ClamD.
- Renaming the primary engine could bypass daemon-derived size/exclusion policy, which used its old literal name. Policy now follows the primary engine record.
- A primary-engine name colliding with an additional engine could be saved before the database rejected the live change. Review and apply now reject the collision first.
- Watch intervals such as 45 seconds were rounded up by a fixed 30-second schedule. A one-second tick now honors the selected interval. Supported values remain 30–3600 seconds.
- Watch files were marked processed before successful job creation. Issue #17 now commits a durable job and its fingerprint together, preserving the old fingerprint on failure. Polls load markers once per directory, traverse beyond the former count/depth limits, and log failures. Disabled engines remain skipped; hourly cleanup prunes absent paths. See [watcher reliability](WATCHER_RELIABILITY.md).
- The form allowed page sizes above the API's 500-row ceiling and more than the metrics collector's 12 endpoint slots. GUI validation now matches both supported limits.
- Invalid REST multipart field names could be silently replaced with defaults. Invalid path whitespace/percent escapes could fail only when making a request. Review now rejects those inputs; the adapter also validates path syntax at construction.
- Blank storage locations and invalid Flyway baseline version text are rejected before saving.

## Verification

`ConfigurationAllFieldsTest` has 215 checks: one review/save/activation test and one deployment-alias/locking test for each field, plus invalid-value regressions. It reopens the actual encrypted configuration files, verifies live versus startup values and confirms that blank secrets retain credentials. Existing core tests cover explicit clearing, optimistic revisions, key loss, ciphertext tampering and independent audit records.

`ConfigurationConsumerWiringTest` exercises real Spring Boot binding and service behavior. `scripts/test_configuration_center_all_fields.py` visits every control in Chromium, including conditional and advanced fields, saves a multi-category draft, recreates the actual packaged application and checks its behavior over HTTP. It uses temporary data, synthetic credentials, a ClamD protocol fixture and a self-signed HTTPS REST receiver. No public DNS or nginx is involved.

The former Settings and Authentication bookmarks redirect to the central editor. Daemon status/files and monitoring/client pages contain no global configuration form; retired global POST writers cannot change settings. Additional endpoints, accounts, API credentials and watched-directory records remain operational resources with their own management screens. They do not provide a second editor for the corresponding global defaults.

Results and current commands are recorded in [CONFIGURATION_CENTER_VALIDATION.md](CONFIGURATION_CENTER_VALIDATION.md) and [SERVER_PACKAGE_VALIDATION.json](../SERVER_PACKAGE_VALIDATION.json). External PostgreSQL/MySQL/MariaDB servers, real identity providers and Docker were not available in this test environment. Their real-world compatibility must not be inferred from property binding or H2 fixtures. Five AF_UNIX client cases remain skipped here; TCP ClamD fixtures and all other tests run.

## Per-field wiring and evidence

Every row additionally receives both exhaustive storage/override checks and the real-browser control inspection. Runtime evidence uses representative valid values and boundary cases; it does not assert every possible deployment combination.


### Connections (28)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `center.clamd.enabled` | Live | EndpointService; ClamavClientProvider; ScanJobService; DaemonScanPolicy | Primary-engine HTTP regression; disabled-engine and renamed-engine tests |
| `center.clamd.name` | Live | EndpointService; ClamavClientProvider; ScanJobService; DaemonScanPolicy | Primary-engine HTTP regression; disabled-engine and renamed-engine tests |
| `clamav.service.host` | Live | EndpointService; ClamavClientProvider; ScanJobService; DaemonScanPolicy | Primary-engine HTTP regression; disabled-engine and renamed-engine tests |
| `clamav.service.port` | Live | EndpointService; ClamavClientProvider; ScanJobService; DaemonScanPolicy | Primary-engine HTTP regression; disabled-engine and renamed-engine tests |
| `clamav.service.platform` | Live | EndpointService; ClamavClientProvider; ScanJobService; DaemonScanPolicy | Primary-engine HTTP regression; disabled-engine and renamed-engine tests |
| `clamav.rest.enabled` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_URL` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_API_KEY` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_AUTH_MODE` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_FORM_KEY` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_TIMEOUT_SECONDS` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_UPLOAD_TIMEOUT_SECONDS` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_REPORT_MAX_JOBS` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_STATS_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_JOBS_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_HISTORY_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_WEBHOOKS_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_SCAN_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_SCAN_HISTORY_PATH` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `CLAMAV_REST_WEBHOOK_FIELD` | Restart | RestApiBridge; RestMonitorController | Saved-value HTTP receiver tests for all paths, headers, form fields, limits and distinct deadlines; packaged self-signed HTTPS receiver |
| `spring.datasource.url` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.datasource.username` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.datasource.password` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.datasource.driver-class-name` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.datasource.hikari.connection-timeout` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.flyway.baseline-on-migrate` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |
| `spring.flyway.baseline-version` | Restart | Spring DataSource/Hikari; Flyway; Hibernate | Saved-property binding, real authenticated H2 connections and migration to V4; existing fresh/legacy H2 migration regressions |

The database keys above also have [dedicated probe/startup validation](DATABASE_CONNECTION_TESTS.md): unsaved draft testing, safe diagnostics, explicit driver selection, driver-specific timeout units, H2 initialization handling and native MySQL/MariaDB startup/retest round trips.

### Scanning (12)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `app.allowedScanRoots` | Live | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.upload.maxBytes` | Live | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.concurrentScans` | Restart | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.storage.uploadDir` | Restart | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.upload.pathScan.enabled` | Live | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.upload.pathScan.fallbackToInstream` | Live | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `spring.servlet.multipart.max-file-size` | Restart | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `spring.servlet.multipart.max-request-size` | Restart | SettingsService; ScanJobService; ScanExecutionService; DaemonScanPolicy | Real filesystem upload/size/root checks; worker-pool capture; packaged INSTREAM/path/fallback scans |
| `app.quarantine.enabled` | Live | SettingsService; QuarantineService | Real enabled/disabled file moves; packaged detected uploads into the saved directory |
| `app.storage.quarantineDir` | Restart | SettingsService; QuarantineService | Real enabled/disabled file moves; packaged detected uploads into the saved directory |
| `app.watch.enabled` | Live | SettingsService; WatcherService | Clock-controlled due-time checks; disabled endpoint, failed enqueue and successful enqueue tests |
| `app.watch.pollSeconds` | Live | SettingsService; WatcherService | Clock-controlled due-time checks; disabled endpoint, failed enqueue and successful enqueue tests |

### Identity & API (37)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `clamav.identity.ldap.enabled` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.url` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.base-dn` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.manager-dn` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.manager-password` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.manager-password-file` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.user-search-base` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.user-search-filter` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.group-search-base` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.group-search-filter` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.group-member-attribute` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.group-name-attribute` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.start-tls` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.pooled` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.nested-groups` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.max-nested-depth` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.referrals` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.connect-timeout-millis` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.read-timeout-millis` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.groups.viewer` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.groups.operator` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.ldap.groups.admin` | Restart | AuthenticationSettingsService; IdentityProperties; DynamicLdapAuthenticationProvider | All-field storage/override tests; existing LDAP compatibility, group-resolution and upgrade browser regressions |
| `clamav.identity.oidc.enabled` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.issuer` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.client-id` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.client-secret` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.client-secret-file` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.scopes` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.groups-claim` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.button-label` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.logout-mode` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.groups.viewer` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.groups.operator` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `clamav.identity.oidc.groups.admin` | Restart | AuthenticationSettingsService; IdentityProperties; LazyOidcClientRegistrationRepository | All-field storage/override tests; existing OIDC compatibility, registration, group and disabled-draft regressions |
| `api.v1.scan-rate-limit-per-minute` | Restart | ScanRateLimitFilter; ApiV1Controller | Saved-value rate filter, accepted/rejected pagination and timezone-aware stale-daemon health; packaged HTTP pagination |
| `api.v1.max-page-size` | Restart | ScanRateLimitFilter; ApiV1Controller | Saved-value rate filter, accepted/rejected pagination and timezone-aware stale-daemon health; packaged HTTP pagination |
| `api.v1.clamd.max-database-age-hours` | Restart | ScanRateLimitFilter; ApiV1Controller | Saved-value rate filter, accepted/rejected pagination and timezone-aware stale-daemon health; packaged HTTP pagination |

### Notifications (9)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `app.webhook.enabled` | Live | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `app.webhook.url` | Live | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.connect-timeout` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.read-timeout` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.dns-timeout` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.delivery-timeout` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.workers` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.queue-capacity` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |
| `clamav.webhook.allow-private-addresses` | Restart | SettingsService; NotificationService; WebhookNotificationProperties; WebhookDeliveryClient; WebhookAddressPolicy | Saved duration/pool/policy binding and dispatch; existing real timeout, saturation, DNS, address-policy and delivery tests |

### Network (8)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `server.forward-headers-strategy` | Restart | Spring ServerProperties; servlet session; UiModelAdvice | Real Boot binder; packaged context-path recreation; existing proxy/direct TLS tests |
| `clamav.public-url` | Restart | UiModelAdvice; OIDC callback/logout helpers | Saved-value model check; existing callback/logout and context-path regressions |
| `server.servlet.context-path` | Restart | Spring ServerProperties; servlet session; UiModelAdvice | Real Boot binder; packaged context-path recreation; existing proxy/direct TLS tests |
| `server.servlet.session.cookie.secure` | Restart | Spring ServerProperties; servlet session; UiModelAdvice | Real Boot binder; packaged context-path recreation; existing proxy/direct TLS tests |
| `server.servlet.session.cookie.same-site` | Restart | Spring ServerProperties; servlet session; UiModelAdvice | Real Boot binder; packaged context-path recreation; existing proxy/direct TLS tests |
| `javax.net.ssl.trustStore` | Restart | ConfigurationEnvironmentPostProcessor; ConfigurationSource; JSSE | GUI-saved file, password and PKCS12 type used by the packaged REST client against a self-signed HTTPS receiver |
| `javax.net.ssl.trustStorePassword` | Restart | ConfigurationEnvironmentPostProcessor; ConfigurationSource; JSSE | GUI-saved file, password and PKCS12 type used by the packaged REST client against a self-signed HTTPS receiver |
| `javax.net.ssl.trustStoreType` | Restart | ConfigurationEnvironmentPostProcessor; ConfigurationSource; JSSE | GUI-saved file, password and PKCS12 type used by the packaged REST client against a self-signed HTTPS receiver |

### Monitoring (10)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `clamav.metrics.enabled` | Live | IntegrationSettingsService; PrometheusAccessFilter; ClamdMetricsCollector; Boot management server | Live real token gate; saved-value scheduled collector/endpoint cap; separate management HTTP listener after recreation |
| `clamav.metrics.scrape-token` | Live | IntegrationSettingsService; PrometheusAccessFilter; ClamdMetricsCollector; Boot management server | Live real token gate; saved-value scheduled collector/endpoint cap; separate management HTTP listener after recreation |
| `clamav.metrics.clamd-poll-ms` | Restart | IntegrationSettingsService; PrometheusAccessFilter; ClamdMetricsCollector; Boot management server | Live real token gate; saved-value scheduled collector/endpoint cap; separate management HTTP listener after recreation |
| `clamav.metrics.clamd-max-endpoints` | Restart | IntegrationSettingsService; PrometheusAccessFilter; ClamdMetricsCollector; Boot management server | Live real token gate; saved-value scheduled collector/endpoint cap; separate management HTTP listener after recreation |
| `management.server.port` | Restart | IntegrationSettingsService; PrometheusAccessFilter; ClamdMetricsCollector; Boot management server | Live real token gate; saved-value scheduled collector/endpoint cap; separate management HTTP listener after recreation |
| `center.observer.enabled` | Live | ClamdConfigurationObserver; IntegrationSettingsService; EndpointService | Actual clamd/freshclam file parsing, immediate mode/poll changes and existing synchronization tests |
| `clamav.config.clamd-path` | Restart | ClamdConfigurationObserver; IntegrationSettingsService; EndpointService | Actual clamd/freshclam file parsing, immediate mode/poll changes and existing synchronization tests |
| `clamav.config.freshclam-path` | Restart | ClamdConfigurationObserver; IntegrationSettingsService; EndpointService | Actual clamd/freshclam file parsing, immediate mode/poll changes and existing synchronization tests |
| `clamav.config.discovery-enabled` | Live | ClamdConfigurationObserver; IntegrationSettingsService; EndpointService | Actual clamd/freshclam file parsing, immediate mode/poll changes and existing synchronization tests |
| `clamav.config.poll-ms` | Live | ClamdConfigurationObserver; IntegrationSettingsService; EndpointService | Actual clamd/freshclam file parsing, immediate mode/poll changes and existing synchronization tests |

### System (3)

| Application key | Activation | Runtime consumer | Runtime evidence |
|---|---|---|---|
| `clamav.ui.default-theme` | Restart | ClamAVWebClientAppConfig; UiModelAdvice; ApiV1Controller; ClamdMetricsCollector | Saved default locale/theme in actual HTML; daemon timestamp conversion in API and metrics tests |
| `clamav.ui.default-language` | Restart | ClamAVWebClientAppConfig; UiModelAdvice; ApiV1Controller; ClamdMetricsCollector | Saved default locale/theme in actual HTML; daemon timestamp conversion in API and metrics tests |
| `api.v1.clamd.database-timezone` | Restart | ApiV1Controller; ClamdMetricsCollector | Actual API stale/fresh timestamp conversion; existing metrics timestamp conversion tests |
