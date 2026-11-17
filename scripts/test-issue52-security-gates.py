#!/usr/bin/env python3
"""Issue #52 mutation tests for required security scanners and gate outcomes."""
from __future__ import annotations
import importlib.util
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source_checker = ROOT / 'scripts/check-issue52-security-gates.py'


def load(name: str, source: Path):
    spec = importlib.util.spec_from_file_location(name, source)
    mod = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(mod)
    return mod


def main():
    selector = load('issue52_selector', ROOT / 'scripts/ci-detect-changes.py')
    gate = load('issue52_gate', ROOT / 'scripts/ci-evaluate-gate.py')
    docs = selector.classify(['README.md', 'docs/security/README.md'])
    code = selector.classify(['cli/main.go'])
    assert docs['security'] is False and docs['codeql'] is False
    assert code['security'] is True and code['codeql'] is True

    def scenario(flags, overrides=None):
        needed = {'changes': {'result': 'success', 'outputs': {k: str(v).lower() for k, v in flags.items()}}}
        for name, signal in gate.JOB_TO_FLAG.items():
            needed[name] = {'result': 'success' if flags[signal] else 'skipped'}
        for name, outcome in (overrides or {}).items():
            needed[name]['result'] = outcome
        return needed

    assert not gate.evaluate(scenario(docs))
    assert not gate.evaluate(scenario(code))
    for name in ('secrets', 'workflow-lint', 'dependency-vulns', 'filesystem-vulns', 'codeql', 'docker-smoke'):
        flags = code.copy()
        if name == 'docker-smoke':
            flags['docker'] = True
        for outcome in ('failure', 'cancelled', 'skipped'):
            assert gate.evaluate(scenario(flags, {name: outcome})), f'{name}={outcome} was incorrectly allowed'
    for name in ('dependency-vulns', 'filesystem-vulns'):
        assert gate.evaluate(scenario(docs, {name: 'failure'})), f'{name} failure was ignored on docs-only PR'
    print('PASS: required security jobs cannot fail, cancel, disappear, or be unexpectedly skipped')

    with tempfile.TemporaryDirectory(prefix='issue52-policy-') as tmp:
        base = Path(tmp)
        for rel in ('.github/workflows/ci.yml', '.github/workflows/release.yml', '.github/dependabot.yml',
                    'osv-scanner.toml', 'scripts/ci-detect-changes.py', 'scripts/ci-evaluate-gate.py',
                    'scripts/issue52-gitleaks-fixture.sh'):
            target = base / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / rel, target)
        ci = base / '.github/workflows/ci.yml'
        dependabot = base / '.github/dependabot.yml'
        osv = base / 'osv-scanner.toml'
        original = ci.read_text()

        def check(expected: bool, message: str):
            r = subprocess.run(['python3', str(source_checker), '--root', str(base)],
                               capture_output=True, text=True)
            assert (r.returncode == 0) is expected, f'{message}: {r.stdout}\n{r.stderr}'
            print('PASS:', message)

        check(True, 'Clean baseline security policy')
        mutations = [
            ("exit-code: '1'", "exit-code: '0'", 'Nonblocking vulnerability scan rejected'),
            ('severity: HIGH,CRITICAL', 'severity: CRITICAL', 'Critical-only scan rejected'),
            ('languages: ${{ matrix.language }}', 'languages: java-kotlin', 'Missing JS/Go analysis rejected'),
            ('go install github.com/zricethezav/gitleaks/v8@v8.30.1', 'echo gitleaks disabled', 'Removed secrets scanner rejected'),
            ('go install github.com/rhysd/actionlint/cmd/actionlint@v1.7.12', 'echo actionlint skipped', 'Removed workflow linter rejected'),
            ('hadolint/hadolint-action@2332a7b74a6de0dda2e2221d575162eba76ba5e5', 'hadolint/hadolint-action@v3.3.0', 'Unpinned Action rejected'),
            ("- language: javascript-typescript", '- language: ignored-language', 'Missing JS matrix member rejected'),
            ('osv-scanner" scan source', 'echo ignored dependency vulnerabilities', 'Dependency scanner execution removed'),
        ]
        for old, new, message in mutations:
            assert old in original, f'Unrecognized test token {old}'
            ci.write_text(original.replace(old, new, 1))
            check(False, message)
        ci.write_text(original)
        old_deps = dependabot.read_text()
        dependabot.write_text(old_deps.replace('directory: "/cli"', 'directory: "/broken"'))
        check(False, 'Missing cli/gomod Dependabot updates rejected')
        dependabot.write_text(old_deps)
        osv.write_text('[[IgnoredVulns]]\nid = "CVE-2026-12345"\nreason = "ignore"\n')
        check(False, 'Unreviewed unexpired OSV suppression rejected')
        osv.write_text('[[IgnoredVulns]]\nid = "CVE-2026-12345"\nreason = "Threat model approved for this package only"\neffectiveUntil = 2020-01-01\n')
        check(False, 'Expired OSV suppression rejected')
        osv.write_text((ROOT / 'osv-scanner.toml').read_text())
        check(True, 'Restored security policy passes')
    print('Issue #52 gate and adversarial policy tests passed')


if __name__ == '__main__':
    main()
