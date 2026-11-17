#!/usr/bin/env python3
"""Classify CI changes without privileged PR API access or mutable actions.

Pull requests compare the checked-out merge commit to its recorded base commit;
main pushes compare event.before to HEAD. Failure to find the base is fatal,
never silently interpreted as a documentation-only change.
"""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import subprocess

SCOPES = ('static', 'java', 'db', 'cli', 'docker', 'browser', 'codeql', 'api', 'upgrade')

# Coverage-policy logic and its offline regression/structural tests control the
# Maven java-quality gate, even though they are not Java source files.
JAVA_QUALITY_CONTROLS = frozenset({
    'scripts/issue58-coverage-ratchet.py',
    'scripts/check-issue58-hardening.py',
    'scripts/test-issue58-hardening.py',
})


def is_documentation(path: str) -> bool:
    return (path.startswith('docs/') or path.endswith('.md') or path.endswith('.rst')
            or path in {'LICENSE', 'CHANGELOG', 'NOTICE'})


def classify(paths: list[str]) -> dict[str, bool]:
    result = {name: False for name in SCOPES}
    result['static'] = True  # CI contract verification runs on every PR.
    result['docs_only'] = bool(paths) and all(is_documentation(p) for p in paths)
    result['security'] = not result['docs_only'] # expensive dependency/FS scans; weekly always scans
    # Any change to the orchestrator/gate itself must exercise the ENTIRE pipeline.
    # Otherwise a malicious or accidental edit to a security job could make it
    # skip CodeQL/Docker/upgrade testing, yet still get a green required gate.
    critical_controls = {'.github/workflows/ci.yml', '.github/workflows/release.yml',
                         'scripts/ci-detect-changes.py', 'scripts/ci-evaluate-gate.py'}
    for p in paths:
        if p in critical_controls:
            for scope in SCOPES:
                result[scope] = True
            result['security'] = True
        if is_documentation(p):
            continue
        java = (p == 'pom.xml' or p.startswith('.mvn/') or p in {'mvnw', 'mvnw.cmd'}
                or p.startswith('src/main/java/') or p.startswith(('src/test/java/', 'src/test/resources/'))
                or p.startswith('src/main/resources/')
                or p.startswith('scripts/issue53-') or p.startswith('scripts/check-issue53-')
                or p.startswith('scripts/test-issue53-')
                or p in JAVA_QUALITY_CONTROLS)
        api = (p == 'pom.xml' or p == '.spectral.yaml'
               or p == '.github/workflows/ci.yml' or p == '.github/workflows/release.yml'
               or p.startswith('src/main/resources/openapi/')
               or p.startswith('src/main/java/info/trizub/clamav/webclient/api/v1/')
               or p.startswith('src/test/java/info/trizub/clamav/webclient/api/v1/')
               or p.startswith('cli/internal/cli/')
               or p.startswith(('scripts/issue55-', 'scripts/check-issue55-', 'scripts/test-issue55-')))
        if java:
            result['java'] = result['codeql'] = result['upgrade'] = True
        if api:
            result['api'] = True
        if p.startswith('src/main/resources/openapi/') or p.startswith('src/main/java/info/trizub/clamav/webclient/api/v1/'):
            result['cli'] = True  # live Go CLI compatibility on contract/controller changes

        if p == 'pom.xml' or p.startswith(('src/main/resources/db/migration/',
                'src/test/java/info/trizub/clamav/webclient/db/',
                'src/main/java/info/trizub/clamav/webclient/db/',
                'src/test/resources/upgrade-fixtures/', 'scripts/issue57-', 'scripts/check-issue57-', 'scripts/test-issue57-')):
            result['db'] = result['upgrade'] = True
        if p.startswith('cli/'):
            result['cli'] = result['codeql'] = True
        if p in {'pom.xml', '.github/workflows/ci.yml', '.github/workflows/release.yml'}:
            result['upgrade'] = True

            result['cli'] = True  # CLI version, build or workflow controls changed
        if p == 'pom.xml' or p.startswith(('src/main/', 'src/test/')):
            result['docker'] = True
        if p.startswith(('scripts/issue57-', 'scripts/check-issue57-', 'scripts/test-issue57-')) or p == 'config/upgrade-baseline.json' or p.startswith(('scripts/issue58-', 'scripts/check-issue58-', 'scripts/test-issue58-')):
            result['upgrade'] = True
        if (p.startswith(('Dockerfile', 'docker-compose', 'examples/compose/'))
                or p in {'.dockerignore', '.env.example', 'docker-entrypoint.sh'}):
            result['docker'] = True
        if p.startswith(('src/main/resources/static/', 'src/main/resources/templates/')):
            result['browser'] = True
        if p.startswith('scripts/'):
            lower = p.lower()
            if 'clamctl' in lower or 'mcp' in lower or 'issue54' in lower:
                result['cli'] = True
            if any(t in lower for t in ('browser', 'playwright', 'configuration_center', 'check-i18n')):
                result['browser'] = True
            if any(t in lower for t in ('smoke', 'docker', 'issue37', 'issue36')):
                result['docker'] = True
        if p == '.github/dependabot.yml':
            result['static'] = True
        known = (java or p.startswith(('cli/', 'scripts/', '.github/workflows/',
                 'src/main/', 'src/test/', 'Dockerfile', 'docker-compose',
                 'examples/compose/')) or p in {'docker-entrypoint.sh',
                 '.dockerignore', '.gitignore', '.env.example', '.github/dependabot.yml'})
        if not known:
            # Unknown build/config source should never silently skip its tests.
            for name in SCOPES:
                result[name] = True
    # On an unknown/empty diff, run heavy checks rather than incorrectly skipping.
    if not paths:
        for name in SCOPES:
            result[name] = True
        result['docs_only'] = False
        result['security'] = True
    return result


def changed_files() -> list[str]:
    event = os.environ.get('GITHUB_EVENT_NAME', '')
    if event == 'pull_request':
        base = os.environ.get('CI_PR_BASE_SHA', '').strip()
    elif event == 'push':
        base = os.environ.get('CI_PUSH_BEFORE_SHA', '').strip()
        if base == '0' * 40:
            base = ''
    else:
        return []  # manual dispatch: full CI
    if not base or len(base) != 40 or not all(ch in '0123456789abcdefABCDEF' for ch in base):
        raise ValueError(f'Cannot establish Git base for event {event}; refusing to skip any job')
    p = subprocess.run(['git', 'diff', '--no-renames', '--name-only', '-z', base, 'HEAD'],
                       capture_output=True, check=True)
    return sorted(set(x.decode('utf-8', 'surrogateescape') for x in p.stdout.split(b'\0') if x))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--files', nargs='*', help='Explicit file list for local classification tests')
    args = parser.parse_args()
    paths = args.files if args.files is not None else changed_files()
    scopes = classify(paths)
    print(json.dumps({'files': paths, 'scopes': scopes}, indent=2))
    output = os.environ.get('GITHUB_OUTPUT')
    if output:
        with Path(output).open('a', encoding='utf-8') as fh:
            for key, enabled in scopes.items():
                fh.write(f'{key}={str(enabled).lower()}\n')


if __name__ == '__main__':
    main()
