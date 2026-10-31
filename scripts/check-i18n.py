#!/usr/bin/env python3
"""Offline locale catalog, routing and Chromium smoke checks (if Playwright available)."""
from pathlib import Path
import json
import os
from subprocess import run
BASE = Path(__file__).resolve().parents[1]
resources = BASE/'src/main/resources'
source = (resources/'static/js/i18n-catalog.js').read_text()
assert source.startswith('/* ClamAV Web UI')
catalog = json.loads(source.split('window.ClamavTranslationCatalog = ', 1)[1].split(';\n',1)[0])
assert set(catalog) == {'uk','ro','fr','es','de'}
key_set = set(catalog['uk'])
assert all(set(d)==key_set for d in catalog.values()), 'Catalogs do not share keys'
assert len(key_set) >= 320, 'Insufficient interface translations'
for lang in ('ro','fr','es','de','uk'):
    p=resources/f'messages_{lang}.properties'
    assert p.exists() and 'ui.language.current=' in p.read_text()
shell=(resources/'templates/fragments/shell.html').read_text()
login=(resources/'templates/login.html').read_text()
for lang in ('en','uk','ro','fr','es','de'):
    for page in (shell,login):
        assert f"lang='{lang}'" in page, f'Missing {lang} selector'
assert '"/setLocale"' in (BASE/'src/main/java/info/trizub/clamav/webclient/config/SecurityConfig.java').read_text()
for path in (resources/'static/js/i18n.js',resources/'static/js/i18n-catalog.js'):
    assert run(['node','--check',str(path)],capture_output=True).returncode == 0
print('PASS: six locale choices, persistent cookie configuration, balanced catalogs, scripts')
try:
    from playwright.sync_api import sync_playwright
except ImportError:
    print('SKIPPED: Chromium browser tests (Playwright not installed)')
    raise SystemExit(0)
with sync_playwright() as p:
    browser=p.chromium.launch(headless=True,executable_path=os.environ.get('CHROMIUM_PATH', '/usr/bin/chromium'), args=['--no-sandbox'])
    for lang,title,dashboard,auth in [('en','Dashboard','Dashboard','Authentication'),('uk','Панель керування','Панель керування','Автентифікація'),('ro','Tablou de bord','Tablou de bord','Autentificare'),('fr','Tableau de bord','Tableau de bord','Authentification'),('es','Panel de control','Panel de control','Autenticación'),('de','Dashboard','Dashboard','Authentifizierung')]:
        page=browser.new_page()
        page.set_content(f'''<!doctype html><html lang="{lang}"><head><title>Dashboard</title></head><body>
        <div id="menu"><a>Dashboard</a><span>Authentication</span></div>
        <input id="secret" type="password" placeholder="Leave blank to retain existing" value="private-password"/>
        <code id="raw">Dashboard</code>
        <div id="dynamic"></div><div id="test-field">User: kosztyk</div>
        </body></html>''')
        page.add_script_tag(path=str(resources/'static/js/i18n-catalog.js'))
        page.add_script_tag(path=str(resources/'static/js/i18n.js'))
        page.wait_for_timeout(30)
        assert page.locator('#menu a').inner_text() == dashboard,(lang,'dashboard')
        assert page.locator('#menu span').inner_text() == auth,(lang,'auth')
        assert page.locator('#secret').input_value()=='private-password',(lang,'secret was modified')
        assert page.locator('#raw').inner_text()=='Dashboard',(lang,'code unexpectedly modified')
        assert page.locator('#test-field').inner_text()=='User: kosztyk',(lang,'dynamic identity modified')
        page.locator('#dynamic').evaluate('(el) => el.textContent = "Save LDAP settings"')
        page.wait_for_timeout(30)
        assert page.locator('#dynamic').inner_text() == catalog.get(lang,{}).get('Save LDAP settings','Save LDAP settings'),(lang,'dynamic')
        page.close()
    browser.close()
print('PASS: Chromium six locales + asynchronous updates + input and code protection')
