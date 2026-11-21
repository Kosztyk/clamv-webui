#!/usr/bin/env python3
"""Offline adversarial regression tests for the real previous-release upgrade gate."""
from __future__ import annotations
import base64
import copy
import importlib.util
import json
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]


def load():
    spec=importlib.util.spec_from_file_location('issue57runtime',ROOT/'scripts/issue57-upgrade-path.py')
    assert spec and spec.loader
    mod=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def expect_failure(func,label):
    try:func()
    except (AssertionError,ValueError,Exception):
        print('PASS: blocked '+label)
    else:raise AssertionError('Unsafe upgrade behavior accepted: '+label)


def structural_negative_tests():
    paths=['config/upgrade-baseline.json',
           'src/test/resources/upgrade-fixtures/v2.1beta1/seed.sql.template',
           'src/test/resources/upgrade-fixtures/v2.1beta1/h2-schema/V1__initial_schema.sql',
           'src/test/resources/upgrade-fixtures/v2.1beta1/h2-schema/V3__external_identities.sql',
           'src/test/resources/upgrade-fixtures/v2.1beta1/h2-schema/V4__authentication_settings.sql',
           'src/test/java/info/trizub/clamav/webclient/db/Issue57H2UpgradeFixtureTest.java',
           'scripts/issue57-upgrade-path.py','scripts/issue58-resolve-predecessor.py','scripts/ci-detect-changes.py',
           'scripts/ci-evaluate-gate.py',
           '.github/workflows/ci.yml','.github/workflows/release.yml']
    with tempfile.TemporaryDirectory(prefix='issue57-policy-') as folder:
        tmp=Path(folder)
        for name in paths:
            target=tmp/name;target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copy2(ROOT/name,target)
        validator=ROOT/'scripts/check-issue57-upgrade-path.py'
        def run_policy(expected_success:bool,label:str):
            p=subprocess.run([sys.executable,str(validator),'--root',str(tmp)],
                             capture_output=True,text=True)
            assert (p.returncode==0)==expected_success, f'{label}: {p.stdout}\n{p.stderr}'
            print('PASS:',label)
        run_policy(True,'complete upgrade gate accepted')
        mutations=[
          ('.github/workflows/ci.yml', 'gate:\n    name: gate', 'gate:\n    name: gate',
           'original gate structure remains'),
          ('.github/workflows/release.yml',
           'db-migrations, upgrade-path, cli-test', 'db-migrations, cli-test',
           'removed release promotion dependency'),
          ('.github/workflows/release.yml',
           'matrix:\n        vendor: [h2, postgresql]', 'matrix:\n        vendor: [h2]',
           'removed PostgreSQL upgrade branch'),
          ('.github/workflows/ci.yml',
           'matrix:\n        vendor: [h2, postgresql]', 'matrix:\n        vendor: [postgresql]',
           'removed H2 upgrade branch'),
          ('src/test/resources/upgrade-fixtures/v2.1beta1/seed.sql.template',
           'INSERT INTO api_credentials', 'INSERT INTO removed_api_credentials',
           'removed token history fixture'),
          ('scripts/issue57-upgrade-path.py',
           'assert_preserved(before,after,key,secrets_map)', '# disabled integrity check',
           'disabled historical row comparison'),
          ('scripts/issue57-upgrade-path.py',
           'fixture_decrypt(key, provider, encrypted.group())', 'secret_map[provider]',
           'disabled AES-GCM proof'),
          ('scripts/issue57-upgrade-path.py',
           "if actual != cfg['commit_sha']:", "if False:",
           'tampered immutable predecessor provenance'),
          ('config/upgrade-baseline.json',
           '"fixture_source_commit": "d06ea6a8ea4d2fd6c89fea1c4be7f55707c77eb3"',
           '"fixture_source_commit": "unverified-floating-tag"',
           'unverified historical seed provenance'),
          ('scripts/issue58-resolve-predecessor.py',
           'version >= candidate_version',
           'False', 'newer and equal-version predecessor allowed'),
        ]
        for name,needle,replacement,label in mutations[1:]:
            file=tmp/name; original=file.read_text()
            assert needle in original, 'Missing mutation target: '+label
            file.write_text(original.replace(needle,replacement,1))
            run_policy(False,label)
            file.write_text(original)


def data_tests():
    mod=load()
    key=secrets.token_bytes(32)
    raw='clamv_'+base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
    secrets_map={k:secrets.token_urlsafe(16) for k in ('ldap','oidc')}
    template=ROOT/'src/test/resources/upgrade-fixtures/v2.1beta1/seed.sql.template'
    sql=mod.render_fixture(template,key,raw,secrets_map)
    assert 'INSERT INTO app_users' in sql and 'INSERT INTO scan_jobs' in sql
    assert all(placeholder not in sql for placeholder in ('__TOKEN_HASH__','__TOKEN_PREFIX__','__LDAP_CIPHER__','__OIDC_CIPHER__'))
    assert mod.sha(raw.encode()) in sql and raw not in sql, 'Only API token hash may be stored'
    print('PASS: versioned fixture renders historical users, token hash, job and encrypted providers')
    for provider in ('ldap','oidc'):
        ciphertext=mod.fixture_cipher(key,provider,secrets_map[provider])
        assert mod.fixture_decrypt(key,provider,ciphertext)==secrets_map[provider]
        expect_failure(lambda provider=provider,ciphertext=ciphertext:mod.fixture_decrypt(secrets.token_bytes(32),provider,ciphertext),
                       provider+' changed encryption key')
    fixture={
        'user':['upgrade57-user:$2b$12$synthetic:TRUE'],
        'user-roles':['ROLE_OPERATOR','ROLE_VIEWER'],
        'token':['a'*64+':clamv_token:health:read,jobs:read,endpoints:read'],
        'endpoint':['upgrade57-endpoint:localhost:3310:TRUE'],
        'job':['upgrade57-job:UPLOAD:FINISHED:OK:upgrade57-file.txt:upgrade57-user'],
        'job-owner':['5702'],
        'audit':['SCAN_RESULT:historical audit'],
        'ldap':['{}:'+mod.fixture_cipher(key,'ldap',secrets_map['ldap'])+':fixture'],
        'oidc':['{}:'+mod.fixture_cipher(key,'oidc',secrets_map['oidc'])+':fixture'],
    }
    encoded='\n'.join(k+'='+base64.urlsafe_b64encode(v.encode()).decode().rstrip('=')
                      for k,items in fixture.items() for v in items)
    assert mod.read_snapshot(encoded)==fixture
    mod.assert_preserved(fixture,copy.deepcopy(fixture),key,secrets_map)
    print('PASS: unchanged historical DB and AES-GCM provider secrets survive')
    for name in fixture:
        damaged=copy.deepcopy(fixture);damaged[name][0]+='-lost'
        expect_failure(lambda damaged=damaged:mod.assert_preserved(fixture,damaged,key,secrets_map),
                       'modified '+name+' record')
    damaged=copy.deepcopy(fixture);damaged.pop('token')
    expect_failure(lambda:mod.read_snapshot('\n'.join(k+'='+base64.urlsafe_b64encode(v.encode()).decode().rstrip('=') for k,items in damaged.items() for v in items)),
                   'missing token record')
    damaged=copy.deepcopy(fixture);damaged['user-roles'].pop()
    expect_failure(lambda:mod.assert_preserved(damaged,damaged,key,secrets_map),'incomplete role membership')
    print('PASS: altered/missing fields, tokens, roles, jobs and provider secrets block release')


if __name__=='__main__':
    data_tests()
    structural_negative_tests()
    print('PASS: issue #57 upgrade regression and adversarial suite')
