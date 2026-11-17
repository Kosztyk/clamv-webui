#!/usr/bin/env python3
"""Check Basic failure counting and successful reset on the executable application JAR."""
import argparse
import base64
import http.client
import importlib.util
import json
from pathlib import Path
import tempfile

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configuration_smoke', ROOT / 'tools/verify-configuration-center.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/clamav-web-client-2.1.0.jar')
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build with mvn verify first, or supply --jar')
    checks = 0
    with tempfile.TemporaryDirectory(prefix='clamav-basic-lockout-runtime-') as temporary:
        app = smoke.Application(jar, Path(temporary))
        try:
            app.wait('/setup/')
            _, view = app.api('view', {})
            status, review = app.api('review', {'version': view['version'], 'changes': {'clamav.service.host': '127.0.0.1', 'clamav.service.port': '9'}})
            assert status == 200
            assert app.api('apply', {'reviewId': review['reviewId'], 'acknowledgeDatabase': True})[0] == 200
            assert app.api('start', {})[0] == 200
            app.wait('/login')

            def request(password, peer, path='/api/v1/health/live'):
                # Each request is cookie-free: verify the supplied password every time.
                authorization = 'Basic ' + base64.b64encode(('admin:' + password).encode()).decode()
                connection = http.client.HTTPConnection('127.0.0.1', app.port, timeout=10)
                try:
                    connection.request('GET', path, headers={'Authorization': authorization, 'X-Forwarded-Proto': 'https', 'X-Forwarded-For': peer})
                    response = connection.getresponse()
                    return response.status, response.read().decode(), dict(response.getheaders())
                finally:
                    connection.close()

            for cycle in range(2):
                for i in range(4):
                    assert request('wrong-password', f'192.0.2.{i+1}')[0] == 401
                    checks += 1
                assert request(smoke.ADMIN_PASSWORD, '198.51.100.10')[0] == 200
                checks += 1
                print('PASS: successful Basic login resets four failed attempts, cycle', cycle+1, flush=True)
            for i in range(5):
                assert request('wrong-password', f'192.0.2.{i+1}')[0] == 401
                checks += 1
            status, body, headers = request(smoke.ADMIN_PASSWORD, '203.0.113.10')
            assert status == 429 and 'login_locked' in body
            assert 1 <= int(headers['Retry-After']) <= 900
            checks += 1
            assert request(smoke.ADMIN_PASSWORD, '203.0.113.11', '/dashboard')[0] == 429
            checks += 1
            print('PASS: five failures lock correct credentials across IPs and routes', flush=True)
        finally:
            app.close()
    print(json.dumps({'checks': checks, 'result': 'passed'}))


if __name__ == '__main__':
    main()
