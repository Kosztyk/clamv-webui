# Reported deployment validation

This page records deployment results reported on **31 October 2026**. These
reports are evidence for the named environments and checks, not a claim that
every identity provider, database engine, Kubernetes policy or registry image
has been independently certified.

## Kubernetes Prometheus exporter — reported by fchicout

Source: [field report on issue #7](https://github.com/Kosztyk/clamv-webui/issues/7#issuecomment-6543165620).

- ClamAV Web UI 2.0.0 in Kubernetes; Prometheus scraping
  `/actuator/prometheus` every 15 seconds through Kubernetes Service DNS.
- Missing/invalid scrape credentials rejected; the configured
  `CLAMAV_METRICS_SCRAPE_TOKEN` accepted with a 200 text exposition response.
- Prometheus target reported UP and parsed the samples successfully.
- Reporter verified scan counters/duration/detections, ClamD slot/thread/queue/
  memory/database gauges, application worker and job backlog gauges, PostgreSQL
  availability, and endpoint availability/staleness/configuration flags.

This validates the reported scrape gate and telemetry. It does not imply that
multiple Web UI replicas are supported for distributed job execution. The
Prometheus scrape-token gate is separate from `/api/v1` Bearer authentication.

## LLDAP over verified LDAPS — reported by fchicout

Source: [field report on issue #3](https://github.com/Kosztyk/clamv-webui/issues/3#issuecomment-6543162862).

- Kubernetes deployment using LLDAP over LDAPS with verified certificates.
- User search under `ou=people` with `(uid={0})`; group search under `ou=groups`
  with `(member={0})`.
- `CLAMAV_IDENTITY_LDAP_GROUPS_ADMIN=clamav_ui_admins` mapped the dedicated
  directory group to `ROLE_ADMIN`.
- Successful credential authentication and group-based administrator access.
- Users without mapped groups denied access; local database administrator
  sign-in remained available alongside LDAP.

This is LLDAP acceptance evidence. It does not stand in for testing Microsoft
Active Directory, OIDC providers, nested groups or every possible TLS setup.

## Operator's local Docker deployment — reported by Kosztyk

The operator built the updated full source using the normal Docker build and
tested a LAN deployment behind Nginx with a self-signed certificate:

| Check | Reported result |
|---|---|
| Runtime identity | UID/GID `10001:10001` |
| Persisted filesystem access | Temporary writes to `/app/conf` and `/app/data` passed |
| Application protection | JAR readable and not writable by the runtime user |
| Direct backend HTTP with fake Bearer token | `403 tls_required` |
| HTTPS through Nginx with fake Bearer token | `401 invalid_token`, confirming the request reached token validation after HTTPS recognition |
| Focused webhook tests in a Maven Docker container | 82 tests, zero failures/errors/skips, BUILD SUCCESS |

Existing root-owned configuration caused an initial startup failure while the
application tried to set owner-only permissions. Migrating application volume
ownership resolved it; startup subsequently completed. A Docker mount marked
read/write did not by itself satisfy file ownership requirements.

These checks do not establish successful authentication with a valid API token
or actual production notification delivery. Complete those final checks using
[Update and verify](UPDATE_AND_VERIFY.md). Self-signed certificate trust is a
client setting; public DNS is not required for reverse-proxy HTTPS recognition.

## Automated checks and remaining deployment coverage

- Package verification evidence: `SERVER_PACKAGE_VALIDATION.json` and
  `security/webhook-resilience-validation.json`.
- Runtime dependency query evidence: `security/webhook-runtime-dependencies.json`;
  this is not a scan of Docker OS/JRE layers.
- Publication-time source checks: `security/repository-update-validation.json`.
- Native Docker image/PTY/EICAR checks require a Docker-capable runner; the CI
  workflow contains them. External database engine tests use the separate
  Docker-backed integration profile.

Keep reports sanitized: do not attach API/scrape tokens, webhook URLs, LDAP bind
passwords, encryption keys or production database credentials to GitHub issues.
