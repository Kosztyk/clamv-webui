#!/usr/bin/env python3
"""Real packaged HTTP acceptance: isolated H2, real form logins and independent cookie jars."""
import argparse
import http.cookiejar
import importlib.util
import json
from pathlib import Path
import re
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

spec = importlib.util.spec_from_file_location('configuration_runtime', Path(__file__).with_name('verify-configuration-center.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)

PASSWORD = 'SessionAcceptanceUserPassword123'
NEW_PASSWORD = 'SessionAcceptanceNewPassword456'
checks = []


def check(condition, description):
    if not condition:
        raise AssertionError(description)
    checks.append(description)
    print(f'PASS {len(checks)}: {description}', flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, url):
        return None


class Browser:
    def __init__(self, base):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies), NoRedirect())
        self.csrf = None

    def request(self, path, form=None, csrf=True, method=None):
        if form is not None:
            form = dict(form)
            if csrf and self.csrf:
                form['_csrf'] = self.csrf
        request = urllib.request.Request(self.base + path,
            data=urllib.parse.urlencode(form).encode() if form is not None else None, method=method)
        try:
            with self.opener.open(request, timeout=15) as response:
                return response.status, response.read(), response.headers
        except urllib.error.HTTPError as response:
            return response.code, response.read(), response.headers

    def login(self, username, password):
        status, page, _ = self.request('/login')
        if status != 200:
            raise AssertionError('Login page must open')
        self.csrf = re.search(rb'name="_csrf"[^>]*value="([^"]+)"', page).group(1).decode()
        status, _, headers = self.request('/login', {'username': username, 'password': password})
        if status != 302 or not headers.get('Location', '').endswith('/dashboard'):
            return False
        status, page, _ = self.request('/api/v1/csrf')
        if status != 200:
            return False
        self.csrf = json.loads(page)['token']
        return True

    def live(self):
        return self.request('/api/v1/health/live')[0]


def user_id(admin, username):
    status, page, _ = admin.request('/admin/users')
    if status != 200:
        raise AssertionError('Admin user list must render')
    for row in re.findall(rb'<tr[^>]*>.*?</tr>', page, re.S):
        if ('>' + username + '<').encode() in row:
            found = re.search(rb'/admin/users/(\d+)/reset', row)
            if found:
                return found.group(1).decode()
    raise AssertionError('Created account not found on user page')


def create(admin, username, role='VIEWER'):
    status, _, _ = admin.request('/admin/users/create', {'username': username, 'password': PASSWORD,
                                                       'role': role, 'enabled': 'true'})
    if status != 302:
        raise AssertionError('Account creation failed')
    return user_id(admin, username)


