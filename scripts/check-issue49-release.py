#!/usr/bin/env python3
"""Static contract audit for the fail-closed, tag-triggered release workflow."""
from pathlib import Path
import argparse
import re
import sys

ROOT = Path(__file__).resolve().parents[1]


def check(path: Path) -> None:
    import yaml
    raw = path.read_text()
    wf = yaml.load(raw, Loader=yaml.BaseLoader)
    assert isinstance(wf, dict), 'Invalid release YAML'
    assert wf['on']['push']['tags'] == ['v*.*.*'], 'Tag push trigger missing'
    assert 'workflow_dispatch' in wf['on'], 'Dry-run dispatch missing'
    assert wf['permissions'] == {'contents': 'read'}, 'Root token must be read-only'
    jobs = wf['jobs']
    required = {'verify-tag', 'release-tests', 'db-migrations', 'cli-test', 'clamctl', 'cli-integration', 'cli-sign', 'image-publish', 'upgrade-path', 'draft', 'publish'}
    assert set(jobs) == required, 'Missing/extra release jobs'
    assert all('timeout-minutes' in job for job in jobs.values()), 'Jobs must have timeouts'
    assert jobs['verify-tag']['permissions'] == {'contents': 'read', 'actions': 'read'}, 'Verify needs read-only Actions API permission'
    assert 'release-verify-tag.py' in raw and 'github.ref_name' in raw, 'Release provenance check missing'
    assert 'verify-tag.outputs.commit' in raw, 'Builds must pin exactly the checked commit'
    for name in ('release-tests', 'db-migrations', 'cli-test', 'clamctl', 'cli-integration', 'cli-sign', 'image-publish', 'upgrade-path'):
        assert 'verify-tag' in jobs[name]['needs'], 'Validation must precede ' + name
    assert {'postgresql', 'mysql', 'mariadb'} == set(jobs['db-migrations']['strategy']['matrix']['vendor']), 'DB integration matrix incomplete'
    assert 'bash scripts/release-gate.sh --docker-build' in raw and 'CLAMAV_REQUIRE_BROWSER' in raw, 'Full release gate/browser tests required'
    assert '-Pdb-integration' in raw and 'Phase2DatabaseIT' in raw, 'Testcontainers integration missing'
    assert 'scripts/issue37-smoke.sh' in raw and 'scripts/smoke-eicar.sh' in raw, 'Actual Docker smoke missing'
    assert 'aquasecurity/trivy-action@' in raw and 'severity: CRITICAL' in raw and "exit-code: '1'" in raw, 'Trivy CRITICAL fail gate missing'
    assert 'issue54-cli-repro.py' in raw and 'go test -race -count=1' in raw, 'All CLI binaries must be reproducibly built and tested'
    assert {'verify-tag','release-tests','db-migrations','cli-test','clamctl','cli-integration','cli-sign','image-publish','upgrade-path'} == set(jobs['draft']['needs']), 'Draft must wait for every gate and signed image verification'
    assert jobs['image-publish']['permissions'] == {'contents': 'read', 'packages': 'write', 'id-token': 'write'}, 'Only image publication may write packages or request Sigstore OIDC'
    assert "github.event_name == 'push'" in jobs['image-publish']['if'], 'Manual dispatch must never push images'
    assert "github.event_name == 'push'" in jobs['draft']['if'], 'Manual dry run must not publish a draft'
    assert jobs['draft']['permissions'] == {'contents': 'write', 'packages': 'read'}, 'Draft requires scoped registry read permission'
    assert 'release-assets.py' in raw and 'sha256sum -c SHA256SUMS' in raw, 'Release assets not verified'
    assert 'gh release create' in raw and '--draft' in raw and '--generate-notes' in raw and '--verify-tag' in raw, 'Must create checked draft with generated notes'
    assert raw.index('Block release on known CRITICAL') < raw.index('Create DRAFT GitHub release'), 'Trivy must run before draft'
    assert jobs['publish']['needs'] == ['verify-tag', 'draft'], 'Publication must depend on the draft'
    assert jobs['publish']['environment'] == 'release', 'Publication requires protected environment'
    assert "vars.RELEASE_PUBLICATION_ENABLED == 'true'" in jobs['publish']['if'], 'Publication must default disabled'
    assert jobs['publish']['permissions'] == {'contents': 'write', 'packages': 'read'}, 'Publication write permission must be job-scoped'
    assert '--draft=false' in raw, 'Publication must promote the verified draft only'
    assert 'scripts/issue51-verify-signature.sh' in raw, 'Published image must be cryptographically verified before drafting and approval'
    assert '--remote-ref-only' in raw and '--expected-sha' in raw, 'Tag must be rechecked before both writes'
    assert 'continue-on-error' not in raw and '--static-only' not in raw, 'Release may not suppress failures'
    assert 'beta-prerelease.yml' not in [p.name for p in path.parent.glob('*.yml')], 'Bypass prerelease publisher must be removed'
    assert all(re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)?@[a-f0-9]{40}', value) for value in re.findall(r'(?m)^\s*-?\s*uses:\s*(\S+)', raw)), 'Actions must be pinned to commit SHAs'
    assert 'target/surefire-reports/**' in raw and 'artifacts/playwright/**' in raw and 'compose.log' in raw, 'Failure diagnostics missing'
    assert 'if: ${{ always() }}' in raw, 'Failure diagnostics must always upload'
    assert 'concurrency:' in raw and 'cancel-in-progress: false' in raw, 'Concurrent releases must not cancel each other'
    print('PASS: issue #49 release workflow: provenance, full gates, six CLI binaries, checksums, draft, opt-in approval')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--workflow', type=Path, default=ROOT / '.github/workflows/release.yml')
    args = parser.parse_args()
    try:
        check(args.workflow)
    except (AssertionError, KeyError, TypeError, ValueError, ImportError) as e:
        print('FAIL: issue #49 release contract:', e, file=sys.stderr)
        raise SystemExit(1)
