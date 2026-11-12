#!/usr/bin/env python3
"""Packaged-JAR limiter checks with isolated H2 and a synthetic ClamD peer."""
import argparse
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location('scope_fixture', Path(__file__).with_name('verify-token-path-scopes.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


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
    with tempfile.TemporaryDirectory(prefix='clamav-bounded-limiters-') as temporary, fixture.Daemon(('127.0.0.1', 0), fixture.Peer) as daemon:
        threading.Thread(target=daemon.serve_forever, daemon=True).start()
        folder = Path(temporary)
        scan = folder / 'scandir'; scan.mkdir()
        sample = scan / 'fixture.txt'; sample.write_text('Synthetic rate-limit fixture')
        app = fixture.runtime.Application(args.jar.resolve(), folder, extra=[
            '--spring.datasource.url=jdbc:h2:file:./data/limiters;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
            '--clamav.service.host=127.0.0.1', f'--clamav.service.port={daemon.server_address[1]}',
            f'--app.allowedScanRoots={scan}', '--api.v1.scan-rate-limit-per-minute=2',
            '--api.v1.scan-rate-limit-max-entries=3', '--clamav.security.login.max-entries=3'])
        opener = urllib.request.build_opener(fixture.NoRedirect())
        try:
            app.wait('/login'); app.login()
            def issue(label):
                status, body, _ = app.request('/api/v1/tokens', {'label': label, 'scopes': ['scan:path'], 'pathRoots': [str(scan)]},
                                               headers={'X-CSRF-TOKEN': app.csrf})
                if status != 201:
                    raise AssertionError('Admin token issuance failed')
                return json.loads(body)['secret']
            def request(path, authorization, payload=None, peer='192.0.2.1', csrf=None, cookie=None):
                headers = {'X-Forwarded-Proto': 'https', 'X-Forwarded-For': peer, 'Authorization': authorization}
                if csrf:
                    headers['X-CSRF-TOKEN'] = csrf
                if cookie:
                    headers['Cookie'] = cookie
                data = json.dumps(payload).encode() if payload is not None else None
                if data is not None:
                    headers['Content-Type'] = 'application/json'
                req = urllib.request.Request(app.base + path, data=data, headers=headers)
                try:
                    with opener.open(req, timeout=15) as response:
                        return response.status, response.read(), response.headers
                except urllib.error.HTTPError as response:
                    return response.code, response.read(), response.headers
            first, second, third = issue('first'), issue('second'), issue('third')
            path = '/api/v1/scans/paths'
            for i, prefix in enumerate(['Bearer ', 'bEaReR ', 'BEARER ']):
                status, body, headers = request(path, prefix + first, {'path': str(sample)}, peer=f'192.0.2.{i+1}')
                check(status == (202 if i < 2 else 429), 'Authenticated token keeps one budget across header case and peer changes, request ' + str(i+1))
                if i == 2:
                    check(1 <= int(headers['Retry-After']) <= 60 and headers['Cache-Control'] == 'no-store', 'Rate rejection supplies bounded Retry-After and no-store')
            check(request(path, 'Bearer ' + second, {'path': str(sample)})[0] == 202,
                  'Another token behind the same proxy IP has an independent budget')
            basic = base64.b64encode(('admin:' + fixture.runtime.ADMIN_PASSWORD).encode()).decode()
            status, body, csrf_headers = request('/api/v1/csrf', 'Basic ' + basic)
            check(status == 200, 'Basic authentication obtains the normal CSRF cookie/token')
            csrf = json.loads(body)['token']
            cookie = csrf_headers['Set-Cookie'].split(';', 1)[0]
            for i, prefix in enumerate(['Basic ', 'bAsIc ', 'BASIC ']):
                check(request(path, prefix + basic, {'path': str(sample)}, peer=f'198.51.100.{i+1}', csrf=csrf, cookie=cookie)[0]
                      == (202 if i < 2 else 429), 'Basic account budget survives header case and peer changes, request ' + str(i+1))
            check(request(path, 'Bearer ' + third, {'path': str(sample)})[0] == 429,
                  'Full three-entry scan cache rejects an untracked token instead of evicting an active budget')
            check(request(path, 'Bearer ' + first, {'path': str(sample)})[0] == 429,
                  'Capacity churn does not reset the first token budget')
            check(request('/api/v1/health/live', 'Basic ' + basic)[0] == 200,
                  'Full scan cache leaves authenticated health reads available')
            for i in range(25):
                if request(path, 'Bearer invalid-' + str(i), {'path': str(sample)})[0] != 401:
                    raise AssertionError('Invalid credentials must remain unauthorized')
            check(True, 'Rotating invalid Bearer credentials are rejected before scan allocation')
            for name in ['random-one', 'random-two', 'random-three']:
                invalid = base64.b64encode((name + ':wrong-password').encode()).decode()
                check(request('/api/v1/health/live', 'Basic ' + invalid)[0] == 401,
                      'First tracked failure is recorded for ' + name)
            check(request('/api/v1/health/live', 'Basic ' + basic)[0] == 429,
                  'Full login cache fails closed for an untracked account even with the correct password')
            check(app.request('/api/v1/health/live')[0] == 200,
                  'Existing authenticated browser session remains usable during login-cache saturation')
            check(request(path, 'Bearer ' + first, {'path': str(sample)})[0] == 429,
                  'Login-cache saturation does not bypass the independent scan budget')
        finally:
            app.close()
    report = {'checks': len(checks), 'passed': len(checks), 'assertions': checks,
              'scope': 'Real packaged JAR, Spring Security and isolated H2. Synthetic ClamD replies and explicit loopback trusted native proxy headers; no external TLS/IdP/Docker runtime.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'checks': len(checks), 'passed': len(checks)}))


if __name__ == '__main__':
    main()
