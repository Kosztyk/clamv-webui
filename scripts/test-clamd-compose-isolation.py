#!/usr/bin/env python3
"""Validate default/merged Compose exposure; --runtime checks a fresh CI H2 installation."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compose', default='docker', help='Docker CLI (default), or a standalone docker-compose binary')
    parser.add_argument('--runtime', action='store_true', help='Also inspect the fresh CI containers after smoke-eicar.sh')
    args = parser.parse_args()
    compose = [args.compose, 'compose'] if Path(args.compose).name == 'docker' else [args.compose]
    env = dict(os.environ)
    for k in ['COMPOSE_FILE', 'COMPOSE_PROJECT_NAME']:
        env.pop(k, None)
    env.update(SPRING_DATASOURCE_URL='jdbc:h2:mem:isolation-check', SPRING_DATASOURCE_USERNAME='sa', SPRING_DATASOURCE_PASSWORD='synthetic-config-check', CLAMAV_REST_URL='')
    checks = 0

    def check(condition, message):
        nonlocal checks
        if not condition:
            raise AssertionError(message)
        checks += 1
        print('PASS:', message, flush=True)

    def config(*files):
        command = compose[:]
        for f in files:
            command += ['-f', str(ROOT / f)]
        return json.loads(subprocess.check_output(command + ['config', '--format', 'json'], cwd=ROOT, env=env))

    pin = json.loads((ROOT / 'docs/security/clamd-image-pin.json').read_text())['image']
    check(bool(re.fullmatch(r'clamav/clamav:[0-9]+\.[0-9]+\.[0-9]+@sha256:[0-9a-f]{64}', pin)), 'Scanner image has a fixed version and digest')
    cases = [
        ('root', ('docker-compose.yml',), True),
        ('root with shared paths', ('docker-compose.yml', 'examples/compose/shared-paths.override.yaml'), True),
        ('standalone INSTREAM', ('examples/compose/standalone-h2.yaml',), False),
        ('standalone with shared paths', ('examples/compose/standalone-h2.yaml', 'examples/compose/shared-paths.override.yaml'), True),
    ]
    for name, files, shared in cases:
        d = config(*files)
        scanner = d['services']['clamav-server']
        web = d['services']['clamav-web-client']
        check(not scanner.get('ports'), name + ': ClamD publishes no host port')
        if shared:
            build=scanner.get('build',{})
            source=(ROOT/'Dockerfile.clamd').read_text()
            check(scanner['image']=='clamav-server:1.4.6-uid10001-local'
                  and (build.get('dockerfile')=='Dockerfile.clamd' or build.get('dockerfile_inline','').replace('$$','$')==source)
                  and build.get('context')==str((ROOT/files[0]).parent)
                  and 'FROM '+pin in source, name + ': UID-aligned scanner derives from the pinned official image')
        else:
            check(scanner['image'] == pin, name + ': scanner uses the pinned image')
        mounts = scanner.get('volumes', [])
        check(all(m['target'] not in {'/', '/app', '/app/data', '/app/conf'} for m in mounts), name + ': private app state has no ancestor mount')
        check(all(m['target'] in {'/app/data/uploads', '/var/lib/clamav', '/scandir'} for m in mounts), name + ': only intended scanner paths are mounted')
        if shared:
            uploads = [m for m in mounts if m['target'] == '/app/data/uploads']
            check(len(uploads) == 1 and uploads[0].get('read_only') is True, name + ': uploads mount is read-only')
            check(uploads[0]['source'] == str((ROOT / files[0]).parent / 'data/uploads'), name + ': only the upload subdirectory is shared')
            check(uploads[0].get('bind', {}).get('create_host_path') is False, name + ': missing upload path is not silently created')
            check(web['environment']['CLAMAV_UPLOAD_DIR'] == '/app/data/uploads', name + ': application upload path matches the scanner mount')
        else:
            check(not any(m['target'].startswith('/app') for m in mounts), name + ': INSTREAM shares no app storage')
    root = config('docker-compose.yml')
    check(root['services']['clamav-web-client']['environment']['CLAMAV_REST_URL'] == '', 'REST scanner URL is optional and blank by default')
    loopback = config('docker-compose.yml', 'examples/compose/clamd-loopback.override.yaml')['services']['clamav-server']['ports']
    check(len(loopback) == 1 and loopback[0]['host_ip'] == '127.0.0.1' and str(loopback[0]['published']) == '3310', 'Optional ClamD host publication is loopback-only')
    if args.runtime:
        # Use the operator/CI Compose environment here, including COMPOSE_FILE.
        def run(*command):
            return subprocess.check_output(compose + list(command), cwd=ROOT, text=True).strip()
        scanner_id = run('ps', '-q', 'clamav-server')
        check(bool(scanner_id), 'Live ClamD container exists')
        container = json.loads(subprocess.check_output(['docker', 'inspect', scanner_id], text=True))[0]
        check(not any(container['HostConfig'].get('PortBindings', {}).values()), 'Live ClamD has no host port bindings')
        mounts = container['Mounts']
        check(not any(m['Destination'] in {'/', '/app', '/app/data', '/app/conf'} for m in mounts), 'Live ClamD has no private-state ancestor mounts')
        uploads = [m for m in mounts if m['Destination'] == '/app/data/uploads']
        check(len(uploads) == 1 and uploads[0]['RW'] is False, 'Live upload mount is read-only')
        uid=run('exec','-T','clamav-server','id','-u','clamav')
        check(uid=='10001','Bundled scanner account matches the private upload owner UID')
        import time
        for attempt in range(100):
            remaining=run('exec','-T','clamav-web-client','find','/app/data/uploads','-type','f','-print','-quit')
            if not remaining:break
            time.sleep(0.1)
        check(not remaining,'Clean and infected smoke uploads have been removed after terminal verdicts')
        for path in ['/app/data/clamav-web-client.mv.db', '/app/data/bootstrap-admin-password']:
            subprocess.check_call(compose + ['exec', '-T', 'clamav-web-client', 'test', '-f', path], cwd=ROOT)
            subprocess.check_call(compose + ['exec', '-T', 'clamav-server', 'sh', '-c', '! test -r "$1"', 'isolation-check', path], cwd=ROOT)
            check(True, 'ClamD cannot read Web UI private file ' + Path(path).name)
    print(json.dumps({'checks': checks, 'runtime': args.runtime, 'result': 'passed'}))


if __name__ == '__main__':
    main()