def start(jar, folder, extra=()):
    app = runtime.Application(jar, folder, forwarded=False, extra=[
        '--spring.datasource.url=jdbc:h2:file:./data/session-acceptance;MODE=PostgreSQL;DB_CLOSE_DELAY=-1',
        '--clamav.service.host=127.0.0.1', '--clamav.service.port=9', *extra])
    app.wait('/login')
    return app


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    jar = args.jar.resolve()
    if not jar.is_file():
        parser.error('Build the JAR with mvn verify first, or supply --jar')
    with tempfile.TemporaryDirectory(prefix='clamav-session-acceptance-') as temp:
        folder = Path(temp)
        app = start(jar, folder)
        try:
            admin = Browser(app.base)
            check(admin.login('admin', runtime.ADMIN_PASSWORD), 'Real bootstrap administrator form login succeeds')
            control_id = create(admin, 'session-control')
            control = Browser(app.base)
            check(control.login('session-control', PASSWORD) and control.live() == 200, 'Independent control session authenticates')

            for name, action, form in [
                ('session-delete', 'delete', {}),
                ('session-reset', 'reset', {'newPassword': NEW_PASSWORD}),
                ('session-disable', 'access', {'role': 'VIEWER', 'enabled': 'false'}),
                ('session-demote', 'access', {'role': 'VIEWER', 'enabled': 'true'}),
                ('session-manual', 'sessions/revoke', {})]:
                account_id = create(admin, name, 'ADMIN' if action == 'access' and form['enabled'] == 'true' else 'VIEWER')
                first, second = Browser(app.base), Browser(app.base)
                if not first.login(name, PASSWORD) or not second.login(name, PASSWORD):
                    raise AssertionError('Two target sessions must authenticate before mutation')
                check(first.live() == second.live() == 200, f'{name}: both existing sessions access protected API before change')
                check(admin.request(f'/admin/users/{account_id}/{action}', form)[0] == 302, f'{name}: administrator action succeeds')
                check(first.live() == second.live() == 401, f'{name}: both sessions receive 401 on their next real HTTP request')
                check(admin.live() == control.live() == 200, f'{name}: other accounts remain authenticated')
                fresh = Browser(app.base)
                if action == 'reset':
                    check(not fresh.login(name, PASSWORD) and fresh.login(name, NEW_PASSWORD), 'Reset requires the new password for a fresh login')
                elif name == 'session-disable':
                    check(not fresh.login(name, PASSWORD), 'Disabled account cannot sign in again')
                    admin.request(f'/admin/users/{account_id}/access', {'role': 'VIEWER', 'enabled': 'true'})
                    check(fresh.login(name, PASSWORD), 'Re-enabled account can create a fresh session')
                elif name == 'session-demote':
                    check(fresh.login(name, PASSWORD) and fresh.request('/admin/users')[0] == 403,
                          'Demoted account receives current viewer permissions on fresh login')
                elif action == 'sessions/revoke':
                    check(fresh.login(name, PASSWORD), 'Manual revocation allows a fresh authentication')
                elif action == 'delete':
                    check(not fresh.login(name, PASSWORD), 'Deleted account cannot authenticate')

            target_id = create(admin, 'session-csrf')
            target = Browser(app.base)
            if not target.login('session-csrf', PASSWORD):
                raise AssertionError('CSRF target must sign in')
            check(admin.request(f'/admin/users/{target_id}/sessions/revoke', {}, csrf=False)[0] == 403 and target.live() == 200,
                  'Session revocation requires the administrator CSRF token')
            check(control.request(f'/admin/users/{target_id}/sessions/revoke', {})[0] == 403 and target.live() == 200,
                  'Viewer cannot revoke another account session')
            admin.request(f'/admin/users/{target_id}/reset', {'newPassword': NEW_PASSWORD})
            status, _, headers = target.request('/dashboard')
            check(status == 302 and headers.get('Location', '').endswith('/login?expired'),
                  'Revoked browser navigation redirects to the explanatory login page')
            root_id = user_id(admin, 'admin')
            # Only the bootstrap local admin remains after the demotion above.
            admin.request(f'/admin/users/{root_id}/access', {'role': 'VIEWER', 'enabled': 'false'})
            check(admin.request('/admin/users')[0] == 200 and admin.live() == 200,
                  'Last enabled local administrator cannot be disabled or demoted')
        finally:
            app.close()

        aged = folder / 'absolute-lifetime'
        aged.mkdir()
        app = start(jar, aged, ['--clamav.security.session.max-age=4s', '--server.servlet.session.timeout=30m'])
        try:
            browser = Browser(app.base)
            check(browser.login('admin', runtime.ADMIN_PASSWORD), 'Short absolute-lifetime runtime authenticates')
            deadline = time.monotonic() + 7
            observations = []
            while time.monotonic() < deadline:
                status = browser.live()
                observations.append(status)
                if status == 401:
                    break
                if status != 200:
                    raise AssertionError('Unexpected lifetime response')
                time.sleep(0.3)
            check(200 in observations and observations[-1] == 401,
                  'Four-second absolute expiry rejects an actively used session before the 30-minute idle limit')
        finally:
            app.close()
    report = {'checks': len(checks), 'passed': len(checks), 'assertions': checks,
              'scope': 'Packaged executable JAR, real Tomcat HTTP, isolated H2, real form logins and separate cookie jars; no external LDAP/OIDC server or Docker runtime.'}
    if args.output:
        args.output.resolve().parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'checks': len(checks), 'passed': len(checks)}))


if __name__ == '__main__':
    main()
