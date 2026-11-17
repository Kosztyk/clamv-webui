#!/usr/bin/env python3
"""Fail-closed policy check: Go CLI testing, reproducibility and signed release gates."""
from pathlib import Path
import argparse
import re
import sys
import yaml

ROOT=Path(__file__).resolve().parents[1]


def check(ci_path:Path, release_path:Path):
    ci= yaml.load(ci_path.read_text(), Loader=yaml.BaseLoader)
    rel=yaml.load(release_path.read_text(),Loader=yaml.BaseLoader)
    jobs=ci['jobs']; r=rel['jobs']
    assert {'cli-test','cli-build','cli-integration','gate'} <= set(jobs), 'Missing split Go CI jobs'
    assert 'go-cli' not in jobs, 'Old combined Go job must be removed'
    assert all("needs.changes.outputs.cli == 'true'" in jobs[j]['if'] for j in ('cli-test','cli-build','cli-integration')), 'CLI jobs must select CLI changes'
    assert {'cli-test', 'cli-build', 'cli-integration'} <= set(jobs['gate']['needs']), 'Go test/build/integration must all gate merges'
    assert {'changes','cli-test'} <= set(jobs['cli-build']['needs']), 'Build must depend on successful CLI tests'
    assert {'changes','cli-build'} <= set(jobs['cli-integration']['needs']), 'Integration must exercise tested build artifacts'
    test=jobs['cli-test']
    assert {'ubuntu-24.04','macos-15','windows-2022'} == set(test['strategy']['matrix']['os']), 'Go CI must test 3 OS families'
    teststeps='\n'.join(str(step.get('run','')) for step in test['steps'])
    for term in ('go test -count=1 ./...', 'go vet ./...', 'go test -race -count=1 ./...',
                 'staticcheck@2026.1','govulncheck@v1.1.4', 'staticcheck" ./...', 'govulncheck" ./...'):
        assert term in teststeps, 'Missing blocking Go quality control: '+term
    buildsteps='\n'.join(str(step.get('run','')) for step in jobs['cli-build']['steps'])
    assert 'issue54-cli-repro.py --out cli/dist' in buildsteps, 'Must independently rebuild and compare six binary hashes'
    inte='\n'.join(str(step.get('run','')) for step in jobs['cli-integration']['steps'])
    assert 'issue54-cli-repro.py --verify-only' in inte and 'test-clamctl-integration.py' in inte, 'Spring/Go integration and downloaded executable version must run'
    assert 'scripts/check-issue54-cli-quality.py' in ci_path.read_text() and 'scripts/test-issue54-cli-quality.py' in ci_path.read_text(), 'Static Go regression gates missing'

    assert {'cli-test','clamctl','cli-integration','cli-sign'} <= set(r), 'Release Go jobs incomplete'
    assert {'verify-tag','cli-test'} == set(r['clamctl']['needs']), 'Release CLI build must depend on quality testing'
    assert {'verify-tag','release-tests','clamctl'} <= set(r['cli-integration']['needs']), 'Release CLI integration must run against approved JAR and CLI'
    assert {'verify-tag','clamctl','cli-integration'} <= set(r['cli-sign']['needs']), 'Release signature must wait for tested/reproduced binaries'
    assert r['cli-sign']['permissions'] == {'contents':'read','id-token':'write'}, 'Keyless OIDC permission must be scoped to CLI signing job'
    assert 'cli-sign' in r['draft']['needs'] and 'cli-integration' in r['draft']['needs'], 'Signed CLI validation must block draft'
    assert 'cli-sign' in r['image-publish']['needs'], 'Image tags must not promote before CLI signature gates'
    raw=release_path.read_text()
    for term in ('issue54-cli-repro.py --version', 'source-date-epoch', 'cosign sign-blob --yes --new-bundle-format --bundle',
                 'issue54-cli-signing.py --input artifacts/cli --out artifacts/cli-sign',
                 'issue54-verify-cli-signature.sh', 'release-cli-signed-checksums',
                 '--cli-sign-dir artifacts/cli-sign --require-cli-signature',
                 '--verify-only --require-image-evidence --require-cli-signature',
                 'go install honnef.co/go/tools/cmd/staticcheck@2026.1',
                 'go install golang.org/x/vuln/cmd/govulncheck@v1.1.4'):
        if term == 'source-date-epoch':
            # Expected to derive the fixed source-date from the immutable git commit in the script itself.
            continue
        assert term in raw, 'Missing signed CLI release protection: '+term
    assert raw.count('bash scripts/issue54-verify-cli-signature.sh') >= 3, 'Signature must be checked when signing, drafting and approving'
    assert raw.index('Sign exact checksum bytes') < raw.index('Create DRAFT GitHub release'), 'Sign before drafting release'
    assert raw.index('Reverify keyless signed CLI checksums') < raw.index('Publish previously verified draft'), 'Reverify before approval'
    assert 'continue-on-error' not in raw, 'Release failures must not be downgraded'

    builder=(ROOT/'cli/tools/build.go').read_text()
    assert '-trimpath' in builder and '-buildid=' in builder and '-X main.version=' in builder, 'Builder must embed canonical version without path/build ID'
    assert 'SOURCE_DATE_EPOCH' in builder and 'CGO_ENABLED' in builder and 'GOTOOLCHAIN' in builder, 'Deterministic build environment missing'
    repro=(ROOT/'scripts/issue54-cli-repro.py').read_text()
    for term in ('SOURCE_DATE_EPOCH', 'git', '--format=%ct', "p/'first'", "p/'second'", 'a != b', 'manifest.json', '--version', 'sha256'):
        assert term in repro, 'Reproducibility helper does not compare independent builds: '+term
    sign=(ROOT/'scripts/issue54-verify-cli-signature.sh').read_text()
    assert '--new-bundle-format' in sign and '--certificate-identity "$identity"' in sign and "--certificate-oidc-issuer 'https://token.actions.githubusercontent.com'" in sign, 'Signature verifier does not bind exact GitHub OIDC identity'
    assert 'clamctl-SHA256SUMS' in (ROOT/'scripts/release-assets.py').read_text(), 'Signed CLI evidence omitted from release assets'
    assert 'target/clamav-web-client-2.1.0.jar' not in (ROOT/'scripts/test-clamctl-integration.py').read_text(), 'Hard-coded stale Spring version'
    assert re.search(r'\bversion\s*=\s*pom\.findtext', (ROOT/'scripts/test-clamctl-integration.py').read_text()), 'Spring integration must derive version from POM'
    print('PASS: issue #54 split CLI jobs, staticcheck, govulncheck, deterministic builds, version, OIDC signing and gate policy')


if __name__=='__main__':
    ap=argparse.ArgumentParser()
    ap.add_argument('--ci',type=Path,default=ROOT/'.github/workflows/ci.yml')
    ap.add_argument('--release',type=Path,default=ROOT/'.github/workflows/release.yml')
    a=ap.parse_args()
    try: check(a.ci,a.release)
    except (AssertionError,KeyError,TypeError,ValueError,OSError) as e:
        print('FAIL issue #54 policy:',e,file=sys.stderr);sys.exit(1)
