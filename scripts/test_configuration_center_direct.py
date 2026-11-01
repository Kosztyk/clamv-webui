#!/usr/bin/env python3
"""Real-browser regression: configure and administer the packaged app over direct HTTP."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import tempfile
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('configuration_smoke', ROOT / 'tools/verify-configuration-center.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT / 'target/clamav-web-client-2.1.0.jar')
    parser.add_argument('--chromium', default=os.environ.get('CHROMIUM_PATH'))
    args = parser.parse_args()
    total = 0

    def check(value, description):
        nonlocal total
        if not value:
            raise AssertionError(description)
        total += 1
        print(f'PASS {total}: {description}', flush=True)

    with tempfile.TemporaryDirectory(prefix='configuration-direct-browser-') as directory:
        work = Path(directory)
        app = smoke.Application(args.jar.resolve(), work, forwarded=False)
        try:
            app.wait('/setup/')
            # Use a different spelling of the actual listening host to check that
            # the interface follows the opened origin instead of a configured URL.
            base = f'http://localhost:{app.port}'
            with sync_playwright() as runtime:
                browser = runtime.chromium.launch(executable_path=args.chromium,
                    args=['--no-sandbox', '--disable-dev-shm-usage'])
                context = browser.new_context(viewport={'width': 1440, 'height': 1000}, color_scheme='light')
                page = context.new_page()
                errors = []
                addresses = []
                forwarded = []
                payloads = []
                page.on('pageerror', lambda error: errors.append(str(error)))

                def record(request):
                    addresses.append(request.url)
                    if request.post_data:
                        payloads.append(request.post_data)
                    if any(key.startswith('x-forwarded-') or key == 'forwarded' for key in request.headers):
                        forwarded.append(request.url)

                page.on('request', record)
                page.goto(base + '/setup/')
                expect(page.get_by_role('heading', name='Unlock setup & recovery')).to_be_visible()
                check(not (work / 'data/clamav-web-client.mv.db').exists(), 'First-run page opens before database creation')
                credential = (work / 'conf/configuration-recovery-token').read_text().strip()
                page.get_by_label('Recovery credential', exact=True).fill('deliberately-invalid')
                page.get_by_role('button', name='Unlock configuration').click()
                expect(page.locator('.cc-message')).to_contain_text('does not match this application instance')
                check(True, 'Wrong recovery credential has a distinct, useful message')
                page.get_by_label('Recovery credential', exact=True).fill(credential)
                page.get_by_role('button', name='Unlock configuration').click()
                expect(page.get_by_role('tab', name='Connections', exact=True)).to_be_visible()
                check(page.get_by_role('tab').count() == 7, 'Owner unlock works directly at the opened HTTP origin')
                acknowledgement = page.get_by_label('Allow configuration over HTTP on this trusted network', exact=True)
                check(acknowledgement.is_visible() and not acknowledgement.is_checked(), 'HTTP transport choice is explicit and starts unchecked')
                check(page.locator('[data-section="rest"] .cc-card-body').count() == 0, 'Optional REST integration is disabled and has no populated URL')
                page.get_by_label('ClamAV host or Unix socket path', exact=True).fill('127.0.0.1')
                page.get_by_label('ClamAV port', exact=True).fill('9')
                page.get_by_role('button', name='Edit Database & persistence', exact=True).click()
                private_password = 'direct-browser-database-private-password'
                page.get_by_label('Database password', exact=True).fill(private_password)
                page.get_by_role('button', name='Review changes', exact=False).click()
                expect(page.locator('.cc-message')).to_contain_text('Allow configuration over HTTP')
                check(page.get_by_role('dialog').count() == 0, 'Unconfirmed HTTP credential submission is blocked with an actionable choice')
                check(not any(private_password in body for body in payloads), 'The browser sends no draft password before HTTP confirmation')
                acknowledgement.check()
                page.get_by_role('button', name='Review changes', exact=False).click()
                expect(page.get_by_role('dialog')).to_be_visible()
                check(private_password not in page.get_by_role('dialog').inner_text(), 'Direct HTTP review masks the database password')
                page.get_by_role('button', name='Apply reviewed changes', exact=True).click()
                expect(page.get_by_role('dialog')).to_contain_text('Confirm the database backup')
                check(True, 'Database acknowledgement remains mandatory')
                page.get_by_role('dialog').locator('input[type="checkbox"]').check()
                page.get_by_role('button', name='Apply reviewed changes', exact=True).click()
                expect(page.get_by_role('dialog')).to_have_count(0)
                check(private_password not in (work / 'conf/configuration-center.properties').read_text(), 'Direct HTTP setup saves the database password encrypted at rest')
                check(not (work / 'data/clamav-web-client.mv.db').exists(), 'Saving setup does not initialize or migrate the database')
                page.get_by_role('button', name='Start application', exact=True).click()
                page.wait_for_url('**/login', timeout=100000)
                check(True, 'First-run HTTP wizard hands off to the normal application without a proxy')
                page.locator('form[action$="/login"] input[name="username"]').fill('admin')
                page.locator('form[action$="/login"] input[name="password"]').fill(smoke.ADMIN_PASSWORD)
                page.get_by_role('button', name='Sign in locally', exact=True).click()
                page.wait_for_url('**/dashboard', timeout=20000)
                check(True, 'Local administrator signs in directly over HTTP')
                page.goto(base + '/admin/configuration-center')
                expect(page.get_by_role('tab', name='Connections', exact=True)).to_be_visible()
                acknowledgement = page.get_by_label('Allow configuration over HTTP on this trusted network', exact=True)
                check(not acknowledgement.is_checked(), 'HTTP confirmation is page-scoped rather than a persisted security bypass')
                acknowledgement.check()
                page.get_by_role('tab', name='Scanning', exact=True).click()
                page.get_by_label('Concurrent scan workers', exact=True).fill('3')
                page.get_by_role('tab', name='Monitoring', exact=True).click()
                page.get_by_role('switch', name='Prometheus & Grafana', exact=True).check()
                private_token = 'direct-browser-metrics-token-01234567890123456789'
                page.get_by_label('Scrape token', exact=True).fill(private_token)
                page.get_by_role('button', name='Review changes', exact=False).click()
                expect(page.get_by_role('dialog')).to_be_visible()
                expect(page.get_by_label('Local administrator password', exact=True)).to_be_visible()
                check(private_token not in page.get_by_role('dialog').inner_text(), 'Sensitive HTTP administration masks credentials and requests local verification')
                page.get_by_label('Local administrator password', exact=True).fill('wrong')
                page.get_by_role('button', name='Apply reviewed changes', exact=True).click()
                expect(page.get_by_role('dialog')).to_contain_text('Administrator verification failed')
                check(True, 'HTTP permission does not bypass local administrator verification')
                page.get_by_label('Local administrator password', exact=True).fill(smoke.ADMIN_PASSWORD)
                page.get_by_role('button', name='Apply reviewed changes', exact=True).click()
                expect(page.get_by_role('dialog')).to_have_count(0)
                expect(page.locator('.cc-restart')).to_contain_text('Concurrent scan workers')
                check(True, 'Worker changes stay staged for restart after direct HTTP administration')
                response = context.request.get(base + '/actuator/prometheus', headers={'Authorization': 'Bearer ' + private_token})
                check(response.status == 200 and 'clamav_scan_workers_capacity' in response.text(), 'Prometheus configuration applies live through its existing separate scrape gate')
                response = context.request.get(base + '/api/v1/health/live', headers={'Authorization': 'Bearer deliberately-invalid'})
                check(response.status == 403 and 'tls_required' in response.text(), 'API bearer authentication still rejects direct HTTP')
                page.get_by_role('tab', name='Connections', exact=True).click()
                page.locator('label[for="themeToggle"]').click()
                check(page.locator('html').get_attribute('data-theme') == 'dark', 'Direct setup interface follows the existing dark theme')
                page.set_viewport_size({'width': 390, 'height': 844})
                page.get_by_role('combobox', name='Connection method', exact=True).select_option('unix')
                check(page.get_by_label('ClamAV port', exact=True).count() == 0, 'Socket selection hides TCP-only fields on a mobile viewport')
                page.get_by_role('combobox', name='Connection method', exact=True).select_option('tcp')
                check(page.evaluate('document.documentElement.scrollWidth <= innerWidth'), 'Direct HTTP interface fits a mobile viewport')
                check(not forwarded, 'Browser sends no forwarded headers during the direct workflow')
                check(all(url.startswith(base + '/') for url in addresses if url.startswith(('http:', 'https:'))),
                      'All browser requests stay on the actual application origin, without a homelab or proxy address')
                check(not errors, 'No JavaScript exceptions occur during the complete direct workflow')
                browser.close()
        finally:
            app.close()
    print(f'All {total} direct HTTP browser checks passed.', flush=True)


if __name__ == '__main__':
    main()
