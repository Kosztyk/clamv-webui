#!/usr/bin/env python3
"""Issue #56: release documentation, security ownership and CI privacy audit wiring."""
from pathlib import Path
import argparse
import re
import sys


def verify(root):
    checks = {
        'Release runbook provides version bump and tag': (
            'RELEASE.md', ('pom.xml', 'git tag -a', 'git push origin', 'main-push', 'gate')),
        'Release runbook documents image evidence and rollback': (
            'RELEASE.md', ('Sigstore', 'GHCR', 'Flyway', 'rollback', 'RELEASE_PUBLICATION_ENABLED')),
        'Release runbook documents approvers and draft': (
            'RELEASE.md', ('release', 'required reviewer', 'draft', 'workflow_dispatch')),
        'Changelog is Keep a Changelog style': (
            'CHANGELOG.md', ('Keep a Changelog', 'Unreleased', 'Security', 'Fixed', 'Changed')),
        'PR template covers API, migrations and security': (
            '.github/pull_request_template.md', ('Flyway', 'OpenAPI', 'CI', 'security', 'CHANGELOG')),
        'Security policy uses private reporting': (
            'SECURITY.md', ('advisories/new', 'Private vulnerability reporting', 'Supported releases', 'privately')),
        'Codeowners cover the critical paths': (
            '.github/CODEOWNERS', ('@Kosztyk', 'SecurityConfig.java', '/identity/', '/db/migration/', '/.github/workflows/', '/Dockerfile')),
        'GitHub categorized release notes': (
            '.github/release.yml', ('changelog:', 'categories:', 'Security', 'Fixes')),
        'Root README links release/security': (
            'README.md', ('RELEASE.md', 'CHANGELOG.md', 'SECURITY.md')),
        'Privacy scanner is active on PRs': (
            '.github/workflows/ci.yml', ('tools/audit-source-package.py', 'scripts/check-issue56-release-docs.py')),
        'Privacy scanner is active before tags': (
            'scripts/release-gate.sh', ('tools/audit-source-package.py', 'scripts/check-issue56-release-docs.py')),
        'Privacy scan handles source and ZIP': (
            'tools/audit-source-package.py', ('private_ipv4_evidence', 'private_evidence', 'sources(', 'zipfile.ZipFile')),
    }
    bad=[]
    for label,(rel,tokens) in checks.items():
        path=root/rel
        if not path.is_file():bad.append((label, 'file missing'));continue
        s=path.read_text(encoding='utf-8')
        if not all(token in s for token in tokens):bad.append((label,'required procedure/policy absent'))
    if bad:
        for label,reason in bad:print(f'FAIL {label}: {reason}',file=sys.stderr)
        return 1
    print(f'PASS: Issue #56 release/security documentation and CI wiring ({len(checks)} checks)')
    return 0


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--root',type=Path,default=Path(__file__).resolve().parents[1])
    args=p.parse_args()
    return verify(args.root.resolve())

if __name__=='__main__':sys.exit(main())
