# Configuration Center v2.1.0 validation — 1 November 2026

The complete application retains the repository snapshot at
`bf380de7f12dda98c99397fb8ecf57b5fd03e3c6` and the previous Configuration Center
edition. This consolidated source revision keeps application and CLI version **2.1.0**; the API stays `/api/v1` and
Flyway stays V4. Earlier features and issues #10, #11 and #12 remain included.

## Automated results

| Check | Result |
|---|---|
| Clean `mvn -B -ntp clean verify` | **609 tests: 604 passed, 5 skipped, zero failures/errors** |
| Configuration Center core, protocol, connection and MVC tests | **42 passed** across the four original focused test classes |
| Database probe regressions | **28 passed**: real H2, all four packaged drivers, safe diagnostics and delayed PostgreSQL authentication through the probe and Hikari pool |
| Direct HTTP database setup browser workflow | **18 passed**: unsaved slow PostgreSQL test, distinct failures, new H2 warning, review/apply/start and authenticated H2 retest |
| Native MySQL 8.0.46 and MariaDB 10.11.14 | **10 stages passed** across both real engines: pre-start draft test, wrong password, missing database, full application startup/migrations and post-start retest/readiness; tests did not write schema or save drafts |
| Complete field persistence and deployment overrides | **215 passed**: two checks for each of 107 fields plus invalid-value regressions |
| Saved-value runtime consumers | **10 passed**, using actual framework binders, local HTTP fixtures, files, H2, API limits and scheduled collection |
| Watcher timing/retry and disabled-engine submission | **4 passed**; primary rename/name-collision cases are also covered |
| All-field real-browser workflow | **236 passed**: all 107 controls inspected, 55 non-identity keys saved, actual restart, self-signed REST TLS, management port, scanning and quarantine |
| Packaged JAR acceptance | **36 passed**, including direct HTTP and forwarded HTTPS paths |
| Consolidation, legacy-provider upgrade and live job browser workflow | **23 passed** in Chromium against the packaged app under `/clamav`, with encrypted legacy LDAP values and real recreation |
| Earlier unchanged direct HTTP browser workflow | **25 passed** in Chromium against the real packaged application, with no proxy or forwarded headers |
| Earlier unchanged HTTPS browser workflow | **19 passed** through an actual local TLS-terminating proxy using a self-signed certificate |
| Existing UI regression gate | Passed: static inventories, observer JavaScript, six Chromium locales and central LDAP/OIDC Test/Review/Apply routes, complete provider fields, draft retention and administrator verification |
| Existing deployment helper | Unchanged; the prior v2.1.0 run passed **10 scenarios** with a simulated Docker CLI |
| CI first-run helper | Unchanged; previously passed against the real packaged app with only Docker token-file access simulated; completed settings retained |
| CLI | Source unchanged; the prior v2.1.0 Go 1.26.0 vet/race checks remain applicable |
| CLI release builder | Source unchanged; previous six-target build/checksum and Linux version checks retained |
| Earlier unchanged CLI/MCP Spring integration | Passed against the real Spring API, including Basic/CSRF, scoped Bearer operations and MCP; ClamD was a synthetic wire fixture |
| Bash/Python/JavaScript syntax and diff whitespace | Passed |
| Final executable JAR | ZIP/CRC passed; build metadata and setup footer verified as 2.1.0 |
| Complete-source GitHub publisher | **12 passed** with actual local bare Git remotes and an offline GitHub CLI fixture; default/protected branch, PR, dry run, repeat and unsafe/tampered ZIP cases |
| Public-source privacy/integrity | All release files checked against supplied identifiers; binary assets inspected; no runtime credentials/state; final ZIP CRC, file list and SHA-256 manifest verified |
| Configuration documentation | README has explicit GUI and Compose/hybrid procedures and exact recovery credential command; YAML parsed and examples/local links checked |

Maven ran with Java 17. The Dockerfile builds and runs with Java 21. Docker was
unavailable here, so no Docker image build or container-native ClamdTop/EICAR run
is claimed. Native MySQL/MariaDB engines ran with isolated temporary data over loopback TCP; PostgreSQL used its actual JDBC driver against a protocol fixture, not a live PostgreSQL engine. The five skipped Java tests are
`UnixClamdClientTest` cases: this execution environment prohibits AF_UNIX
sockets. They are not counted as passes. Packaged recovery tests use real H2
and an intentionally unreachable PostgreSQL address. Cross-compilation does
not claim execution of the macOS, Windows or ARM64 binaries.

## Complete field audit

See [the per-field wiring and evidence report](CONFIGURATION_FIELD_AUDIT.md) for every global key. This audit found and corrected disabled-engine scan submissions, primary-engine policy/name handling, watcher timing and failed-enqueue retry behavior, mismatched numeric limits, invalid REST multipart/path inputs and invalid storage/baseline inputs. The GUI field audit and runtime/restart browser regression now run in CI. Existing direct/proxy TLS and CLI results above are explicitly identified when retained from the preceding validation.

## Consolidation and live completion regression coverage

- All 20 LDAP runtime option names and all 10 OIDC option names have a unique
  central mapping, including basic attributes previously hidden under Advanced.
  Both credential sources remain available. Validation bounds match runtime.
- Old global settings/provider/Prometheus/observer writers reject mutations;
  old GET bookmarks redirect. Diagnostic/download views contain no duplicate
  inputs. The default endpoint has one editor and a stable selected record.
- A real packaged-app test seeds an encrypted legacy LDAP row, verifies every
  option in the GUI, saves partial changes and recreates the process. Untouched
  fields and bind credentials survive. Canonical OIDC scopes have no false
  pending restart. Disabled OIDC drafts do not create incomplete Spring OAuth
  registrations. Local administrator login remains available.
