# Security policy

## Supported releases

| Release line | Security support |
| --- | --- |
| Current `2.1.0` prerelease candidates | Active development; fixes evaluated on the current branch |
| `2.0.x` | Legacy release; backports are not guaranteed; migrate when supported |
| `1.x` | No active security maintenance |

The release status of an individual tag is shown under
[GitHub Releases](https://github.com/Kosztyk/clamv-webui/releases). A source
archive or a draft release is **not** a security-patched production image.
Do not infer security support solely from a version label.

## Report vulnerabilities **privately**

**Do not disclose exploits, credentials, tokens, real IPs, full internal URLs,
customer data or working proof-of-concept attacks in public issues or PRs.**

Use the repository's [private vulnerability reporting form](https://github.com/Kosztyk/clamv-webui/security/advisories/new)
when the feature is enabled. GitHub privately delivers the report to repository
maintainers, with an advisory collaboration channel. Include a concise impact
summary, affected release, reproduction steps with redacted or fabricated
inputs, and any proposed patch; do not upload real secrets. Request a CVE
through the advisory process only when appropriate.

**Administrator setup required:** Under **GitHub → repository Settings → Code
security → Private vulnerability reporting**, enable the feature and verify
that the private advisory submission form opens for an unaffiliated reporter.
`SECURITY.md` does not activate this GitHub setting; acceptance is incomplete
until a repository administrator verifies it. If private reporting is not yet
available, contact the maintainer through an already-established private
channel; do not move disclosure to a public GitHub issue.

## Coordinated disclosure process

1. Maintainers acknowledge receipt privately as soon as practical; urgent
   critical reports receive priority. Do not promise a fixed SLA without a
   funded security response team.
2. Reproduce and scope the finding without transferring live customer data.
   Apply mitigation and rotate exposed credentials immediately if necessary.
3. Patch on a restricted branch/private advisory when appropriate, run the
   complete CI `gate`, contract tests and tag release validation, and publish
   signed, checksummed artifacts only after approval.
4. Coordinate publication with the reporter, issue a GitHub Security Advisory
   when appropriate, and describe upgrades/mitigations in release notes.
5. Treat leaked internal IPs as information exposure and audit repository
   history, published images and previously downloaded archives; changing
   working-tree files cannot retroactively erase already-public revisions.

## Security of build artifacts and local deployment

Verify GitHub Actions provenance, image digests/Cosign signatures, and signed
CLI checksum manifests as described in `RELEASE.md`,
`docs/ISSUE51_SIGNED_GHCR_RELEASE.md` and
`docs/ISSUE54_CLI_QUALITY_SIGNED_RELEASE.md`. Local Docker Compose examples
contain documentation-only TEST-NET addresses, not deployable LAN defaults.
Keep real server addresses, database URLs with passwords, and TLS keys in
protected runtime config and deployment secrets; never commit them.
