#!/usr/bin/env python3
"""Issue #52: offline, fail-closed structural verification of security CI gates.

This does not make claims about live CVE databases or scanner results. It checks
that GitHub will *run* the scanners, that failures reach the single required
`gate`, that exceptions are reviewed and expiring, and that all Actions use SHA
references. Exit nonzero on weakened policies.
"""
from __future__ import annotations

import argparse
from datetime import date
from pathlib import Path
import re
import sys
import tomllib


def require(predicate: bool, message: str) -> None:
    if not predicate:
        raise AssertionError(message)


def assert_blocking(step: dict, message: str, *, min_severity: bool = False) -> None:
    args = step.get('with', {})
    require(args.get('exit-code') == '1', f'{message}: exit-code must be 1')
    require(args.get('ignore-unfixed') == 'true', f'{message}: fixable CVE filter required')
    if min_severity:
        require(set(args.get('severity', '').split(',')) == {'HIGH', 'CRITICAL'},
                f'{message}: HIGH and CRITICAL required')
    require(step.get('continue-on-error') not in {'true', 'True'}, f'{message}: advisory mode forbidden')


def check(root: Path) -> None:
    try:
        import yaml
    except ImportError as ex:
        raise RuntimeError('PyYAML required (pip install PyYAML==6.0.2)') from ex
    ci_text = (root / '.github/workflows/ci.yml').read_text()
    rel_text = (root / '.github/workflows/release.yml').read_text()
    ci = yaml.load(ci_text, Loader=yaml.BaseLoader)
    release = yaml.load(rel_text, Loader=yaml.BaseLoader)
    require(isinstance(ci, dict) and isinstance(release, dict), 'Workflow YAML is invalid')
    events = ci['on']
    require('pull_request' in events and 'push' in events and 'schedule' in events,
            'Security checks must run on PRs, main and weekly schedule')
    require(events['push'].get('branches') == ['main'], 'Push security checks must target main')
    require(not any('paths' in str(events.get(event)) for event in ('push', 'pull_request')),
            'Event path filters would block required gate')
    require(ci['permissions'] == {'contents': 'read'}, 'CI root permissions must be least privilege')
    jobs = ci['jobs']
    required = {'secrets', 'workflow-lint', 'dependency-vulns', 'filesystem-vulns', 'docker-smoke', 'codeql'}
    require(required.issubset(jobs), f'Security jobs missing: {sorted(required - set(jobs))}')
    gate = jobs['gate']
    require('always()' in gate['if'], 'gate must always report')
    require(required.issubset(set(gate['needs'])), 'All scanners must be in gate.needs')
    require('scripts/ci-evaluate-gate.py' in ci_text, 'gate results must be evaluated')
    classifier = (root / 'scripts/ci-detect-changes.py').read_text()
    evaluator = (root / 'scripts/ci-evaluate-gate.py').read_text()
    for job in required:
        require(re.search(rf"'{re.escape(job)}':\s*'(?:static|security|docker|codeql)'", evaluator) is not None,
                f'{job} must be fail-closed in gate evaluator')
        require('timeout-minutes' in jobs[job], f'{job} must have a timeout')
    require("result['security'] = not result['docs_only']" in classifier,
            'Docs-only optimization must not prevent non-doc security scans')
    require('continue-on-error' not in ci_text, 'No advisory or ignored security checks')
    # All marketplace Action refs are verified 40-hex commits, including release.
    refs = re.findall(r'(?m)^\s*-?\s*uses:\s*([^\s#]+)', ci_text + '\n' + rel_text)
    require(refs and all(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?@[0-9a-f]{40}', ref)
                         for ref in refs), 'All third-party Actions must use full commit SHAs')
    # Gitleaks open source binary avoids the hosted Action's license restrictions.
    secrets = jobs['secrets']
    require('if' not in secrets, 'Secret scanner must run even on documentation-only PRs')
    runs = '\n'.join(str(step.get('run', '')) for step in secrets['steps'])
    require('go install github.com/zricethezav/gitleaks/v8@v8.30.1' in runs,
            'Secret scanner version must be pinned')
    require('gitleaks" dir --redact --exit-code 1' in runs,
            'Gitleaks source scan must be blocking and redact findings')
    require('gitleaks" git --redact --exit-code 1' in runs and '--log-opts=' in runs,
            'Gitleaks must also inspect newly introduced Git commits')
    require('scripts/issue52-gitleaks-fixture.sh' in runs,
            'CI must prove fake tokens are detected')
    fixture = (root / 'scripts/issue52-gitleaks-fixture.sh').read_text()
    require('ghp_' in fixture and 'exit-code 1' in fixture and 'report.json' in fixture and
            'status" -ne 1' in fixture and 'RuleID' in fixture,
            'Synthetic Gitleaks negative fixture must verify a real finding, not a scanner failure')
    # actionlint and hadolint errors block the gate.
    lint = jobs['workflow-lint']
    require('if' not in lint, 'Workflow lint must run for all PRs')
    require('go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.12' in ci_text,
            'actionlint must use a pinned version')
    require('"$(go env GOPATH)/bin/actionlint" -oneline' in ci_text,
            'actionlint must execute (not just install)')
    hadolint = [s for s in lint['steps'] if s.get('uses', '').startswith('hadolint/hadolint-action@')]
    require({s.get('with', {}).get('dockerfile') for s in hadolint} ==
            {'Dockerfile', 'Dockerfile.clamd', 'Dockerfile.clamd-uid'},
            'All 3 Dockerfiles must be linted')
    for step in hadolint:
        require(step['with'].get('no-fail') == 'false' and step['with'].get('failure-threshold') == 'error',
                'hadolint errors must block CI')
    # OSV scans both Maven and Go lockfiles recursively with no default suppressions.
    deps = jobs['dependency-vulns']
    require("needs.changes.outputs.security == 'true'" in deps['if'], 'Dependency scan must cover non-doc PRs')
    args = '\n'.join(str(st.get('run', '')) for st in deps['steps'])
    require('go install github.com/google/osv-scanner/v2/cmd/osv-scanner@v2.2.1' in args,
            'OSV-Scanner version must be pinned by module release')
    require('osv-scanner" scan source' in args,
            'OSV-Scanner must execute against source with a blocking exit code')
    for param in ('--config=osv-scanner.toml', '--recursive', '--format=json', '--output=artifacts/security/osv.json'):
        require(param in args, f'OSV-Scanner must include {param}')
    with (root / 'osv-scanner.toml').open('rb') as fh:
        config = tomllib.load(fh)
    for v in config.get('IgnoredVulns', []):
        require(re.fullmatch(r'(?:GHSA|CVE|GO|OSV)-[A-Za-z0-9-]+', v.get('id', '')) is not None,
                'OSV exception needs an explicit vulnerability ID')
        require(len(v.get('reason', '').strip()) >= 20, 'OSV exception must explain its threat model')
        until = v.get('effectiveUntil')
        require(isinstance(until, date) and until >= date.today(), 'OSV exception must have a future expiry date')
    require(not config.get('PackageOverrides'), 'Broad OSV package suppressions are not allowed')
    dependabot = yaml.load((root / '.github/dependabot.yml').read_text(), Loader=yaml.BaseLoader)
    ecosystems = {(u['package-ecosystem'], u['directory']) for u in dependabot['updates']}
    for key in {('maven', '/'), ('github-actions', '/'), ('docker', '/'), ('gomod', '/cli')}:
        require(key in ecosystems, f'Dependabot ecosystem missing: {key}')
    require(all(u['schedule']['interval'] == 'weekly' for u in dependabot['updates']),
            'Dependabot ecosystem scans must be weekly')
    # Trivy filesystem plus dual image policies: existing CRITICAL/unfixed and HIGH/CRITICAL/fixable.
    fs = jobs['filesystem-vulns']
    require("needs.changes.outputs.security == 'true'" in fs['if'], 'Filesystem scan must cover non-doc PRs')
    fssteps = [st for st in fs['steps'] if st.get('uses', '').startswith('aquasecurity/trivy-action@')]
    require(len(fssteps) == 1 and fssteps[0]['with']['scan-type'] == 'fs',
            'Trivy filesystem scan required')
    assert_blocking(fssteps[0], 'Trivy filesystem', min_severity=True)
    imgsteps = [st for st in jobs['docker-smoke']['steps'] if st.get('uses', '').startswith('aquasecurity/trivy-action@')]
    require(len(imgsteps) >= 2, 'Both image Trivy policies must run')
    require(any(s['with']['severity'] == 'CRITICAL' and s['with'].get('ignore-unfixed') == 'false' and
                s['with'].get('exit-code') == '1' for s in imgsteps),
            'Existing CRITICAL (including unfixed) gate must be retained')
    require(any(s['with']['scan-type'] == 'image' and s['with']['severity'] == 'HIGH,CRITICAL' and
                s['with'].get('ignore-unfixed') == 'true' and s['with'].get('exit-code') == '1' for s in imgsteps),
            'Image HIGH/CRITICAL fixable vulnerability gate required')
    # Matrix with three distinct languages and non-advisory CodeQL.
    cql = jobs['codeql']
    langs = {m['language']: m['mode'] for m in cql['strategy']['matrix']['include']}
    require(langs == {'java-kotlin': 'manual', 'javascript-typescript': 'none', 'go': 'manual'},
            'CodeQL must scan Java, JavaScript/TypeScript and Go')
    require(cql['permissions'] == {'contents': 'read', 'security-events': 'write'},
            'CodeQL upload permissions must be job scoped')
    require('queries: security-extended' in ci_text and 'languages: ${{ matrix.language }}' in ci_text,
            'CodeQL scanner must use the language matrix and extended queries')
    require('go build ./...' in ci_text and 'mvn -B -ntp -DskipTests package' in ci_text,
            'Both compiled languages need manual CodeQL builds')
    require('github/codeql-action/analyze@' in ci_text, 'CodeQL analysis upload required')
    # Release still scans again on tag and verifies GHCR digest before tags.
    tag_tests = release['jobs']['release-tests']['steps']
    release_image = [s for s in tag_tests if s.get('uses', '').startswith('aquasecurity/trivy-action@') and s.get('with', {}).get('scan-type') == 'image']
    release_fs = [s for s in tag_tests if s.get('uses', '').startswith('aquasecurity/trivy-action@') and s.get('with', {}).get('scan-type') == 'fs']
    require(any(s['with']['severity'] == 'HIGH,CRITICAL' and s['with']['exit-code'] == '1' for s in release_image),
            'Tag release must block HIGH/CRITICAL image CVEs')
    require(len(release_fs) >= 1, 'Tag release must rescan filesystem')
    assert_blocking(release_fs[0], 'Tag release Trivy filesystem', min_severity=True)
    require('scripts/issue51-verify-signature.sh' in rel_text and 'scripts/smoke-eicar.sh' in rel_text,
            'Signed GHCR digest verification and EICAR smoke must survive')
    print('PASS: issue #52 dependency, secret, lint, filesystem, image and three-language CodeQL gates all enforced')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        check(args.root)
    except (AssertionError, KeyError, TypeError, ValueError, OSError, RuntimeError) as exc:
        print('FAIL:', exc, file=sys.stderr)
        raise SystemExit(1)


if __name__ == '__main__':
    main()
