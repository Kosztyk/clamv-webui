#!/usr/bin/env python3
"""Fail closed if previous-release data upgrade tests can be bypassed."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import re
import sys
import yaml

ROOT=Path(__file__).resolve().parents[1]
REQUIRED_SQL=['INSERT INTO app_users','INSERT INTO app_user_roles','INSERT INTO clamd_endpoints',
              'INSERT INTO scan_jobs','INSERT INTO api_credentials','INSERT INTO auth_provider_settings',
              '__TOKEN_HASH__','__LDAP_CIPHER__','__OIDC_CIPHER__']


def check(root: Path) -> None:
    cfg=json.loads((root/'config/upgrade-baseline.json').read_text())
    assert re.fullmatch(r'[0-9a-f]{40}',cfg['fixture_source_commit']), 'Historical test fixture provenance must be pinned'
    assert cfg['fixture_source_release']=='v2.1beta1', 'Frozen seed fixture provenance changed'
    assert 'release_tag' not in cfg and 'commit_sha' not in cfg, 'Live predecessor cannot remain hard-coded'
    fixture=root/cfg['fixture']
    assert fixture.is_file() and 'upgrade-fixtures/' in str(fixture), 'Versioned predecessor SQL dump missing'
    frozen=root/'src/test/resources/upgrade-fixtures/v2.1beta1/h2-schema'
    for name in ('V1__initial_schema.sql','V3__external_identities.sql','V4__authentication_settings.sql'):
        assert (frozen/name).is_file() and (frozen/name).stat().st_size > 300, 'Missing original predecessor-era H2 Flyway schema snapshot: '+name
    fast_test=root/'src/test/java/info/trizub/clamav/webclient/db/Issue57H2UpgradeFixtureTest.java'
    assert fast_test.is_file() and 'flyway.migrate()' in fast_test.read_text() and 'assertEquals(before, snapshot(conn)' in fast_test.read_text(), 'Missing fast H2 previous-release fixture migration regression'
    sql=fixture.read_text()
    assert all(text in sql for text in REQUIRED_SQL), 'Predecessor fixture no longer protects user/token/job/provider data'
    assert "'OK'" in sql and "'FINISHED'" in sql, 'Preserved historical scan result must be valid'
    resolver=(root/'scripts/issue58-resolve-predecessor.py').read_text()
    assert 'fetch_releases' in resolver and 'version >= candidate_version' in resolver and 'published_at' in resolver, 'Missing dynamic previous published release selection'
    runtime=(root/'scripts/issue57-upgrade-path.py').read_text()
    for token in ('refs/tags/', "if actual != cfg['commit_sha']:", 'commit_sha', 'git\',\'worktree\'', 'docker\',\'build\'',
                  'probe(\'seed\'', 'probe(\'snapshot\'', 'assert_preserved(before,after,key,secrets_map)',
                  'fixture_decrypt(key, provider, encrypted.group())', 'verify_api', 'wait_for_https',
                  'docker\',\'stop\'', '--stop-timeout', 'auth-settings.key',
                  'configuration-center.properties', 'integrations.properties',
                  'SERVER_SSL_ENABLED=true', 'APP_DB_POSTGRES_LOB_AUTO_MIGRATE=false'):
        assert token in runtime, 'Upgrade test bypass/feature missing: ' + token
    assert 'except Exception:' in runtime and 'finally:' in runtime, 'Failures must collect logs and clean up'
    for vendor in ('h2','postgresql'):
        assert vendor in runtime, 'Missing vendor '+vendor
    ci=yaml.load((root/'.github/workflows/ci.yml').read_text(),Loader=yaml.BaseLoader)
    rel=yaml.load((root/'.github/workflows/release.yml').read_text(),Loader=yaml.BaseLoader)
    cj=ci['jobs'];rj=rel['jobs']
    assert 'upgrade-path' in cj and 'upgrade-path' in rj, 'Both PR and release need independent upgrade jobs'
    assert 'upgrade-path' in cj['gate']['needs'], 'Upgrade path must be in required CI gate'
    assert 'upgrade-path' in rj['image-publish']['needs'], 'No image tag may publish before upgrades'
    assert 'upgrade-path' in rj['draft']['needs'], 'No GitHub release may draft before upgrades'
    assert 'needs.changes.outputs.upgrade' in cj['upgrade-path']['if'], 'Relevant PRs must select upgrades'
    for job in (cj['upgrade-path'],rj['upgrade-path']):
        assert set(job['strategy']['matrix']['vendor'])=={'h2','postgresql'}, 'H2 and PostgreSQL must both upgrade'
        assert job['strategy']['fail-fast']=='false', 'Matrix failures must not hide other DB result'
        assert 'timeout-minutes' in job and any(step.get('with',{}).get('fetch-depth') == '0' for step in job['steps']), 'Job must fetch predecessor tags'
        assert job['permissions']=={'contents':'read'}, 'Upgrade test may not write GitHub'
        steps='\n'.join(str(step.get('run','')) for step in job['steps'])
        for command in ('dependency:copy-dependencies','issue57-upgrade-path.py','issue58-resolve-predecessor.py','cryptography==46.0.4'):
            assert command in steps, 'Missing real runtime upgrade prerequisite: '+command
        assert '--vendor "$UPGRADE_VENDOR"' in steps, 'Each matrix vendor must execute actual Docker upgrade'
        assert any('artifacts/upgrade-path/**' in str(step.get('with',{})) for step in job['steps']), 'Must upload upgrade evidence'
        assert not any(step.get('continue-on-error')=='true' for step in job['steps']), 'Cannot ignore upgrade failures'
    classifier=(root/'scripts/ci-detect-changes.py').read_text()
    evaluator=(root/'scripts/ci-evaluate-gate.py').read_text()
    assert "'upgrade-path': 'upgrade'" in evaluator and "'upgrade'" in classifier, 'Gate evaluator and classifier must select upgrades'
    print('PASS: previous-release upgrade gating for H2/PostgreSQL, pinned ref, preserved DB + conf, API smoke, no bypass')

if __name__ == '__main__':
    ap=argparse.ArgumentParser()
    ap.add_argument('--root',type=Path,default=ROOT)
    args=ap.parse_args()
    try:check(args.root)
    except (AssertionError,KeyError,ValueError,FileNotFoundError) as e:
        print('FAIL: issue #57 upgrade-path policy:',str(e),file=sys.stderr)
        sys.exit(1)
