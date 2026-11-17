#!/usr/bin/env python3
"""Regression and adversarial tests for issue #48 CI change selection and gate."""
from __future__ import annotations
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]

def load(name: str, file: str):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts'/file)
    module = importlib.util.module_from_spec(spec)
    assert spec and spec.loader
    spec.loader.exec_module(module)
    return module

selector = load('ci_selector', 'ci-detect-changes.py')
gate = load('ci_gate', 'ci-evaluate-gate.py')


def sample_needs(flags, failure=None):
    actual = {'changes': {'result': 'success', 'outputs': {k: str(v).lower() for k,v in flags.items()}}}
    for name, scope in gate.JOB_TO_FLAG.items():
        actual[name] = {'result': 'success' if flags[scope] else 'skipped'}
    if failure:
        actual[failure[0]]['result'] = failure[1]
    return actual


def expect_failure(needs, label):
    errors = gate.evaluate(needs)
    assert errors, f'{label}: expected gate failure'
    print('PASS:', label)


def main():
    docs = selector.classify(['README.md','docs/installation.md'])
    assert docs['docs_only'] and docs['static'] and not any(docs[x] for x in ('java','db','docker','cli','browser','codeql'))
    assert not gate.evaluate(sample_needs(docs))
    print('PASS: docs-only PR passes quickly while all expensive checks are filtered')

    for control in ('.github/workflows/ci.yml', '.github/workflows/release.yml',
                    'scripts/ci-detect-changes.py', 'scripts/ci-evaluate-gate.py'):
        selected = selector.classify([control])
        assert all(selected[scope] for scope in selector.SCOPES), (
            control + ': CI control-plane modification did not select all gates')
        assert selected['security'] and not selected['docs_only']
        assert not gate.evaluate(sample_needs(selected))
    print('PASS: workflow and gate-selector changes force every security/build/test scope')

    # Python coverage-policy changes must exercise Maven's measured coverage
    # comparison, not just the lightweight static and upgrade checks.
    for control in sorted(selector.JAVA_QUALITY_CONTROLS):
        selected = selector.classify([control])
        assert all(selected[scope] for scope in ('static', 'java', 'codeql', 'upgrade', 'security')), (
            control + ': coverage quality gate would be skipped')
        assert not selected['docs_only']
        assert not any(selected[scope] for scope in ('db', 'cli', 'docker', 'browser', 'api')), (
            control + ': unrelated CI scopes should remain filtered')
        assert not gate.evaluate(sample_needs(selected))
        expect_failure(sample_needs(selected, ('java-quality', 'skipped')),
                       control + ': skipped Java quality job blocks merge')
    combined = selector.classify(sorted(selector.JAVA_QUALITY_CONTROLS))
    assert combined['java'] and combined['codeql'] and combined['upgrade']
    assert not gate.evaluate(sample_needs(combined))
    unrelated = selector.classify(['scripts/issue58-resolve-predecessor.py'])
    assert unrelated['upgrade'] and not unrelated['java'], (
        'Upgrade-predecessor changes must not unnecessarily select Java quality')
    print('PASS: coverage policy/check/tests trigger Maven java-quality without unrelated jobs')

    # Exercise the CLI/output contract consumed by .github/workflows/ci.yml,
    # not just the internal Python classification function.
    with tempfile.TemporaryDirectory(prefix='issue48-quality-output-') as directory:
        output = Path(directory) / 'github-output'
        env = dict(os.environ, GITHUB_OUTPUT=str(output))
        subprocess.run([sys.executable, str(ROOT / 'scripts/ci-detect-changes.py'),
                        '--files', 'scripts/issue58-coverage-ratchet.py'],
                       check=True, capture_output=True, text=True, env=env)
        signals = dict(line.split('=', 1) for line in output.read_text().splitlines())
        assert signals['java'] == 'true' and signals['codeql'] == 'true'
        assert signals['upgrade'] == 'true' and signals['db'] == 'false'
    print('PASS: GitHub Actions output exposes java=true for coverage-policy-only changes')

    migration = selector.classify(['src/main/resources/db/migration/postgresql/V8__ldap_directory_id.sql'])
    assert all(migration[x] for x in ('java','db','docker','codeql'))
    assert not gate.evaluate(sample_needs(migration))
    print('PASS: DB migration selects Java, three-vendor matrix, container and CodeQL')

    cli = selector.classify(['cli/internal/cli/app.go'])
    assert cli['cli'] and not cli['java'] and not cli['db'] and not cli['docker']
    assert not gate.evaluate(sample_needs(cli))
    print('PASS: CLI change runs Go job only')

    expect_failure(sample_needs(migration, ('db-migrations','skipped')), 'Unexpectedly skipped required database job blocks merge')
    expect_failure(sample_needs(migration, ('java-tests','failure')), 'Failed Java tests block merge')
    expect_failure(sample_needs(docs, ('docker-smoke','failure')), 'Failures cannot be ignored even if job normally filtered')
    expect_failure(sample_needs(migration, ('codeql','cancelled')), 'Cancelled security job blocks merge')
    absent = sample_needs(docs); del absent['codeql']
    expect_failure(absent, 'Missing needed job blocks merge')
    missing = sample_needs(docs); missing['changes']['result'] = 'failure'
    expect_failure(missing, 'Change-detection failure blocks merge')
    unknown = sample_needs(docs); del unknown['changes']['outputs']['cli']
    expect_failure(unknown, 'Unknown flag blocks merge')

    with tempfile.TemporaryDirectory(prefix='issue48-git-') as directory:
        repo = Path(directory)
        def git(*args):
            return subprocess.check_output(['git', '-C', str(repo), *args], stderr=subprocess.STDOUT, text=True).strip()
        git('init','-q')
        git('config','user.email','ci-test@invalid.test')
        git('config','user.name','CI Test')
        (repo/'README.md').write_text('before\n')
        git('add','.')
        git('commit','-qm','baseline')
        before = git('rev-parse','HEAD')
        (repo/'README.md').write_text('after\n')
        git('commit','-qam','docs')
        old = Path.cwd()
        try:
            os.chdir(repo)
            os.environ.update(GITHUB_EVENT_NAME='pull_request', CI_PR_BASE_SHA=before)
            assert selector.changed_files() == ['README.md']
            # A migration renamed to documentation must still select the DB matrix.
            migration = repo/'src/main/resources/db/migration/postgresql/V9__test.sql'
            migration.parent.mkdir(parents=True)
            migration.write_text('SELECT 1;\n')
            git('add','.')
            git('commit','-qm','add fixture migration')
            previous = git('rev-parse','HEAD')
            (repo/'docs').mkdir()
            git('mv', str(migration.relative_to(repo)), 'docs/renamed-migration.md')
            git('commit','-qm','rename migration to documentation')
            os.environ['CI_PR_BASE_SHA'] = previous
            paths = selector.changed_files()
            assert 'docs/renamed-migration.md' in paths
            assert 'src/main/resources/db/migration/postgresql/V9__test.sql' in paths
            assert selector.classify(paths)['db'], 'Removed migration must activate DB matrix'
            os.environ['CI_PR_BASE_SHA'] = '1'*40
            try:
                selector.changed_files()
            except subprocess.CalledProcessError:
                pass
            else:
                raise AssertionError('Diff against missing base silently succeeded')
        finally:
            os.chdir(old)
            for key in ('GITHUB_EVENT_NAME','CI_PR_BASE_SHA'):
                os.environ.pop(key,None)
    print('PASS: git diff uses an actual base and fails closed if missing')

    try:
        selector.classify([])
    except Exception as ex:
        raise AssertionError('Manual CI must select all checks') from ex
    assert all(selector.classify([])[k] for k in gate.JOB_TO_FLAG.values())
    print('PASS: manually triggered checks run all jobs')
    print('Issue #48 regression scenarios passed')

if __name__ == '__main__': main()
