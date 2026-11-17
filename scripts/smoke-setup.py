#!/usr/bin/env python3
"""CI only: initialize a fresh Compose deployment over direct HTTP before EICAR checks."""
import argparse
import json
import subprocess
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8080')
    args = parser.parse_args()
    base = args.base_url.rstrip('/')
    deadline = time.monotonic() + 180
    credential = ''
    while time.monotonic() < deadline:
        result = subprocess.run(['docker', 'compose', 'exec', '-T', 'clamav-web-client',
                                 'cat', '/app/conf/configuration-recovery-token'], capture_output=True, text=True)
        if result.returncode == 0:
            credential = result.stdout.strip()
            break
        time.sleep(1)
    if len(credential) != 43:
        raise AssertionError('Recovery credential was not created by the fresh Compose application')

    def api(action, payload):
        request = urllib.request.Request(base + '/setup/api/' + action, data=json.dumps(payload).encode(),
            headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + credential, 'Origin': base})
        with urllib.request.urlopen(request, timeout=15) as response:
            return json.load(response)

    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + '/setup/', timeout=5) as response:
                if response.status == 200:
                    break
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    view = api('view', {})
    if view['setupComplete']:
        raise AssertionError('CI setup only operates on an uninitialized deployment; existing settings were not changed')
    reviewed = api('review', {'version': view['version'], 'acknowledgeHttp': True, 'changes': {
        'clamav.service.host': 'clamav-server', 'clamav.service.port': '3310',
        'app.upload.pathScan.enabled': 'true', 'app.upload.pathScan.fallbackToInstream': 'false'}})
    api('apply', {'reviewId': reviewed['reviewId'], 'acknowledgeHttp': True})
    api('start', {})
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + '/login', timeout=5) as response:
                if response.status == 200 and b'name="username"' in response.read():
                    print('PASS: fresh Compose setup and application startup over direct HTTP, without proxy headers')
                    return
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise AssertionError('Main application did not start after CI setup')


if __name__ == '__main__':
    main()
