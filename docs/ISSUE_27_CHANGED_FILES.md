# Issue #27 — Local beta2 source delta

Baseline: `clamav-webui-v2.1.0beta2-issues25-26-full-source.zip`.

Changed files:
- `src/main/resources/application.properties` — secure session cookie defaults, explicit HttpOnly, SameSite, adjustable timeout and HTTP-dev override.
- `src/main/resources/configuration-center/catalog.json` — secure-by-default setting plus operational hints.
- `src/main/java/info/trizub/clamav/webclient/config/ClamAVWebClientAppConfig.java` — same Secure/HttpOnly/SameSite policy on `clamav_lang`, with strict SameSite validation and normalized values.
- `src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java` — `Referrer-Policy: no-referrer`; retain existing security filter chain and H2 frame exception.
- `src/test/java/info/trizub/clamav/webclient/config/CookieLocaleSecurityTest.java` — locale cookie tests for TLS termination and explicit HTTP-only development mode.
- `src/test/java/info/trizub/clamav/webclient/config/CookieSessionHttpIntegrationTest.java` — embedded servlet container tests of `JSESSIONID` and `clamav_lang` HTTP response headers.
- `src/test/java/info/trizub/clamav/webclient/configuration/ConfigurationCoreTest.java` — updated unsafe SameSite=None test to explicitly disable Secure, since the new secure default is true.
- `docs/CONFIGURATION_REFERENCE.md` — document new default.
- `docs/PHASE3_ENTERPRISE_IDENTITY.md` — correct obsolete forwarded-header default guidance; issue #13 remains unchanged.
- `docs/ISSUE_27_COOKIE_SECURITY.md` — deployment and upgrade guidance, curl verification, CSP limitation.
- `README.md` — link to issue #27 and HTTPS/HTTP-dev instructions.
- `docs/ISSUE_27_CHANGED_FILES.md` — this change inventory.

**Not changed:** Issue #25 `AuditService`/`AuditEventWriter`, issue #26 validation classes and handlers, database migrations, Docker files, scanning/retention code, original session revocation logic, and application build version (`v2.1.0beta2`).

**Validation performed:** source defaults and invariants, Phase 1/2/4 static checks, i18n checks, JSON parsing, source-package secret and runtime-state audit, unchanged-file binary comparison and ZIP integrity. **Not performed:** complete Maven build, Spring integration tests or live NGINX cookie roundtrip; Maven and a running server were unavailable in the local execution environment. The JAR-dependent configuration and path-privacy validators cannot execute without a built JAR. Do not claim runtime acceptance until these pass.
