#!/usr/bin/env python3
"""Real packaged HTTP/scheduler checks with a silent TCP peer; temporary conf/data only."""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import re
import socketserver
import tempfile
import threading
import time
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location('configuration_runtime', Path(__file__).with_name('verify-configuration-center.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class Daemon(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, silent):
        self.silent = silent
        self.entered = threading.Event()
        self.lock = threading.Lock()
        self.active = 0
        self.closed = 0
        super().__init__(('127.0.0.1', 0), Handler)
        self.worker = threading.Thread(target=self.serve_forever, daemon=True)
        self.worker.start()

    @property
    def port(self):
        return self.server_address[1]

    def close(self):
        self.shutdown()
        self.server_close()
        self.worker.join(timeout=2)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(12)
        command = bytearray()
        while True:
            byte = self.request.recv(1)
            if not byte or byte in (b'\0', b'\n'):
                break
            command += byte
        if self.server.silent:
            with self.server.lock:
                self.server.active += 1
            self.server.entered.set()
            try:
                while self.request.recv(8192):
                    pass
            finally:
                with self.server.lock:
                    self.server.active -= 1
                    self.server.closed += 1
            return
        stamp = datetime.now(timezone.utc).strftime('%a %b %d %H:%M:%S %Y')
        response = {
            b'zPING': b'PONG\0',
            b'zVERSION': f'ClamAV 1.4.6/27920/{stamp}\0'.encode(),
            b'zSTATS': b'POOLS: 1\nTHREADS: live 2 idle 2 max 10\nQUEUE: 0 items\nEND\0',
        }.get(bytes(command), b'UNKNOWN COMMAND ERROR\0')
        self.request.sendall(response)


def wait_until(action, seconds):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if action():
            return True
        time.sleep(0.05)
    return False


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build with mvn verify or supply --jar')
    results = []

    def check(condition, description, **evidence):
        if not condition:
            raise AssertionError(description)
        results.append(dict(description=description, **evidence))
        print('PASS:', description, json.dumps(evidence) if evidence else '', flush=True)

    silent, healthy = Daemon(True), Daemon(False)
    app = None
    try:
        with tempfile.TemporaryDirectory(prefix='clamav-timeout-acceptance-') as directory:
            folder = Path(directory)
            config = folder / 'clamd.conf'
            config.write_text(f'TCPSocket {silent.port}\nTCPAddr 127.0.0.1\n')
            app = runtime.Application(jar, folder, forwarded=False, defer_polls=False, extra=[
                '--spring.datasource.url=jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
                '--clamav.config.poll-ms=1000', f'--clamav.config.clamd-path={config}',
                '--clamav.metrics.clamd-initial-delay-ms=5000',
            ])
            try:
                app.wait('/login')
                app.login()
                check(silent.entered.wait(8), 'Scheduled metrics reaches a real TCP peer that never answers')
                check(silent.active > 0, 'Metrics probe remains pending before its five-second deadline')
                primary = json.loads(app.request('/api/endpoints')[1])[0]['id']
                config.write_text(f'TCPSocket {healthy.port}\nTCPAddr 127.0.0.1\n')
                start = time.monotonic()
                changed = wait_until(lambda: re.search(rb'<td[^>]*>\s*' + str(healthy.port).encode() + rb'\s*</td>',
                                     app.request('/admin/endpoints')[1]) is not None, 3)
                elapsed = time.monotonic() - start
                check(changed and silent.active > 0,
                      'Actual scheduled configuration polling updates the endpoint while metrics is still blocked', seconds=round(elapsed, 3))
                start = time.monotonic()
                status, body, _ = app.request('/api/v1/health/clamd?endpointId=' + str(primary))
                check(status == 200 and json.loads(body)['connectivity'] == 'UP' and silent.active > 0,
                      'Healthy endpoint and servlet requests remain available during the blocked metrics poll',
                      seconds=round(time.monotonic() - start, 3))

                def form(path, values):
                    request = urllib.request.Request(app.base + path,
                        data=urllib.parse.urlencode(values).encode(),
                        headers={'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-TOKEN': app.csrf})
                    with app.opener.open(request, timeout=8) as response:
                        return response.status, response.read()

                check(form('/admin/endpoints/create', {'name': 'silent API fixture', 'host': '127.0.0.1',
                      'port': silent.port, 'platform': 'UNIX', 'enabled': 'true'})[0] == 200,
                      'Administrator creates an independent silent endpoint for HTTP deadline checks')
                endpoint = next(item['id'] for item in json.loads(app.request('/api/endpoints')[1]) if item['name'] == 'silent API fixture')
                for path, expected in [('/api/clamdtop', 200), ('/api/v1/health/clamd', 503), ('/main', 200)]:
                    start = time.monotonic()
                    status, content, _ = app.request(path + '?endpointId=' + str(endpoint))
                    elapsed = time.monotonic() - start
                    failed = (json.loads(content).get('ok') is False if path == '/api/clamdtop' else
                              json.loads(content).get('connectivity') == 'DOWN' if path == '/api/v1/health/clamd' else
                              b'ClamD operation timed out.' in content)
                    check(status == expected and failed and 4.5 <= elapsed < 6.5,
                          path + ' returns a bounded failure using the default five-second budget', seconds=round(elapsed, 3))
                check(wait_until(lambda: silent.active == 0 and silent.closed >= 4, 1),
                      'Timed-out metrics and HTTP probes close their real TCP connections', closed_connections=silent.closed)
                check(app.request('/api/v1/health/live')[0] == 200, 'Application remains live after the failed probes')
            except Exception:
                print((folder / 'process.log').read_text(errors='replace')[-4000:], flush=True)
                raise
            finally:
                app.close()
                app = None
    finally:
        if app:
            app.close()
        silent.close()
        healthy.close()
    result = {'result': 'passed', 'checks': len(results), 'runner': 'tools/verify-clamd-timeouts.py',
              'command_budget_seconds': 5, 'scheduler_default_threads': 4,
              'daemon': 'Synthetic TCP peers; this test does not scan malware', 'assertions': results}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)


if __name__ == '__main__':
    main()
