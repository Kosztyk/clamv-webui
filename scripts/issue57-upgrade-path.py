#!/usr/bin/env python3
"""Real Docker upgrade of the pinned released source into the candidate image.

Starts the predecessor, seeds its real database, reopens it to prove credentials,
then stops it and launches the candidate with the SAME database and data/conf mounts.
Requires Docker, JDK 21, Maven-provided H2/Postgres JDBC jars and cryptography.
Never runs against production, and never prints credentials or encrypted settings.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / 'config/upgrade-baseline.json'
RESOLVED = ROOT / 'artifacts/upgrade-predecessor.json'
FIXTURE_DIR = ROOT / 'src/test/resources/upgrade-fixtures'
CI_BASE_PASSWORD = 'CIUpgradeFixtureKeystoreOnly'  # test-only certificate passphrase; no production secrets


def run(cmd: list[str], *, cwd: Path | None = None, input: str | None = None) -> str:
    result = subprocess.run(cmd, cwd=cwd, input=input, capture_output=True, text=True)
    if result.returncode:
        # No args on error: DB/token/SSL env may appear on the command line.
        raise RuntimeError(f'{cmd[0]} failed with exit={result.returncode}; stderr tail: {result.stderr[-750:]}')
    return result.stdout.strip()


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def fixture_cipher(key: bytes, provider: str, secret: str) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    iv = secrets.token_bytes(12)
    body = AESGCM(key).encrypt(iv, secret.encode(), ('clamav-auth-provider:' + provider).encode())
    return 'gcm2:' + base64.b64encode(iv + body).decode()


def fixture_decrypt(key: bytes, provider: str, encoded: str) -> str:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if not encoded.startswith('gcm2:'):
        raise AssertionError(f'{provider}: encrypted settings format changed/lost')
    body = base64.b64decode(encoded[5:], validate=True)
    return AESGCM(key).decrypt(body[:12], body[12:], ('clamav-auth-provider:' + provider).encode()).decode()


def render_fixture(template: Path, key: bytes, token: str, secrets_map: dict[str,str]) -> str:
    text = template.read_text()
    updates = {'__TOKEN_HASH__': sha(token.encode()), '__TOKEN_PREFIX__': token[:12],
               '__LDAP_CIPHER__': fixture_cipher(key, 'ldap', secrets_map['ldap']),
               '__OIDC_CIPHER__': fixture_cipher(key, 'oidc', secrets_map['oidc'])}
    for literal, value in updates.items():
        assert re.fullmatch(r'[a-zA-Z0-9_:+/=.-]+', value), f'Unsafe SQL fixture substitution: {literal}'
        text = text.replace(literal, value)
    if re.search(r'__[A-Z_]+__', text):
        raise AssertionError('Unexpanded fixture template variable')
    return text


def read_snapshot(raw: str) -> dict[str, list[str]]:
    rows: dict[str, list[str]] = {}
    for line in raw.splitlines():
        key, sep, encoded = line.partition('=')
        if not sep or key not in {'user','user-roles','token','endpoint','job','job-owner','audit','ldap','oidc'}:
            raise AssertionError('Malformed/unexpected historical database snapshot entry')
        decoded = base64.urlsafe_b64decode(encoded + '=' * (-len(encoded) % 4)).decode()
        rows.setdefault(key, []).append(decoded)
    if set(rows) != {'user','user-roles','token','endpoint','job','job-owner','audit','ldap','oidc'}:
        raise AssertionError('Historical snapshot missing required user/token/endpoint/job/settings rows')
    return rows


def assert_preserved(before: dict[str,list[str]], after: dict[str,list[str]], key: bytes, secret_map: dict[str,str]) -> None:
    if before != after:
        changed = sorted(k for k in set(before) | set(after) if before.get(k) != after.get(k))
        raise AssertionError('Previous release data was lost/changed in: ' + ', '.join(changed))
    for provider in ('ldap', 'oidc'):
        joined = after[provider][0]
        encrypted = re.search(r'gcm2:[A-Za-z0-9+/=]+', joined)
        if not encrypted or fixture_decrypt(key, provider, encrypted.group()) != secret_map[provider]:
            raise AssertionError(provider + ' encrypted authentication settings did not survive upgrade')
    if len(after['user-roles']) != 2:
        raise AssertionError('Role membership was lost')


def required_files(directory: Path, candidates: list[str]) -> Path:
    matches = [p for name in candidates for p in directory.rglob(name) if p.is_file()]
    if not matches:
        raise RuntimeError('Required JDBC driver unavailable: ' + ', '.join(candidates))
    return sorted(matches, key=lambda p: (len(p.name),p.name))[-1]


def wait_for_https(container: str, port: int, password: str, token: str, deadline: int = 150) -> None:
    context = ssl._create_unverified_context()  # CI-only self-signed certificate, bound to loopback
    base = f'https://127.0.0.1:{port}'
    for attempt in range(deadline):
        status = run(['docker', 'inspect', '-f', '{{.State.Running}}', container])
        if status != 'true':
            raise AssertionError(f'App container exited before readiness: {container}')
        try:
            request = urllib.request.Request(base + '/login')
            with urllib.request.urlopen(request, timeout=3, context=context) as response:
                if response.status == 200 and b'username' in response.read():
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(1)
    raise AssertionError('Application never exposed normal login (startup recovery/setup does not count)')


def verify_api(port: int, password: str, token: str) -> None:
    context = ssl._create_unverified_context()
    base = f'https://127.0.0.1:{port}'
    auth_user = base64.b64encode(('upgrade57-user:' + password).encode()).decode()
    endpoints = [('/api/v1/auth', 'Basic ' + auth_user, lambda obj: obj.get('principal') == 'upgrade57-user'),
                 ('/api/v1/auth', 'Bearer ' + token, lambda obj: obj.get('authentication') == 'token'),
                 ('/api/v1/endpoints', 'Bearer ' + token, lambda obj: isinstance(obj,list) and any(e.get('name') == 'upgrade57-endpoint' for e in obj)),
                 ('/api/v1/jobs/upgrade57-job', 'Bearer ' + token, lambda obj: obj.get('id') == 'upgrade57-job' and obj.get('status') == 'FINISHED'),
                 ('/api/v1/health/ready', 'Bearer ' + token, lambda obj: obj.get('status') == 'UP')]
    for path, authorization, assertion in endpoints:
        request = urllib.request.Request(base + path, headers={'Authorization': authorization})
        with urllib.request.urlopen(request, timeout=12, context=context) as response:
            if response.status != 200 or not assertion(json.load(response)):
                raise AssertionError('Previous-release API contract did not survive: ' + path)


def docker_port(container: str) -> int:
    value = run(['docker','port',container,'8080/tcp'])
    return int(value.strip().split(':')[-1])


def run_upgrade(root: Path, vendor: str, drivers_dir: Path, artifacts: Path, cfg: dict) -> None:
    name = re.sub(r'[^a-z0-9]', '', vendor) + '-' + secrets.token_hex(4)
    network = 'clamav-upgrade-' + name
    prior_name, next_name, db_name = ('upgrade-old-' + name, 'upgrade-new-' + name, 'upgrade-db-' + name)
    prior_image, next_image = 'clamav-upgrade-prior:' + name, 'clamav-upgrade-candidate:' + name
    data_volume, conf_volume = network + '-data', network + '-conf'
    containers: list[str] = []
    worktree: Path | None = None
    network_created = False
    with tempfile.TemporaryDirectory(prefix='clamav-upgrade-') as temp:
        scratch = Path(temp)
        conf, helper = scratch/'conf', scratch/'tools'
        conf.mkdir(mode=0o700)
        helper.mkdir(mode=0o755)
        key = secrets.token_bytes(32)
        secrets_map = {p: secrets.token_urlsafe(18) for p in ('ldap','oidc')}
        token = 'clamv_' + base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip('=')
        password = 'U57-' + secrets.token_urlsafe(20)
        key_file = conf/'auth-settings.key'
        key_file.write_text(base64.b64encode(key).decode() + '\n')
        key_file.chmod(0o640)
        (conf/'integrations.properties').write_text('# Persisted upgrade fixture integration configuration\nfixture.enabled=false\n')
        (conf/'configuration-center.properties').write_text('_format=1\n_revision=1\n_setupComplete=true\n')
        conf_hashes = {p.name: sha(p.read_bytes()) for p in (key_file,conf/'integrations.properties',conf/'configuration-center.properties')}
        sql = render_fixture(root/cfg['fixture'], key, token, secrets_map)
        # Fixture SQL and credentials stay in the disposable tool mount; only
        # redacted summary JSON may be uploaded as an artifact.
        (helper/'seed.sql').write_text(sql)
        h2jar = required_files(drivers_dir,['h2-*.jar'])
        pgjar = required_files(drivers_dir,['postgresql-*.jar'])
        shutil.copy2(h2jar, helper/'h2.jar')
        shutil.copy2(pgjar, helper/'postgresql.jar')
        classes = helper/'classes'
        classes.mkdir()
        run(['javac','--release','21','-d',str(classes),str(root/'scripts/Issue57JdbcProbe.java')])
        old_db_url = 'jdbc:h2:file:/app/data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=0'
        probe_url = old_db_url
        db_user, db_password = 'sa', ''
        try:
            run(['docker','network','create',network]);network_created = True
            worktree = scratch/'predecessor'
            # Enforce immutable released source and reject tag rebinding.
            actual = run(['git','rev-parse',f"refs/tags/{cfg['release_tag']}^{{commit}}"],cwd=root)
            if actual != cfg['commit_sha']:
                raise AssertionError('Previous release tag no longer points to pinned immutable commit')
            run(['git','worktree','add','--detach',str(worktree),actual],cwd=root)
            run(['docker','build','--progress=plain','-t',prior_image,str(worktree)])
            run(['docker','build','--progress=plain','-t',next_image,str(root)])
            if vendor == 'postgresql':
                db_password = secrets.token_urlsafe(23)
                db_user = 'upgrade_user'
                pgimage = cfg['postgres_image']
                run(['docker','run','-d','--name',db_name,'--network',network,'--network-alias','upgrade-db',
                     '-e','POSTGRES_USER='+db_user,'-e','POSTGRES_PASSWORD='+db_password,'-e','POSTGRES_DB=upgrade_db',pgimage])
                containers.append(db_name)
                for _ in range(90):
                    check = subprocess.run(['docker','exec',db_name,'pg_isready','-U',db_user,'-d','upgrade_db'],capture_output=True)
                    if check.returncode == 0: break
                    time.sleep(1)
                else: raise AssertionError('Previous-release PostgreSQL fixture database did not start')
                old_db_url = 'jdbc:postgresql://upgrade-db:5432/upgrade_db'
                probe_url = old_db_url
            elif vendor != 'h2':
                raise ValueError('Only H2 and PostgreSQL supported by upgrade smoke')

            def probe(mode: str, expected: int, sql_file: bool = False) -> str:
                cmd = ['docker','run','--rm','--network',network,'--user','10001:10001',
                       '--group-add',str(os.getgid()),'-v',data_volume+':/app/data',
                       '-v',str(helper)+':/tools:ro','--entrypoint','java',next_image,
                       '-cp','/tools/classes:/tools/h2.jar:/tools/postgresql.jar',
                       'Issue57JdbcProbe',mode,probe_url,db_user,db_password,str(expected)]
                if sql_file:cmd.append('/tools/seed.sql')
                return run(cmd)

            def app(image: str, container: str) -> int:
                cmd = ['docker','run','-d','--name',container,'--network',network,
                       '--user','10001:10001',
                       '-p','127.0.0.1::8080','--stop-timeout','75',
                       '-v',data_volume+':/app/data','-v',conf_volume+':/app/conf',
                       '-e','SPRING_DATASOURCE_URL='+old_db_url,
                       '-e','SPRING_DATASOURCE_USERNAME='+db_user,
                       '-e','SPRING_DATASOURCE_PASSWORD='+db_password,
                       '-e','CLAMAV_BOOTSTRAP_ADMIN_PASSWORD='+password,
                       '-e','CLAMAV_HOST=localhost',
                       '-e','APP_DB_POSTGRES_LOB_AUTO_MIGRATE=false',
                       '-e','SERVER_SSL_ENABLED=true',
                       '-e','SERVER_SSL_KEY_STORE=/app/conf/upgrade-fixture.p12',
                       '-e','SERVER_SSL_KEY_STORE_TYPE=PKCS12',
                       '-e','SERVER_SSL_KEY_STORE_PASSWORD='+CI_BASE_PASSWORD,
                       '-e','SERVER_SSL_KEY_ALIAS=upgrade-fixture',image]
                run(cmd);containers.append(container)
                port=docker_port(container)
                wait_for_https(container,port,password,token)
                return port

            run(['keytool','-genkeypair','-alias','upgrade-fixture','-keyalg','RSA','-keysize','2048',
                 '-validity','2','-dname','CN=localhost', '-ext','SAN=dns:localhost,ip:127.0.0.1',
                 '-storetype','PKCS12','-keystore',str(conf/'upgrade-fixture.p12'),
                 '-storepass',CI_BASE_PASSWORD,'-keypass',CI_BASE_PASSWORD,'-noprompt'])
            (conf/'upgrade-fixture.p12').chmod(0o640)
            conf_hashes['upgrade-fixture.p12'] = sha((conf/'upgrade-fixture.p12').read_bytes())
            # Docker owns both persistent volumes, so fixture ownership and cleanup
            # work even when the CI runner lacks permission to chown its files.
            run(['docker','run','--rm','--network','none','--user','0:0','--entrypoint','/bin/sh',
                 '-v',str(conf)+':/fixture-conf:ro','-v',data_volume+':/app/data',
                 '-v',conf_volume+':/app/conf',next_image,'-ec',
                 'cp -a /fixture-conf/. /app/conf/\n'
                 'chown -R 10001:10001 /app/conf /app/data\n'
                 'chmod 0700 /app/conf\n'
                 'chmod 0750 /app/data'])
            port = app(prior_image,prior_name)
            run(['docker','stop','-t','70',prior_name]);run(['docker','rm',prior_name]);containers.remove(prior_name)
            probe('seed',0,True)
            port = app(prior_image,prior_name)
            verify_api(port,password,token)
            run(['docker','stop','-t','70',prior_name]);run(['docker','rm',prior_name]);containers.remove(prior_name)
            before_version = int(probe('version',0).strip())
            before = read_snapshot(probe('snapshot',before_version))
            port = app(next_image,next_name)
            verify_api(port,password,token)
            run(['docker','stop','-t','70',next_name]);run(['docker','rm',next_name]);containers.remove(next_name)
            after_version = int(probe('version',0).strip())
            # Include Java Flyway migrations as well as vendor-specific SQL, now
            # and on future releases (V2 is currently a Java migration).
            vendor_dir = root/'src/main/resources/db/migration'/('postgresql' if vendor=='postgresql' else 'h2')
            migrations = list(vendor_dir.glob('V*__*.sql')) + list((root/'src/main/java').rglob('V*__*.java'))
            expected_versions = {int(m.group(1)) for m in (re.match(r'V(\d+)__',file.name) for file in migrations) if m}
            expected_candidate = max(expected_versions)
            if expected_versions != set(range(1,expected_candidate+1)) or after_version != expected_candidate or after_version < before_version:
                raise AssertionError('Candidate Flyway history is missing versions or older than the previous release')
            after = read_snapshot(probe('snapshot',expected_candidate))
            assert_preserved(before,after,key,secrets_map)
            run(['docker','run','--rm','-i','--network','none','--user','10001:10001',
                 '--entrypoint','sha256sum','-v',conf_volume+':/app/conf:ro',next_image,'-c','-'],
                input=''.join(f'{digest}  /app/conf/{filename}\n' for filename,digest in conf_hashes.items()))
            # Historical app state has been exercised with the same persisted
            # databases and host-mounted file permissions across both starts.
            artifacts.mkdir(parents=True,exist_ok=True)
            report={'vendor':vendor,'previous_release_tag':cfg['release_tag'],
                    'previous_release_commit':cfg['commit_sha'],
                    'rows_verified':sorted(before),'encrypted_providers':['ldap','oidc'],
                    'preserved_conf_files':sorted(conf_hashes),'previous_flyway_version':before_version,'flyway_latest_version':after_version,
                    'previous_and_candidate_api_verified':True,'same_data_and_conf_mounts':True,
                    'before_snapshot_sha256':sha(json.dumps(before,sort_keys=True).encode()),
                    'after_snapshot_sha256':sha(json.dumps(after,sort_keys=True).encode())}
            (artifacts/(vendor+'-upgrade.json')).write_text(json.dumps(report,indent=2)+'\n')
            print('PASS: '+vendor+' previous release -> candidate persisted migration, auth, token, settings and API smoke')
        except Exception:
            # Preserve bounded, redacted failure diagnostics before deleting containers.
            artifacts.mkdir(parents=True, exist_ok=True)
            redact = [token, password, db_password, base64.b64encode(key).decode(),
                      *secrets_map.values()]
            for container in containers:
                logs = subprocess.run(['docker','logs','--tail','140',container],
                                      capture_output=True,text=True)
                body = (logs.stdout + logs.stderr)[-25000:]
                for sensitive in redact:
                    if sensitive:
                        body = body.replace(sensitive, '<REDACTED>')
                (artifacts/(vendor+'-'+container+'-failure.log')).write_text(body)
            raise
        finally:
            for container in reversed(containers):
                subprocess.run(['docker','rm','-fv',container],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            subprocess.run(['docker','volume','rm',data_volume,conf_volume],
                           stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if worktree is not None and worktree.exists():
                subprocess.run(['git','worktree','remove','--force',str(worktree)],cwd=root,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if network_created:
                subprocess.run(['docker','network','rm',network],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)


def main() -> None:
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--vendor',choices=('h2','postgresql'),required=True)
    parser.add_argument('--drivers-dir',type=Path,default=ROOT/'artifacts/upgrade-drivers')
    parser.add_argument('--reports-dir',type=Path,default=ROOT/'artifacts/upgrade-path')
    parser.add_argument('--resolved-predecessor',type=Path,default=RESOLVED)
    args=parser.parse_args()
    cfg=json.loads(CONFIG.read_text())
    resolved=json.loads(args.resolved_predecessor.read_text())
    for key in ('release_tag','commit_sha'):
        assert key in resolved, 'Incomplete latest-release resolution: '+key
    assert re.fullmatch(r'[0-9a-f]{40}',resolved['commit_sha']), 'Immutable predecessor SHA required'
    cfg.update({key:resolved[key] for key in ('release_tag','commit_sha')})
    assert (ROOT/cfg['fixture']).is_file(), 'Missing versioned previous release data fixture'
    run_upgrade(ROOT,args.vendor,args.drivers_dir,args.reports_dir,cfg)


if __name__=='__main__':
    main()
