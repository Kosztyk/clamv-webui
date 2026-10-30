# Dependency security revision 1 — 30 October 2026

This revision replaces the affected runtime dependency versions behind all 18
CVEs shown in the supplied Docker Scout screenshots. It keeps the application
version at 2.0.0 and identifies the revised image as `2.0.0-security.1`.

## Dependency changes

| Component | Attached source before this revision | Revised runtime |
| --- | --- | --- |
| Spring Boot | 3.5.16 | 4.0.9 |
| Spring Framework | 6.2.19 | 7.0.10 |
| Embedded Tomcat | 10.1.55 | 11.0.27 |
| Jackson 2 core/databind | 2.21.5 | 2.21.7 |
| Jackson 3 core/databind | Not present | 3.1.7, managed by Boot 4 |
| Log4j API and SLF4J bridge | 2.24.3 | 2.25.5 |
| MariaDB Connector/J | 3.5.8 | 3.5.10 |

These are resolved dependency versions, checked against the nested libraries in
the built executable JAR. Boot manages the compatible dependency set. The
existing PostgreSQL 42.7.13 override is retained.

The two Spring findings affect Spring Framework through 6.2.19. At verification,
Maven Central did not publish a later 6.2 patch. The revision therefore moves to
the stable Boot 4.0 line, which provides patched Spring Framework 7.0.10. This is
a framework migration, rather than five independent version overrides.

## Reported CVEs and corresponding fixes

Each CVE below links to its published CVE record, including the affected version
ranges and upstream advisory references. The version column is the version
actually packaged in this revision, rather than an asserted minimum fix.

