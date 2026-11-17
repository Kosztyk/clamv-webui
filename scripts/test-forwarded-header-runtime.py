#!/usr/bin/env python3
"""Exercise issue #13 on the packaged standalone setup/recovery HTTP listener."""
import argparse
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
        parser.error('Build the JAR with mvn verify first, or supply --jar')
    checks = 0
    cases = [
        ('direct-default', (), 400),
        ('native-no-allowlist', ('--server.forward-headers-strategy=native',), 400),
        ('native-unlisted-peer', ('--server.forward-headers-strategy=native', '--server.tomcat.remoteip.internal-proxies=192.0.2.10/32'), 400),
        ('native-trusted-peer', ('--server.forward-headers-strategy=native', '--server.tomcat.remoteip.internal-proxies=127.0.0.1/32'), 200),
    ]
    with tempfile.TemporaryDirectory(prefix='clamav-forwarded-header-runtime-') as temporary:
        for name, options, expected in cases:
            folder = Path(temporary) / name
            folder.mkdir()
            app = smoke.Application(jar, folder, extra=options, forwarded=False)
            try:
                app.wait('/setup/')
                status, view = app.api('view', {})
                assert status == 200
                draft = {'version': view['version'], 'changes': {'spring.datasource.password': 'synthetic-runtime-secret'}}
                headers = {'X-Forwarded-For': '1.2.3.4', 'X-Forwarded-Proto': 'https'}
                status, _ = app.api('review', draft, headers=headers)
                assert status == expected, (name, status, expected)
                print('PASS:', name, 'requires HTTP acknowledgement' if expected == 400 else 'recognizes allowlisted proxy TLS', flush=True)
                checks += 1
                status, _ = app.api('review', draft, headers={'Forwarded': 'for=1.2.3.4;proto=https'})
                assert status == 400, (name, 'RFC Forwarded must not enable native TLS')
                checks += 1
                if name == 'direct-default':
                    for i in range(8):
                        status, _, _ = app.request('/setup/api/login', {'token': 'wrong-synthetic-token'}, headers={'X-Forwarded-For': f'192.0.2.{i+1}'})
                        assert status == 401
                    status, _, _ = app.request('/setup/api/login', {'token': 'wrong-synthetic-token'}, headers={'X-Forwarded-For': '198.51.100.10'})
                    assert status == 429, 'Rotating forged IP headers must not bypass the recovery lockout'
                    checks += 1
                    print('PASS: rotating spoofed IPs cannot bypass recovery lockout', flush=True)
            finally:
                app.close()
    print(json.dumps({'checks': checks, 'result': 'passed'}))


if __name__ == '__main__':
    main()
