#!/usr/bin/env python3
"""Actual Chromium regression for LDAP/OIDC Test and Save endpoint routing.

Requires Playwright for Python and a Chromium executable (CHROMIUM_PATH optional).
Run `python3 scripts/test_authentication_form_routes.py`.

Runs the production authentication-management.js and actual form template markup,
with Thymeleaf URL attributes materialized and HTTP fetch mocked in the browser.
Tests both missing formaction fallback and explicit buttons. Does not contact a
live backend/LDAP server or use real credentials.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "src/main/resources/templates/pages/authentication.html"
SCRIPT = ROOT / "src/main/resources/static/js/authentication-management.js"
PAGE = "/admin/settings/authentication"
BASE = "https://clamav.test"


def materialized_html() -> str:
    template = TEMPLATE.read_text(encoding="utf-8")
    match = re.search(r'(<section\b[^>]*class="auth-admin-page"[^>]*>.*?</section>)', template, re.S)
    assert match, "Authentication template section missing"
    section = match.group(1)
    section = re.sub(r'th:(formaction|action)="@\{([^}]+)\}"',
                     lambda m: f'{m.group(1)}="{m.group(2)}"', section)
    section = section.replace('th:name="${_csrf.parameterName}"', 'name="_csrf"')
    section = section.replace('th:value="${_csrf.token}"', 'value="test-csrf-token"')
    section = section.replace('class="panel panel-body mt-4 auth-provider-panel"',
                              'class="panel panel-body mt-4 auth-provider-panel" hidden')
    # document.baseURI is HTTPS for realistic resolved form actions. The document
    # itself stays at about:blank because outbound browser traffic is prohibited.
    return '<html><head><base href="https://clamav.test/"></head><body>' + section + '</body></html>'


def main():
    js = SCRIPT.read_text(encoding="utf-8")
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True,
            executable_path=os.environ.get("CHROMIUM_PATH", "/usr/bin/chromium"),
            args=["--no-sandbox"])
        try:
            page = browser.new_page()
            page.set_content(materialized_html())
            # Mock network only; production JS remains unchanged.
            page.evaluate('''() => {
                window.__sent = [];
                window.__fail = false;
                window.fetch = async (url, options) => {
                    const entry = { url: String(url), method: options.method,
                                    body: String(options.body), headers: options.headers };
                    window.__sent.push(entry);
                    const valid = entry.url.startsWith("https://clamav.test/admin/settings/authentication/") &&
                        (entry.url.endsWith("/test") || entry.url.endsWith("/save"));
                    if (!valid) return new Response('Method Not Allowed', {status: 405, headers: {'Content-Type':'text/plain'}});
                    if (window.__fail) return new Response(JSON.stringify({ok:false, message:'Provider connection/validation failed; draft preserved'}), {status:400, headers:{'Content-Type':'application/json'}});
                    const isSave = entry.url.endsWith('/save');
                    return new Response(JSON.stringify({ok:true, message: isSave?'Saved':'Test OK', saved:isSave,
                        enabled:'true', secretConfigured: true}), {status:200, headers:{'Content-Type':'application/json'}});
                };
            }''')
            page.add_script_tag(content=js)
            for provider in ('ldap', 'oidc'):
                form = page.locator(f'form#{provider}')
                page.locator(f'button[data-auth-provider="{provider}"]').click()
                assert form.is_visible(), f'{provider} panel not expanded'
                save = form.get_by_role('button', name='Save LDAP settings' if provider=='ldap' else 'Save OIDC settings')
                test = form.get_by_role('button', name='Test LDAP connection' if provider=='ldap' else 'Test OIDC discovery')
                assert save.get_attribute('formaction') == f'{PAGE}/{provider}/save'
                assert test.get_attribute('formaction') == f'{PAGE}/{provider}/test'
                nonsecret = form.locator('input[name="managerDn"]' if provider=='ldap' else 'input[name="clientId"]')
                nonsecret.fill('keep-this-value')
                page.evaluate('window.__fail = true')
                # Exercise the real explicit HTML button override first.
                save.click()
                page.wait_for_function("p => document.querySelector(`form#${p} .auth-inline-result`).textContent.includes('draft preserved')", arg=provider)
                assert page.evaluate('window.__sent.at(-1).url') == f'{BASE}{PAGE}/{provider}/save'
                # Fallback case reproduces original defect: no override attribute
                # means button.formAction returns document URL, not form.action.
                save.evaluate("el => el.removeAttribute('formaction')")
                assert save.evaluate('el => el.formAction') == 'about:blank'
                save.click()
                page.wait_for_function("p => window.__sent.length >= (p === 'ldap' ? 2 : 6)", arg=provider)
                entry = page.evaluate('window.__sent.at(-1)')
                assert entry['url'] == f'{BASE}{PAGE}/{provider}/save', entry
                assert entry['method'] == 'POST'
                assert '_csrf=test-csrf-token' in entry['body']
                assert entry['headers']['X-Requested-With'] == 'XMLHttpRequest'
                assert nonsecret.input_value() == 'keep-this-value'
                page.evaluate('window.__fail = false')
                test.click()
                page.wait_for_function("p => document.querySelector(`form#${p} .auth-inline-result`).textContent.includes('Test OK')", arg=provider)
                assert page.evaluate('window.__sent.at(-1).url') == f'{BASE}{PAGE}/{provider}/test'
                save.click()
                page.wait_for_function("p => document.querySelector(`form#${p} .auth-inline-result`).textContent.includes('Saved')", arg=provider)
                assert page.evaluate('window.__sent.at(-1).url') == f'{BASE}{PAGE}/{provider}/save'
                assert form.is_visible(), f'{provider} panel collapsed after Save'
                print(f'PASS {provider.upper()}: Save fallback, Test override, failed Save retains draft')
            assert page.evaluate('window.__sent.length') == 8
            print('PASS: all 8 requests have correct /ldap|oidc/test|save URLs, none POST to GET-only settings page')
            # Browser on about:blank: new secrets intentionally blocked by HTTPS-only check.
            form = page.locator('form#ldap')
            form.locator('input[name="secret"]').fill('synthetic-secret')
            before = page.evaluate('window.__sent.length')
            form.get_by_role('button', name='Save LDAP settings').click()
            assert page.evaluate('window.__sent.length') == before, 'HTTP secret guard bypassed'
            assert form.locator('input[name="secret"]').input_value() == 'synthetic-secret'
            print('PASS: new secrets blocked on non-HTTPS page without clearing the draft')
        finally:
            browser.close()

if __name__ == '__main__':
    main()