| Reported CVE | Dependency | Revised packaged version |
| --- | --- | --- |
| [CVE-2026-65637](https://www.cve.org/CVERecord?id=CVE-2026-65637) | Tomcat | 11.0.27 |
| [CVE-2026-65905](https://www.cve.org/CVERecord?id=CVE-2026-65905) | Tomcat | 11.0.27 |
| [CVE-2026-47884](https://www.cve.org/CVERecord?id=CVE-2026-47884) | Spring Framework | 7.0.10 |
| [CVE-2026-47890](https://www.cve.org/CVERecord?id=CVE-2026-47890) | Spring Framework | 7.0.10 |
| [CVE-2026-68525](https://www.cve.org/CVERecord?id=CVE-2026-68525) | Tomcat | 11.0.27 |
| [CVE-2026-65182](https://www.cve.org/CVERecord?id=CVE-2026-65182) | Tomcat | 11.0.27 |
| [CVE-2026-91776](https://www.cve.org/CVERecord?id=CVE-2026-91776) | Jackson databind | 2.21.7 / 3.1.7 |
| [CVE-2026-68497](https://www.cve.org/CVERecord?id=CVE-2026-68497) | Jackson databind | 2.21.7 / 3.1.7 |
| [CVE-2026-91777](https://www.cve.org/CVERecord?id=CVE-2026-91777) | Jackson databind | 2.21.7 / 3.1.7 |
| [CVE-2026-89425](https://www.cve.org/CVERecord?id=CVE-2026-89425) | Jackson core | 2.21.7 / 3.1.7 |
| [CVE-2026-89407](https://www.cve.org/CVERecord?id=CVE-2026-89407) | Jackson core | 2.21.7 / 3.1.7 |
| [CVE-2026-49844](https://www.cve.org/CVERecord?id=CVE-2026-49844) | Log4j API | 2.25.5 |
| [CVE-2026-55856](https://www.cve.org/CVERecord?id=CVE-2026-55856) | MariaDB Connector/J | 3.5.10 |
| [CVE-2026-55857](https://www.cve.org/CVERecord?id=CVE-2026-55857) | MariaDB Connector/J | 3.5.10 |
| [CVE-2026-55858](https://www.cve.org/CVERecord?id=CVE-2026-55858) | MariaDB Connector/J | 3.5.10 |
| [CVE-2026-83557](https://www.cve.org/CVERecord?id=CVE-2026-83557) | Jackson databind | 2.21.7 / 3.1.7 |
| [CVE-2026-19032](https://www.cve.org/CVERecord?id=CVE-2026-19032) | Jackson databind | 2.21.7 / 3.1.7 |
| [CVE-2026-61700](https://www.cve.org/CVERecord?id=CVE-2026-61700) | MariaDB Connector/J | 3.5.10 |

The CVE records were retrieved from the CVE Program API at
`https://cveawg.mitre.org/api/cve/<CVE-ID>`. Published artifact availability and
Boot's managed versions were checked against Maven Central. Upstream guidance:
[Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[Boot JSON configuration](https://docs.spring.io/spring-boot/4.0/reference/features/json.html),
[Tomcat security notices](https://tomcat.apache.org/security-11.html), and
[Log4j security notices](https://logging.apache.org/security.html).

## Compatibility changes

- Web MVC, OAuth2 client, Flyway and test dependencies use the Boot 4 modules.
- The application keeps its Jackson 2 `ObjectMapper` and explicitly selects
  Jackson 2 for HTTP and WebSocket messaging. Existing REST JSON, persisted
  authentication settings, notifications and scan records keep that mapper.
  Both Jackson generations present in the runtime use patched releases.
- Security 7's `PathPatternRequestMatcher` replaces the removed
  `AntPathRequestMatcher` for v1 API JSON authentication/authorization errors.
- Test imports, metrics test configuration and Testcontainers 2 database modules
  follow the new package names. Regression coverage includes login redirects
  and anonymous API JSON responses under an application context path.
- SMTP remains in the executable JAR. Its Maven dependency is no longer marked
  optional, because Boot 4 excludes optional dependencies when repackaging.
- The H2 console module remains available when explicitly enabled. Flyway
  auto-configuration is supplied by the new Flyway starter.

Existing Flyway SQL migrations, UI templates, translations, dashboard assets,
standalone CLI binaries, deployment volumes and example Compose settings are
unchanged. No production database or running deployment was accessed.

## Verification

The checked build used OpenJDK 17.0.20, Maven 3.9.11 and the Java 17 source
target. The Dockerfile continues to build and run with Java 21.

- `mvn -B -ntp verify`: **BUILD SUCCESS**, 219 tests, zero failures, zero errors;
  214 passed and five AF_UNIX socket tests were skipped by the environment.
- Phase 0/1, database migration structure and Phase 4 source checks passed.
- All five configuration-observer JavaScript tests passed. Six-language catalog,
  route and JavaScript syntax checks passed.
- A CycloneDX runtime SBOM contains 156 Maven components. Querying their package
  URLs against the OSV API returned no matching advisories on the verification
  date. This is an OSV database check, not a Docker Scout result.
- The packaged JAR starts successfully with embedded Tomcat 11 and a temporary
  H2 database. See the accompanying HTTP smoke report for the checks performed.

The runtime SBOM and verification results are in `docs/security/`.

Browser execution was blocked by this environment's AF_UNIX restrictions.
Docker is unavailable here, so a complete container build/scan, the native
ClamdTop executable, live EICAR scanning and the Docker-based PostgreSQL/MySQL/
MariaDB integration profile were not run. Live LDAP/OIDC providers were not
contacted. The new dependency set needs those deployment checks on your host.

The additional Trivy scan attempt failed while accessing its local vulnerability
database and produced no usable security result. No zero-vulnerability claim is
based on that attempt. OSV results and vendor version checks do not replace
scanning the rebuilt container, including its OS and JRE packages.

## Build and scan the corrected image

Extract the source into a separate source directory. Keep the running
installation's Compose file, `conf/`, `data/`, external database credentials,
LDAP truststore and secrets in place.

From the corrected source directory:

```bash
docker build --pull --no-cache -t clamav-web-client:2.0.0-security.1 .
docker scout cves local://clamav-web-client:2.0.0-security.1
```

The Dockerfile runs Maven verification with tests enabled. `--pull` refreshes
the build and JRE base images; `--no-cache` also refreshes the runtime package
installation. Scout's `local://` source selects the newly built local image.

To check specifically for the 18 reported CVEs without filtering out findings
that lack a fix:

```bash
docker scout cves --exit-code \
  --only-cve-id CVE-2026-65637,CVE-2026-65905,CVE-2026-47884,CVE-2026-47890,CVE-2026-68525,CVE-2026-65182,CVE-2026-91776,CVE-2026-68497,CVE-2026-91777,CVE-2026-89425,CVE-2026-89407,CVE-2026-49844,CVE-2026-55856,CVE-2026-55857,CVE-2026-55858,CVE-2026-83557,CVE-2026-19032,CVE-2026-61700 \
  local://clamav-web-client:2.0.0-security.1
```

Also review the full scan for other or newly disclosed issues. Scout returns
exit code 2 when the selected scan finds vulnerabilities; see the
[Docker Scout CLI reference](https://docs.docker.com/reference/cli/docker/scout/cves/).

After the image passes your checks, select it in your existing Web UI service:

```yaml
image: clamav-web-client:2.0.0-security.1
pull_policy: never
```

Then recreate that service, substituting its existing name if different:

```bash
docker compose up -d --no-deps --no-build --force-recreate clamav-web-client
docker compose logs -f clamav-web-client
```

Keep the existing database and configuration mounts. This security revision
does not require enabling Flyway baselining or resetting application data.
