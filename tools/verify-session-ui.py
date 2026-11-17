#!/usr/bin/env python3
"""Focused Chromium checks for Users actions and session settings in direct mode."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from playwright.sync_api import sync_playwright, expect

spec = importlib.util.spec_from_file_location('session_runtime', Path(__file__).with_name('verify-session-revocation.py'))
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=Path('target/clamav-web-client-2.1.0.jar'))
    parser.add_argument('--chromium', default=os.environ.get('CHROMIUM_PATH'))
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    checks = []
    def check(condition, message):
        if not condition:
            raise AssertionError(message)
        checks.append(message)
        print(f'PASS {len(checks)}: {message}', flush=True)
    with tempfile.TemporaryDirectory(prefix='clamav-session-ui-') as temporary:
        folder = Path(temporary)
        app = runtime.start(args.jar.resolve(), folder)
        try:
            with sync_playwright() as pw:
                browser = pw.chromium.launch(executable_path=args.chromium, args=['--no-sandbox', '--disable-dev-shm-usage'])
                admin_context = browser.new_context(viewport={'width': 1440, 'height': 1000})
                victim_context = browser.new_context(viewport={'width': 1440, 'height': 1000})
                admin, victim = admin_context.new_page(), victim_context.new_page()
                errors = []
                for page in (admin, victim):
                    page.on('pageerror', lambda error: errors.append(str(error)))
                def login(page, username, password):
                    page.goto(app.base + '/login')
                    form = page.locator('form[action$="/login"]')
                    form.locator('[name="username"]').fill(username)
                    form.locator('[name="password"]').fill(password)
                    form.get_by_role('button', name='Sign in locally', exact=True).click()
                    page.wait_for_url('**/dashboard')
                login(admin, 'admin', runtime.runtime.ADMIN_PASSWORD)
                admin.goto(app.base + '/admin/users')
                form = admin.locator('form[action$="/admin/users/create"]')
                form.locator('[name="username"]').fill('session-browser-user')
                form.locator('[name="password"]').fill(runtime.PASSWORD)
                form.locator('[name="role"]').select_option('ADMIN')
                form.get_by_role('button', name='Create user', exact=True).click()
                row = admin.get_by_role('row').filter(has=admin.get_by_role('cell', name='session-browser-user', exact=True))
                expect(row).to_be_visible()
                check('ROLE_ADMIN' in row.inner_text(), 'Users form creates an enabled local administrator')
                login(victim, 'session-browser-user', runtime.PASSWORD)
                check(victim.request.get(app.base + '/api/v1/health/live').status == 200, 'Independent browser session reaches protected API')
                row.locator('summary').click()
                row.locator('form[action$="/access"] [name="role"]').select_option('VIEWER')
                row.get_by_role('button', name='Save access', exact=True).click()
                expect(row).not_to_contain_text('ROLE_ADMIN')
                check(victim.request.get(app.base + '/api/v1/health/live').status == 401, 'Saving demotion in the UI invalidates the other browser session')
                login(victim, 'session-browser-user', runtime.PASSWORD)
                check(victim.request.get(app.base + '/admin/users').status == 403, 'Fresh demoted browser cannot open administration')
                row.get_by_role('button', name='Revoke sessions', exact=True).click()
                check(victim.request.get(app.base + '/api/v1/health/live').status == 401, 'UI Revoke sessions invalidates the signed-in viewer')
                login(victim, 'session-browser-user', runtime.PASSWORD)
                row.locator('[name="newPassword"]').fill(runtime.NEW_PASSWORD)
                row.get_by_role('button', name='Reset password', exact=True).click()
                check(victim.request.get(app.base + '/api/v1/health/live').status == 401, 'UI password reset invalidates the existing browser session')
                login(victim, 'session-browser-user', runtime.NEW_PASSWORD)
                check(victim.request.get(app.base + '/api/v1/health/live').status == 200, 'Updated password creates a fresh browser session')
                expect(admin.get_by_text('No external users have signed in.', exact=True)).to_be_visible()
                check(True, 'External session administration has an explicit empty state')
                admin.goto(app.base + '/admin/configuration-center')
                admin.get_by_role('tab', name='Network', exact=True).click()
                card = admin.locator('[data-section="proxy"]')
                check(not card.get_by_role('switch').is_checked(), 'Direct deployment keeps forwarded-header trust disabled')
                card.get_by_role('button', name='Edit Reverse proxy & session security', exact=True).click()
                card.locator('summary').click()
                idle = card.locator('[data-key="server.servlet.session.timeout"] input')
                absolute = card.locator('[data-key="clamav.security.session.max-age"] input')
                expect(idle).to_be_visible(); expect(absolute).to_be_visible()
                check(idle.input_value() == '30m' and absolute.input_value() == '1h', 'Idle and absolute lifetime controls show the safe defaults')
                absolute.fill('2h')
                check(not card.get_by_role('switch').is_checked(), 'Editing lifetime does not enable forwarded-header trust')
                check(not errors, 'Users and Configuration Center interactions produce no browser script errors')
                browser.close()
        finally:
            app.close()
    result = {'checks': len(checks), 'passed': len(checks), 'assertions': checks,
              'scope': 'Headless Chromium, real packaged JAR/direct HTTP/H2, two isolated browser contexts; no external identity server.'}
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({'checks': len(checks), 'passed': len(checks)}))


if __name__ == '__main__':
    main()
