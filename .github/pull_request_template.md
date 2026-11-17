## Summary

What changes, why, and which issue does this address? (Example: `Fixes #123`.)

## Release / compatibility impact

- Version change (if a release is intended): **edit `pom.xml` only**.
- New/changed environment variables, Configuration Center settings or defaults:
- Database schema/Flyway migration and supported upgrade paths:
- `/api/v1` contract and `clamctl` compatibility:
- Authentication, authorization, privacy and network trust changes:
- Release-note category / `CHANGELOG.md` user-visible entry:

## Verification checklist

- [ ] I did not commit passwords, secrets, keys, production config, user data,
      internal IP addresses, hostnames or real infrastructure paths.
- [ ] I ran `python3 tools/audit-source-package.py .` (including private IPv4
      sanitization), and reviewed the diff for values scanner rules may miss.
- [ ] I ran `python3 scripts/check-versions.py` and applicable source tests.
- [ ] I added/updated Java, Go or UI tests, including negative cases as needed.
- [ ] I updated README/user guide/CHANGELOG and release migration notes.
- [ ] I added Flyway migrations for each supported database when applicable,
      and verified safe backup/rollback behavior.
- [ ] I checked API v1 OpenAPI compatibility, CLI behavior and error payloads.
- [ ] I considered security impact (SSRF, auth/session, webhook, path disclosure,
      CSRF, remote dependencies); sensitive details were **not** posted publicly.
- [ ] I reviewed vendored assets and pinned Actions/dependency changes.
- [ ] CI's single `gate` check is green; release tags are not created in PRs.

## Reviewer notes

List specific files, failure scenarios, migration considerations, and any
risk that warrants review by a separate code owner. For vulnerability reports,
use the private channel linked from `SECURITY.md` rather than including
working exploits or secrets in a public PR.
