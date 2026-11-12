#!/usr/bin/env python3
"""Packaged path-privacy checks with real operator sessions, Basic and restricted Bearer tokens."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


fixture = module('scope_fixture', 'verify-token-path-scopes.py')
sessions = module('session_fixture', 'verify-session-revocation.py')
DENIED = 'Path is not allowed or does not exist'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    checks = []
    def check(condition, description):
        if not condition:
            raise AssertionError(description)
        checks.append(description)
        print('PASS:', description, flush=True)
    with tempfile.TemporaryDirectory(prefix='clamav-path-privacy-') as temporary, fixture.Daemon(('127.0.0.1', 0), fixture.Peer) as daemon:
        threading.Thread(target=daemon.serve_forever, daemon=True).start()
        folder = Path(temporary)
        real = folder / 'scan-real'; real.mkdir()
        tenant = real / 'tenant'; tenant.mkdir()
        other = real / 'other'; other.mkdir()
        allowed = tenant / 'allowed.txt'; allowed.write_text('Synthetic allowed fixture')
        other_existing = other / 'existing.txt'; other_existing.write_text('Other tenant fixture')
        configured = folder / 'scandir'; configured.symlink_to(real, target_is_directory=True)
        token_alias = folder / 'token-root-alias'; token_alias.symlink_to(tenant, target_is_directory=True)
        existing = folder / 'private-existing.txt'; existing.write_text('Synthetic private fixture')
        missing = folder / 'private-missing.txt'
        escape = tenant / 'escape'; escape.symlink_to(existing)
        dangling = tenant / 'dangling'; dangling.symlink_to(missing)
        loop = tenant / 'loop'; loop.symlink_to(loop)
        outside_alias = folder / 'outside-alias'; outside_alias.symlink_to(allowed)
        rejected = [existing, missing, escape, dangling, loop, outside_alias]
        app = fixture.runtime.Application(args.jar.resolve(), folder, extra=[
            '--spring.datasource.url=jdbc:h2:file:./data/path-privacy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
            '--clamav.service.host=127.0.0.1', f'--clamav.service.port={daemon.server_address[1]}',
            f'--app.allowedScanRoots={configured}'])
        machine = urllib.request.build_opener(fixture.NoRedirect())
        try:
            app.wait('/login'); app.login()
            endpoint = json.loads(app.request('/api/endpoints')[1])[0]['id']
            operator = sessions.Browser(app.base)
            form = {'username': 'privacy-operator', 'password': sessions.PASSWORD, 'role': 'OPERATOR', 'enabled': 'true', '_csrf': app.csrf}
            request = urllib.request.Request(app.base + '/admin/users/create',
                data=urllib.parse.urlencode(form).encode(), headers={'X-Forwarded-Proto': 'https'})
            with app.opener.open(request, timeout=15) as response:
                if response.status != 200:
                    raise AssertionError('Operator creation failed')
            check(operator.login('privacy-operator', sessions.PASSWORD), 'A real operator session authenticates')
            status, token_body, _ = app.request('/api/v1/tokens', {'label': 'path privacy', 'scopes': ['scan:path'],
                'pathRoots': [str(token_alias)]}, headers={'X-CSRF-TOKEN': app.csrf})
            check(status == 201, 'Administrator-selected alias resolves to a permitted canonical token root')
            token = json.loads(token_body)
            check(token['token']['pathRoots'] == str(tenant.resolve()), 'The token root remains pinned to its canonical directory')
            basic = 'Basic ' + base64.b64encode(('privacy-operator:' + sessions.PASSWORD).encode()).decode()
            def submit(path, target, authorization=None):
                headers = {'Content-Type': 'application/json'}
                opener = operator.opener
                if authorization:
                    headers.update(Authorization=authorization, **{'X-Forwarded-Proto': 'https'})
                    opener = machine
                else:
                    headers['X-CSRF-TOKEN'] = operator.csrf
                req = urllib.request.Request(app.base + path, data=json.dumps({'path': str(target), 'endpointId': endpoint}).encode(), headers=headers)
                try:
                    with opener.open(req, timeout=15) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as response:
                    return response.code, response.read()
            def public(payload):
                doc = json.loads(payload)
                doc.pop('timestamp', None)
                return doc
            before = json.loads(app.request('/api/v1/jobs')[1])['totalItems']
            for index, target in enumerate(rejected):
                status, page, _ = operator.request('/scan/path', {'path': str(target), 'endpointId': endpoint})
                check(status == 200 and DENIED.encode() in page and str(target).encode() not in page,
                      'Operator page uses one safe error for rejected target ' + str(index + 1))
            for path, auth in [('/api/scan/path', None), ('/api/v1/scans/paths', None), ('/api/scan/path', basic)]:
                expected = None
                targets = rejected if auth is None else [existing, missing]
                for target in targets:
                    status, body = submit(path, target, auth)
                    if status != 400:
                        raise AssertionError('Rejected session/Basic path must return 400')
                    actual = public(body)
                    if expected is None:
                        expected = actual
                    if actual != expected or str(target).encode() in body:
                        raise AssertionError('Rejected paths must have the same public error')
                check(True, path + (' with Basic' if auth else ' with session') + ' has identical public failures for existing/missing and escape targets')
            restricted = rejected + [other_existing, other / 'missing.txt', tenant / 'missing.txt']
            expected = None
            for target in restricted:
                status, body = submit('/api/v1/scans/paths', target, 'Bearer ' + token['secret'])
                if status != 403:
                    raise AssertionError('Every restricted-token path denial must return 403')
                actual = public(body)
                if expected is None:
                    expected = actual
                if actual != expected or str(target).encode() in body:
                    raise AssertionError('Restricted token must not distinguish target existence')
            check(True, 'Restricted token cannot distinguish existing/missing global, tenant or symlink-denied targets')
            check(json.loads(app.request('/api/v1/jobs')[1])['totalItems'] == before,
                  'Rejected requests create no queued scan jobs')
            check(submit('/api/v1/scans/paths', configured / 'tenant/allowed.txt')[0] == 202,
                  'The configured root alias accepts an allowed operator scan')
            check(submit('/api/v1/scans/paths', allowed, 'Bearer ' + token['secret'])[0] == 202,
                  'A canonical permitted token path still queues successfully')
            inside_alias = tenant / 'inside-alias'; inside_alias.symlink_to(allowed)
            check(submit('/api/v1/scans/paths', inside_alias, 'Bearer ' + token['secret'])[0] == 202,
                  'An in-root alias still queues successfully without widening the grant')
        finally:
            app.close()
    report = {'checks': len(checks), 'passed': len(checks), 'assertions': checks,
              'scope': 'Real executable JAR, Tomcat, operator form session/Basic/Bearer and isolated H2/files. Synthetic ClamD replies and explicit loopback trusted native proxy headers; no external TLS/IdP/Docker runtime.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'checks': len(checks), 'passed': len(checks)}))


if __name__ == '__main__':
    main()
