# Issue #56 — release governance and internal-address privacy

## Scope

The new root-level [`RELEASE.md`](../RELEASE.md) is the maintainer's single
release checklist. [`CHANGELOG.md`](../CHANGELOG.md) tracks user-visible
changes, `.github/release.yml` configures GitHub's generated release-note
categories, `.github/pull_request_template.md` collects review evidence,
[`SECURITY.md`](../SECURITY.md) provides coordinated private disclosure,
and `.github/CODEOWNERS` identifies security-sensitive files for code-owner review.

### GitHub settings that source cannot activate

The following must be configured and verified by a repository administrator:

1. **Settings → Code security → Private vulnerability reporting → Enable.**
   Verify a reporter can access the private reporting UI. The policy file
   and URL alone do not enable private reporting. The connected GitHub
   integration cannot change this setting.
2. **Settings → Rulesets / Branches:** require the single `CI / gate` check;
   enable code-owner review only when there is an eligible reviewer other than
   the PR author. The `CODEOWNERS` file alone cannot enforce approval.
3. **Settings → Environments → release:** configure required reviewers,
   then enable the `RELEASE_PUBLICATION_ENABLED=true` Actions variable if
   automated draft publication is desired.

Do not publish from an unapproved commit or manually skip signature checks.

## Source-address inventory and remediation

The **issue #55 complete source** was inspected with a strict RFC1918 IPv4
matcher. The initial source had **26 literal RFC1918 matches across 17 files**.
Some were genuine home-network values; others were generic Docker bridge
examples, deliberate private-address security-test fixtures, or incidental
SVG country-flag vector decimal path coordinates. Those categories must not
be confused: removing security regression tests would weaken SSRF protection,
while altering flag artwork would risk UI changes.

The issue #56 update removes real home-subnet values from 6 documentation/test
files and replaces them with RFC 5737 documentation-only TEST-NET addresses.
It also replaces private Docker-network addresses in 4 documentation files.
A separate controller SSRF test was changed to an unrelated synthetic private
address. **Do not copy TEST-NET addresses into production configuration:**
set production service endpoints and trusted proxy CIDRs through runtime
configuration appropriate for your network, not committed files.

The updated `tools/audit-source-package.py` now **fails on all RFC1918 dotted
IPv4 literals** outside individually allowlisted, intentionally synthetic
SSRF/security test fixtures. Third-party SVG flag artwork receives one narrow
false-positive exception for coordinate strings that resemble `10.x.x.x`
addresses; `172.16/12` and `192.168/16` still trigger the scan, even in SVG.
All output is file-oriented: the private value is never printed back in CI.

The audit runs in the always-reporting unified CI static job, the complete
release gate, and the publisher's pre-authentication archive check. The audit
supports both extracted source directories and entire ZIPs, including assets.

```bash
python3 tools/audit-source-package.py .
python3 scripts/check-issue56-release-docs.py
python3 scripts/test-issue56-release-docs.py
bash publish-clamav-webui.sh /path/to/full-source.zip --check
```

**Limits:** these checks inspect the current source snapshot, not every prior
Git commit, a developer's untracked ignored local files, or already published
binaries/images. GitHub code search against current indexed `main` is only a
supplement to the archive audit, and cannot prove Git history never contained
sensitive values. If an old commit or published asset leaked a private address,
remediation must also cover Git history, releases, builds and forks. A network
address is not an authentication secret, but exposing topology is undesirable.

### Regression checks

- A deliberately injected RFC1918 literal in README, Java test, or ZIP fails.
- The source audit redacts offending values from its messages.
- A documentation-only TEST-NET IP is permitted.
- The allowlisted webhook/SSRF security regression fixtures remain valid, but
  newly introduced private addresses in those files fail.
- SVG drawing coordinates are not mistaken for private infrastructure.
- The publisher refuses a tampered archive **before GitHub authentication**.

## Remaining acceptance checks

- Confirm `SECURITY.md` can be viewed from the repository's Security tab.
- **Enable GitHub private vulnerability reporting manually** and test the
  submission flow. This cannot be accomplished by committing Markdown.
- Require `CI / gate` and configure code-owner reviews where appropriate.
- Run GitHub Actions and the protected release flow to validate approvals.
