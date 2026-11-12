# Security update: 2.3.9-kosz.2

The Docker Scout screenshot identified `tomcat-embed-core-10.1.55.jar` inside `/app/clamav-web-client.jar`, with these findings:

- CVE-2026-65905: DIGEST authentication replay handling.
- CVE-2026-68525: FORM authentication redirect and method-specific constraints.
- CVE-2026-65182: security constraint ordering.

This update sets the Spring Boot parent property in `pom.xml`:

```xml
<tomcat.version>10.1.60</tomcat.version>
```

The override updates the embedded Tomcat modules together. The GUI/API features remain in place, and the included Compose file now carries forward your REST URL `http://192.168.68.144:3000`.

## Why 10.1.60

Although Scout lists 10.1.58 as a fix threshold, Apache says the 10.1.58 release vote did not pass. The fixes were distributed in 10.1.59. Version 10.1.60 also includes subsequent security fixes and is the current 10.1 release checked on 2026-09-28.

Official references:

- https://tomcat.apache.org/security-10.html
- https://tomcat.apache.org/download-10.cgi

The screenshot establishes a vulnerable dependency version; it does not establish that the GUI is exploitable through every listed issue. Exploitability depends on the affected Tomcat features and configuration. The update removes the affected dependency version rather than relying on that distinction.

## Rebuild your GUI

Extract the source over your source directory while preserving your existing `conf/`, `data/` and any production-specific Compose settings. Use the new image tag `kosztyk/clamav-web-client:2.3.9-kosz.2`.

The archive's Compose filename is `docker-compose.yml`. Use it explicitly so an older `docker-compose.yaml` does not take precedence:

```bash
docker compose -f docker-compose.yml build --pull --no-cache clamav-web-client
docker compose -f docker-compose.yml up -d --force-recreate --no-deps clamav-web-client
docker compose -f docker-compose.yml logs --tail=100 clamav-web-client
```

If maintaining your own Compose file, preserve its settings and change only its image tag to `kosztyk/clamav-web-client:2.3.9-kosz.2`, then build and recreate that file's GUI service explicitly. No REST API image change is required.

Re-scan the newly built image in Docker Scout. The dependency listing should show `tomcat-embed-core`, `tomcat-embed-el` and `tomcat-embed-websocket` at 10.1.60. Scanning a previous tag, digest or running container can still show 10.1.55. Refreshing base images without changing the Maven dependency does not update the Tomcat library packaged inside the application JAR.

This is a targeted dependency fix. A zero-vulnerability Docker image is not claimed; Docker Scout must scan the rebuilt image to evaluate its other contents.
