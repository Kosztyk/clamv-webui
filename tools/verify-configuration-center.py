#!/usr/bin/env python3
"""Packaged-JAR acceptance checks. Uses temporary conf/data, never deployment volumes."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

ADMIN_PASSWORD = 'PackagedConfigurationCenterTestAdmin456'
SECRET = 'packaged-synthetic-db-password'
checks = 0


def check(condition, description):
    global checks
    if not condition:
        raise AssertionError(description)
    checks += 1
    print(f'PASS {checks}: {description}', flush=True)


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


class Application:
    def __init__(self, jar, folder, extra=(), forwarded=True):
        self.folder = folder
        self.forwarded = forwarded
        self.port = free_port()
        self.base = f'http://127.0.0.1:{self.port}'
        class LoopbackProxyRedirects(urllib.request.HTTPRedirectHandler):
            def redirect_request(handler, request, fp, code, message, headers, new_url):
                selected=urllib.parse.urlsplit(new_url)
                # The loopback transport simulates the HTTP side of a TLS edge.
                # Follow its canonical HTTPS redirects on that same private socket.
                if selected.scheme == 'https' and selected.hostname == '127.0.0.1':
                    new_url=urllib.parse.urlunsplit(selected._replace(scheme='http',netloc=f'127.0.0.1:{self.port}'))
                return super().redirect_request(request,fp,code,message,headers,new_url)
        class LoopbackProxyCookies(http.cookiejar.DefaultCookiePolicy):
            def return_ok_secure(policy,cookie,request):
                return urllib.parse.urlsplit(request.full_url).hostname == '127.0.0.1' or super().return_ok_secure(cookie,request)
        self.opener = (urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar(LoopbackProxyCookies())),LoopbackProxyRedirects())
                       if forwarded else urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar())))
        environment = {key: value for key, value in os.environ.items()
                       if not key.startswith(('CLAMAV_', 'SPRING_', 'SERVER_', 'MANAGEMENT_', 'API_V1_'))
                       and key not in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS')}
        self.log = (folder / 'process.log').open('ab')
        self.process = subprocess.Popen(['java', '-jar', str(jar), f'--server.port={self.port}',
            '--server.address=127.0.0.1', '--clamav.config.initial-delay-ms=3600000',
            '--clamav.metrics.clamd-initial-delay-ms=3600000',
            f'--clamav.bootstrap.admin.password={ADMIN_PASSWORD}', *extra], cwd=folder,
            env=environment, stdout=self.log, stderr=subprocess.STDOUT)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.log.close()

    def request(self, path, payload=None, token=False, secure=None, origin=None, headers=None):
        selected = dict(headers or {})
        if self.forwarded if secure is None else secure:
            selected['X-Forwarded-Proto'] = 'https'
        if origin:
            selected['Origin'] = origin
        if token:
            selected['Authorization'] = 'Bearer ' + (self.folder / 'conf/configuration-recovery-token').read_text().strip()
        data = None
        if payload is not None:
            selected['Content-Type'] = 'application/json'
            data = json.dumps(payload).encode()
        request = urllib.request.Request(self.base + path, data=data, headers=selected)
        try:
            with self.opener.open(request, timeout=10) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as response:
            return response.code, response.read(), response.headers

    def wait(self, path, expected=200, timeout=100):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise AssertionError('Application process stopped; inspect the temporary process.log')
            try:
                response = self.request(path)
                if response[0] == expected:
                    return response
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(0.3)
        raise AssertionError(f'Timed out waiting for {path}')

    def api(self, action, payload, **options):
        status, content, headers = self.request('/setup/api/' + action, payload, token=True, **options)
        return status, json.loads(content)

    def login(self):
        _, page, _ = self.request('/login')
        csrf = re.search(rb'name="_csrf"[^>]*value="([^"]+)"', page).group(1).decode()
        form = urllib.parse.urlencode({'username': 'admin', 'password': ADMIN_PASSWORD, '_csrf': csrf}).encode()
        request = urllib.request.Request(self.base + '/login', data=form,
            headers=dict({'Content-Type': 'application/x-www-form-urlencoded'}, **({'X-Forwarded-Proto': 'https'} if self.forwarded else {})))
        with self.opener.open(request, timeout=15) as response:
            page = response.read()
        self.csrf = re.search(rb'<meta name="_csrf" content="([^"]+)"', page).group(1).decode()

    def admin(self, action, payload=None):
        status, content, _ = self.request('/admin/configuration-center/api/' + action, payload,
                                        headers={'X-CSRF-TOKEN': self.csrf})
        return status, json.loads(content)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build the JAR with mvn verify first, or supply --jar')
    with tempfile.TemporaryDirectory(prefix='clamav-configuration-acceptance-') as directory:
        folder = Path(directory)
        app = Application(jar, folder)
        try:
            app.wait('/setup/')
            check(not (folder / 'data/clamav-web-client.mv.db').exists(), 'First-run setup opens no database')
            check(app.request('/actuator/health')[0] == 503, 'Uninitialized application health returns 503')
            check(app.request('/setup/api/view', {})[0] == 401, 'Recovery API requires its separate owner credential')
            check(app.api('view', {}, origin='https://attacker.example')[0] == 403, 'Cross-origin recovery mutation is denied')
            status, view = app.api('view', {})
            check(status == 200 and len(view['categories']) == 7, 'Authenticated setup exposes the seven real categories')
            draft = {'version': view['version'], 'changes': {'clamav.service.host': '127.0.0.1',
                     'clamav.service.port': '9', 'spring.datasource.password': SECRET}}
            check(app.api('review', draft, secure=False)[0] == 400, 'Unconfirmed HTTP cannot submit a new credential')
            status, reviewed = app.api('review', draft)
            check(status == 200 and SECRET not in json.dumps(reviewed), 'Review accepts trusted forwarded HTTPS and masks credentials')
            status, result = app.api('apply', {'reviewId': reviewed['reviewId'], 'acknowledgeDatabase': True})
            check(status == 200, 'Initial configuration commits before Spring DataSource initialization')
            stored = (folder / 'conf/configuration-center.properties').read_text()
            check(SECRET not in stored and 'cc1' in stored, 'Persisted database password is encrypted')
            if os.name == 'posix':
                check((folder / 'conf/configuration-center.key').stat().st_mode & 0o077 == 0, 'Encryption key is owner-private')
            check(app.api('start', {})[0] == 200, 'Application starts only after an explicit setup action')
            app.wait('/login')
            check((folder / 'data/clamav-web-client.mv.db').exists(), 'Spring starts with the GUI-selected H2 configuration')
            app.login()
            status, live = app.admin('view')
            check(status == 200, 'Existing local administrator can open Configuration Center')
            check(app.request('/api/v1/health/live', headers={'Authorization': 'Bearer deliberately-invalid'})[0] == 401,
                  'Forwarded HTTPS reaches bearer token validation')
            check(app.request('/api/v1/health/live', secure=False, headers={'Authorization': 'Bearer deliberately-invalid'})[0] == 403,
                  'Plain direct HTTP still rejects bearer tokens')
            status, reviewed = app.admin('review', {'version': live['version'], 'changes': {'app.concurrentScans': '3'}})
            check(status == 200, 'Administrator reviews a startup-bound change')
            status, result = app.admin('apply', {'reviewId': reviewed['reviewId']})
            workers = next(field for field in result['view']['fields'] if field['definition']['key'] == 'app.concurrentScans')
            check(status == 200 and workers['value'] == '3' and workers['activeValue'] == '2' and workers['pendingRestart'],
                  'Saved worker capacity is staged while the running executor retains its original value')
            check(app.api('view', {})[0] == 200, 'Recovery remains available alongside the running application')
        finally:
            app.close()
        app = Application(jar, folder)
        try:
            app.wait('/login')
            app.login()
            _, view = app.admin('view')
            workers = next(field for field in view['fields'] if field['definition']['key'] == 'app.concurrentScans')
            check(workers['activeValue'] == '3' and not workers['pendingRestart'], 'Recreated process activates saved settings without build flags')
            check(SECRET not in json.dumps(view), 'Active credentials stay absent from the complete administration response')
        finally:
            app.close()
        direct = folder / 'direct-http'
        direct.mkdir()
        app = Application(jar, direct, forwarded=False)
        try:
            app.wait('/setup/')
            check(not (direct / 'data/clamav-web-client.mv.db').exists(), 'Direct HTTP first-run setup waits before opening a database')
            status, view = app.api('view', {}, origin=app.base)
            check(status == 200, 'Owner can unlock a published HTTP address without TLS or forwarded headers')
            draft = {'version': view['version'], 'changes': {'spring.datasource.password': SECRET,
                     'clamav.service.host': '127.0.0.1', 'clamav.service.port': '9'}}
            check(app.api('review', draft, origin=app.base)[0] == 400, 'HTTP credential submission requires explicit transport confirmation')
            draft['acknowledgeHttp'] = True
            status, reviewed = app.api('review', draft, origin=app.base)
            check(status == 200 and SECRET not in json.dumps(reviewed), 'Confirmed direct HTTP credentials can be reviewed without echoing them')
            status, saved = app.api('apply', {'reviewId': reviewed['reviewId'], 'acknowledgeDatabase': True,
                                           'acknowledgeHttp': True}, origin=app.base)
            check(status == 200 and SECRET not in (direct / 'conf/configuration-center.properties').read_text(),
                  'Direct HTTP setup persists encrypted database credentials')
            check(app.api('start', {}, origin=app.base)[0] == 200, 'Direct HTTP setup explicitly hands off to Spring')
            app.wait('/login')
            app.login()
            status, view = app.admin('view')
            check(status == 200, 'Local administrator login and Configuration Center work directly over HTTP')
            status, reviewed = app.admin('review', {'version': view['version'], 'acknowledgeHttp': True,
                'changes': {'CLAMAV_REST_API_KEY': 'direct-http-administration-private-key'}})
            status, saved = app.admin('apply', {'reviewId': reviewed['reviewId'], 'acknowledgeHttp': True,
                'verificationUsername': 'admin', 'verificationPassword': ADMIN_PASSWORD})
            check(status == 200, 'HTTP administration retains local-administrator re-verification for sensitive saves')
            check(app.request('/api/v1/health/live', headers={'Authorization': 'Bearer deliberately-invalid'})[0] == 403,
                  'HTTP configuration permission never bypasses API bearer HTTPS enforcement')
        finally:
            app.close()
        app = Application(jar, direct, forwarded=False)
        try:
            app.wait('/login')
            app.login()
            status, view = app.admin('view')
            check(status == 200 and 'direct-http-administration-private-key' not in json.dumps(view),
                  'Direct HTTP configuration survives recreation and remains masked')
        finally:
            app.close()
        outage = folder / 'outage'
        outage.mkdir()
        app = Application(jar, outage, ('--spring.datasource.url=jdbc:postgresql://127.0.0.1:9/unavailable?connectTimeout=1',
                                       '--spring.datasource.username=unavailable', '--spring.datasource.password=synthetic'))
        try:
            app.wait('/setup/')
            check(not (outage / 'data/clamav-web-client.mv.db').exists(), 'External database outage never creates a fallback H2 database')
            check(app.request('/actuator/health')[0] == 503, 'Recovery mode reports application DOWN to health probes')
            status, view = app.api('view', {})
            database = next(field for field in view['fields'] if field['definition']['key'] == 'spring.datasource.url')
            check(status == 200 and database['locked'] and database['value'].startswith('jdbc:postgresql:'),
                  'Database-independent recovery retains and locks the deployment-selected database')
        finally:
            app.close()
        app = Application(jar, outage)
        try:
            app.wait('/setup/')
            status, view = app.api('view', {})
            check(status == 200 and view['databaseChoiceRequired'], 'Removing external database deployment settings demands an explicit database choice')
            check(app.api('start', {})[0] == 400, 'Start cannot bypass recovery database safeguards')
            check(not (outage / 'data/clamav-web-client.mv.db').exists(), 'Missing deployment configuration also leaves H2 unopened')
        finally:
            app.close()
    print(f'All {checks} packaged application checks passed.', flush=True)


if __name__ == '__main__':
    main()
