#!/usr/bin/env python3
"""Packaged token-root authorization checks; isolated H2/files and a synthetic ClamD peer."""
import argparse
import importlib.util
import json
from pathlib import Path
import socketserver
import struct
import tempfile
import threading
import time
import urllib.error
import urllib.request

spec = importlib.util.spec_from_file_location('configuration_runtime', Path(__file__).with_name('verify-configuration-center.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class Peer(socketserver.StreamRequestHandler):
    def handle(self):
        self.request.settimeout(10)
        command = bytearray()
        while True:
            byte = self.rfile.read(1)
            if not byte or byte in (b'\0', b'\n'):
                break
            command += byte
        if command.startswith(b'zMULTISCAN '):
            path = command.split(b' ', 1)[1].decode()
            signature = 'Fixture.A' if '/a/' in path else 'Fixture.B' if '/b/' in path else 'Fixture.Upload'
            response = f'{path}: {signature} FOUND\0'.encode()
        elif command == b'zINSTREAM':
            while True:
                length = struct.unpack('!I', self.rfile.read(4))[0]
                if not length:
                    break
                if len(self.rfile.read(length)) != length:
                    raise AssertionError('Truncated synthetic upload')
            response = b'stream: Fixture.Upload FOUND\0'
        else:
            response = {b'zPING': b'PONG\0', b'zVERSION': b'ClamAV fixture/1/date\0',
                        b'zSTATS': b'POOLS: 1\nQUEUE: 0 items\nEND\0'}.get(bytes(command), b'UNKNOWN COMMAND ERROR\0')
        self.wfile.write(response)


class Daemon(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build with mvn verify or supply --jar')
    checks = []

    def check(condition, message, **evidence):
        if not condition:
            raise AssertionError(message)
        checks.append(dict(description=message, **evidence))
        print('PASS:', message, flush=True)

    with Daemon(('127.0.0.1', 0), Peer) as daemon, tempfile.TemporaryDirectory(prefix='clamav-token-scope-') as temporary:
        worker = threading.Thread(target=daemon.serve_forever, daemon=True)
        worker.start()
        folder = Path(temporary)
        scan = folder / 'scandir'
        a, b = scan / 'a', scan / 'b'
        a.mkdir(parents=True); b.mkdir()
        (a / 'sample.txt').write_text('Synthetic tenant A fixture')
        (b / 'sample.txt').write_text('Synthetic tenant B fixture')
        alias = scan / 'alias-a'
        alias.symlink_to(a, target_is_directory=True)
        app = runtime.Application(jar, folder, extra=[
            '--spring.datasource.url=jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
            '--clamav.service.host=127.0.0.1', f'--clamav.service.port={daemon.server_address[1]}',
            f'--app.allowedScanRoots={scan}',
        ])
        token_http = urllib.request.build_opener(NoRedirect())
        try:
            app.wait('/login'); app.login()
            endpoint = json.loads(app.request('/api/endpoints')[1])[0]['id']

            def issue(label, roots, scopes):
                status, body, _ = app.request('/api/v1/tokens', {'label': label, 'scopes': scopes,
                    'endpointIds': [endpoint], 'pathRoots': [str(root) for root in roots]}, headers={'X-CSRF-TOKEN': app.csrf})
                check(status == 201, 'Administrator issues ' + label)
                return json.loads(body)

            scopes = ['scan:path', 'scan:upload', 'jobs:read', 'audit:read']
            first = issue('tenant A', [alias], scopes)
            second = issue('tenant B', [b], scopes)
            broad = issue('endpoint history', [], ['jobs:read', 'audit:read'])
            check(first['token']['pathRoots'] == str(a.resolve()), 'Alias root is returned and stored as the canonical directory')

            def request(path, token, payload=None, raw=None, content_type=None):
                data = json.dumps(payload).encode() if payload is not None else raw
                headers = {'Authorization': 'Bearer ' + token['secret'], 'X-Forwarded-Proto': 'https'}
                if data is not None:
                    headers['Content-Type'] = content_type or 'application/json'
                selected = urllib.request.Request(app.base + path, data=data, headers=headers)
                try:
                    with token_http.open(selected, timeout=10) as response:
                        return response.status, response.read()
                except urllib.error.HTTPError as response:
                    return response.code, response.read()

            def submit(token, path):
                status, body = request('/api/v1/scans/paths', token, {'path': str(path), 'endpointId': endpoint})
                check(status == 202, 'Scoped token submits a permitted canonical path')
                return json.loads(body)['jobId']

            def upload(token, name):
                boundary = 'clamav-scope-fixture-boundary'
                data = (f'--{boundary}\r\nContent-Disposition: form-data; name="files"; filename="{name}"\r\n'
                        f'Content-Type: text/plain\r\n\r\nSynthetic upload fixture\r\n--{boundary}--\r\n').encode()
                status, body = request('/api/v1/scans/uploads?endpointId=' + str(endpoint), token, raw=data,
                    content_type='multipart/form-data; boundary=' + boundary)
                check(status == 202, 'Scoped token submits its own upload')
                return json.loads(body)['jobIds'][0]

            a_job, b_job = submit(first, alias / 'sample.txt'), submit(second, b / 'sample.txt')
            a_upload, b_upload = upload(first, 'tenant-a.txt'), upload(second, 'tenant-b.txt')
            for token, job_id in [(first, a_job), (second, b_job), (first, a_upload), (second, b_upload)]:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    status, content = request('/api/v1/jobs/' + job_id, token)
                    job = json.loads(content)
                    if status == 200 and job.get('status') == 'FINISHED':
                        break
                    time.sleep(0.05)
                check(status == 200 and job.get('verdict') == 'VIRUS_FOUND',
                      'Authorized job remains readable after the synthetic worker result')

            status, content = request('/api/v1/jobs?size=25', first)
            page = json.loads(content)
            check(status == 200 and page['totalItems'] == 2 and {job['id'] for job in page['items']} == {a_job, a_upload},
                  'Root-scoped job list excludes another root and another token upload', visible_jobs=2)
            check(b'Fixture.B' not in content and b'tenant-b.txt' not in content,
                  'Hidden signatures and upload labels do not leak into the authorized list')
            pages = [json.loads(request('/api/v1/jobs?size=1&page=' + str(index), first)[1]) for index in (0, 1)]
            check(all(page['totalItems'] == 2 and len(page['items']) == 1 for page in pages)
                  and {page['items'][0]['id'] for page in pages} == {a_job, a_upload},
                  'Pagination totals and records contain only visible jobs')
            for hidden in (b_job, b_upload):
                for suffix in ('', '/progress'):
                    check(request('/api/v1/jobs/' + hidden + suffix, first)[0] == 403,
                          'Details/progress rejects a hidden path or foreign upload')
            check(json.loads(request('/api/v1/jobs?size=25', broad)[1])['totalItems'] == 4,
                  'Empty-root history credentials retain explicit endpoint-wide reads', visible_jobs=4)

            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                status, content = request('/api/v1/audit?size=100', first)
                audit = json.loads(content)
                if status == 200 and audit.get('totalItems', 0) >= 4:
                    break
                time.sleep(0.05)
            check(status == 200 and audit['totalItems'] >= 4 and all(event['jobId'] in {a_job, a_upload} for event in audit['items']),
                  'Linked audit records obey the same job visibility and exclude administrative events')
            alias.unlink(); alias.symlink_to(b, target_is_directory=True)
            status, _ = request('/api/v1/scans/paths', first, {'path': str(alias / 'sample.txt'), 'endpointId': endpoint})
            check(status == 403, 'Retargeting the original alias does not expand the pinned grant')
            check(request('/api/v1/jobs/' + a_job, first)[0] == 200,
                  'Existing canonical in-root history remains accessible after alias retargeting')
            check(request('/api/jobs', first)[0] in (401, 403), 'Bearer token cannot bypass v1 restrictions through legacy job routes')
            status, content, _ = app.request('/admin/api-tokens')
            check(status == 200 and b'Path roots' in content and b'Nonempty roots restrict' in content,
                  'Administrator token page explains and displays the effective root policy')
        except Exception:
            print((folder / 'process.log').read_text(errors='replace')[-4000:], flush=True)
            raise
        finally:
            app.close(); daemon.shutdown(); worker.join(timeout=2)
    result = {'result': 'passed', 'checks': len(checks), 'runner': 'tools/verify-token-path-scopes.py',
              'backend': 'Real packaged Spring application and H2 with synthetic ClamD verdicts',
              'transport': 'Loopback trusted native proxy-header fixture; no external TLS or daemon', 'assertions': checks}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
