#!/usr/bin/env python3
"""Issue #48 structural validation of the always-reporting required CI gate."""
from __future__ import annotations
import argparse
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
JOBS = {'changes', 'static', 'java-tests', 'java-quality', 'db-migrations', 'upgrade-path', 'cli-test', 'cli-build', 'cli-integration',
        'docker-smoke', 'browser', 'codeql', 'api-contract', 'secrets', 'workflow-lint',
        'dependency-vulns', 'filesystem-vulns', 'gate'}


def verify(path: Path) -> None:
    try:
        import yaml
    except ImportError as ex:
        raise RuntimeError('Install PyYAML to validate the GitHub workflow') from ex
    contents = path.read_text()
    workflow = yaml.load(contents, Loader=yaml.BaseLoader)
    assert isinstance(workflow, dict), 'Invalid YAML document'
    events = workflow['on']
    assert 'pull_request' in events and 'push' in events, 'Always trigger on all PRs and main push'
    assert 'schedule' in events, 'Weekly scans must run even without code changes'
    for event in ('pull_request', 'push'):
        assert 'paths:' not in str(events[event]) and 'paths-ignore:' not in str(events[event]), 'No event-level path filters'
    assert events['push']['branches'] == ['main'], 'Main branch push must trigger'
    assert workflow['permissions'] == {'contents': 'read'}, 'Root permissions must be least privilege'
    assert workflow['concurrency']['group'] == 'ci-${{ github.ref }}', 'Stable ref concurrency group'
    assert workflow['concurrency']['cancel-in-progress'] == 'true', 'Superseded runs must be cancelled'
    jobs = workflow['jobs']
    assert set(jobs) == JOBS, f'Expected named jobs {sorted(JOBS)}, got {sorted(jobs)}'
    assert all('timeout-minutes' in job for job in jobs.values()), 'Every job must have timeout'
    assert jobs['gate']['name'] == 'gate' and 'always()' in jobs['gate']['if'], 'Required gate is always-reporting'
    needs = jobs['gate']['needs']
    assert set(needs) == JOBS - {'gate'}, 'Required gate depends on all jobs'
    assert jobs['changes']['outputs'] and all(x in jobs['changes']['outputs'] for x in ('db','java','cli','docker','browser','codeql','security','docs_only','api','upgrade')), 'All conditional signals exported'
    for job, output in [('java-tests','java'),('java-quality','java'),('db-migrations','db'),('upgrade-path','upgrade'),('cli-test','cli'),('cli-build','cli'),('cli-integration','cli'),('docker-smoke','docker'),('browser','browser'),('codeql','codeql'),('api-contract','api'),('dependency-vulns','security'),('filesystem-vulns','security')]:
        assert "needs.changes.outputs." + output in jobs[job]['if'], f'{job} selectively runs on {output}'
    assert set(jobs['db-migrations']['strategy']['matrix']['vendor']) == {'postgresql','mysql','mariadb'}, 'All database vendors tested'
    assert jobs['db-migrations']['strategy']['fail-fast'] == 'false', 'Do not cancel other vendors on first failure'
    assert "CI_NEEDS_JSON" in contents and 'scripts/ci-evaluate-gate.py' in contents, 'Gate must inspect all results'
    assert 'scripts/ci-detect-changes.py' in contents and 'fetch-depth: 0' in contents, 'Reliable diff classifier'
    assert 'cache: maven' in contents and 'cache: true' in contents and 'go-version-file: cli/go.mod' in contents, 'Maven and Go dependency caches configured'
    assert 'scripts/smoke-eicar.sh' in contents and 'scripts/issue37-smoke.sh' in contents, 'Real Docker smoke tests retained'
    assert re.search(r'(?m)^\s*exit-code:\s*[\'\"]?1[\'\"]?\s*$', contents), 'Critical CVEs block CI'
    assert 'security-events: write' in contents, 'CodeQL gate is retained'
    assert {row['language'] for row in jobs['codeql']['strategy']['matrix']['include']} == {'java-kotlin', 'javascript-typescript', 'go'}, 'CodeQL language matrix must cover Java, JavaScript and Go'
    assert 'languages: ${{ matrix.language }}' in contents, 'CodeQL must analyze all matrix languages'
    for asset in ('target/surefire-reports/', 'target/failsafe-reports/', 'artifacts/playwright/', 'artifacts/container-logs/'):
        assert asset in contents, f'Missing artifact upload: {asset}'
    assert 'if-no-files-found:' in contents and 'if: ${{ always() }}' in contents, 'Failure artifacts always uploaded'
    assert 'continue-on-error' not in contents, 'Blocking checks cannot be downgraded to advisory'
    refs = re.findall(r'(?m)^\s*-?\s*uses:\s*(\S+)', contents)
    assert refs and all(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?@[a-f0-9]{40}', x) for x in refs), 'GitHub Actions SHA-pinned'
    assert workflow['jobs']['codeql']['permissions'] == {'contents':'read','security-events':'write'}, 'CodeQL permissions scoped to one job'
    print(f'PASS: unified workflow, {len(JOBS)} stable job IDs, conditional matrix, fail-closed gate and artifact collection')


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--workflow', type=Path, default=ROOT/'.github/workflows/ci.yml')
    args = ap.parse_args()
    try:
        verify(args.workflow)
    except (AssertionError, KeyError, TypeError, ValueError, RuntimeError) as ex:
        print('FAIL:', str(ex), file=sys.stderr)
        sys.exit(1)

if __name__ == '__main__':
    main()