- Three real queued application uploads run against a gated synthetic ClamD:
  two running and one queued become finished with clean, detected and error
  verdicts without a browser navigation or reload. Focus remains on the job
  link. Transient refresh errors retain the last results, recover automatically
  and stop polling after navigation. Returning through PJAX fetches fresh HTML.
- The new browser test uses direct HTTP and a servlet context prefix. The
  existing direct-setup and actual HTTPS-proxy browser flows also pass.

## Direct HTTP regression coverage

- Fresh setup opens before database initialization. The owner credential from
  this instance unlocks the page directly at the opened browser origin.
- A wrong credential has a distinct 401 explanation. Protocol tests distinguish
  it from the temporary 429 retry limit; both HTTP adapters return Retry-After.
- HTTP credential confirmation starts unchecked and resets on a new page.
  Without it, the browser sends no draft password. The server independently
  rejects unconfirmed new credentials and sensitive applies.
- Review masks the database password. Database acknowledgement remains
  mandatory. Saved secrets are encrypted before the database is created.
- Start application hands off to the normal login without a reverse proxy.
  Local administrator login and sensitive password re-verification work over
  direct HTTP. Wrong administrator passwords remain rejected.
- Monitoring changes apply live; worker-capacity changes remain staged for
  restart. API Bearer tokens still receive `403 tls_required` over plain HTTP.
- Disabled REST starts collapsed with no preconfigured URL. Seven categories,
  dark theme, mobile layout and Unix-socket field visibility work. All browser
  requests use the actual opened origin, and no JavaScript exceptions occur.

## Retained safeguards exercised

The shared catalogue has 107 fields across seven categories. Validation rejects
unknown fields, invalid ranges, unsupported drivers and unsafe JDBC options.
Persistence tests cover encryption, matching-key requirements, masked views,
explicit secret clearing, revision conflicts and expired/replayed reviews.
Deployment environment/JVM/external-file values remain authoritative, including
explicit false and mounted identity secret files. Role checks, CSRF, same-origin
recovery checks and bounded authentication attempts remain enforced.

Packaged tests cover persistence across recreation, startup-bound settings,
protected recovery on external database failure, health 503 during recovery and
prevention of silent H2 fallback. Connection tests remain read-only and bounded.
The existing webhook, identity, metrics and scan tests are retained in the full
Maven suite. Existing v2.0.0 security advisory and reported production-validation
files retain their original scope; they are not new scans or field tests of
v2.1.0.

## Reproduce

From the complete source with Java, Maven, Python, Node and browser dependencies
installed:

```bash
mvn -B -ntp clean verify
python3 tools/verify-configuration-center.py
python3 scripts/test_configuration_center_direct.py
python3 scripts/test_configuration_center_consolidation.py
python3 scripts/test_configuration_database_setup.py
bash scripts/release-gate.sh --static-only
python3 scripts/test-server-deployment-helper.py
```

The browser scripts need Playwright and its Chromium, or an explicit
`--chromium` path. The consolidation test also needs a JDK to seed its synthetic legacy fixture. Acceptance scripts use temporary directories and synthetic
credentials; they do not operate on production volumes. The packaged test's
forwarded mode simulates the HTTP side of a trusted TLS edge. Its direct mode
sends no forwarded headers. The separate HTTPS browser validation used a real
self-signed test proxy; certificate-error acceptance was test-only.

For CLI verification, from `cli/`:

```bash
go vet ./...
go test -race -count=1 ./...
go run ./tools/build.go
```

Then run `python3 scripts/test-clamctl-integration.py` from the repository root.
It requires the packaged JAR and the generated Linux amd64 CLI binary. GitHub
Actions also initializes the fresh Docker deployment through HTTP setup before
running the retained container/EICAR checks.

For the normal full application image:

```bash
docker build --pull -t clamav-web-client:2.1.0-local .
```

No `build-and-start.sh --trusted-proxy` step, image patch or special build
argument is involved. See [the field consolidation audit](CONFIGURATION_CONSOLIDATION.md), [release notes](RELEASE_NOTES_V2_1_0.md),
[setup and deployment](CONFIGURATION_CENTER.md) and
[all fields and aliases](CONFIGURATION_REFERENCE.md).

The earlier functional Configuration Center screenshot remains available at
[docs/images/configuration-center.png](images/configuration-center.png); its
version label predates this release.

## Documented source package — 1 November 2026

The complete release retains every previous application path and includes a minimum named-volume Compose with no application environment fields, a Compose-managed connection example, the GitHub publisher and a file-integrity manifest. README and current guides explicitly distinguish setup owner access from local administrator login. Private deployment identifiers were replaced throughout source examples, historical documentation, comments and test fixtures; SQL URL templates now use `appdb`. See [privacy and integrity](SOURCE_PACKAGE_PRIVACY.md) and [publishing](PUBLISHING.md).

The publisher was exercised with local Git remotes and a simulated `gh`, not with a live GitHub push. Its pinned CLI asset digests were verified against the official release API. Container builds/deployment and external production services are still outside this environment.

## Database probe correction — 1 November 2026

The old three-second PostgreSQL login budget was reproduced as a false failure with a four-second authentication fixture. The corrected probe uses the configured pool connection budget with bounded, driver-specific timeout units and explicit driver selection before Spring startup. New H2 databases now receive an uninitialized informational result, existing `IFEXISTS` options are normalized, and failed tests explain the cause without including raw JDBC messages or secrets. Direct HTTP consent, unchanged saved revisions and post-initialization retesting are verified. See [database test details and reproduction](DATABASE_CONNECTION_TESTS.md); the optional Docker integration workflow now exercises the probe before and after migrations for all three external SQL families.
