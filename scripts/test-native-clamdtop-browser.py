#!/usr/bin/env python3
"""Optional real Chromium layout regression for the native ncurses terminal.

Requires playwright and a local Chromium install. Does not connect to a daemon
and does not access external services. Run from any directory:
    python scripts/test-native-clamdtop-browser.py
"""
from pathlib import Path
from playwright.sync_api import sync_playwright
import shutil

ASSETS = Path(__file__).resolve().parent.parent / 'src/main/resources/static'
BROWSER = shutil.which('chromium') or shutil.which('chromium-browser') or shutil.which('google-chrome')
if not BROWSER:
    raise SystemExit('Chromium not installed (browser regression test unavailable)')

FIXTURE = '''<!doctype html><html lang="en"><head><meta charset="utf-8">
<style>html,body{margin:0;height:100%}.testwrap{height:100vh;width:100vw;display:flex;flex-direction:column}
#native-clamdtop{flex:1;min-height:0}#native-clamdtop-terminal{height:100%;flex:1}</style>
</head><body><div class="testwrap"><section id="native-clamdtop">
<select id="native-clamdtop-endpoint"><option value="1">default</option></select>
<button id="native-clamdtop-connect">Connect</button>
<button id="native-clamdtop-disconnect">Disconnect</button>
<span id="native-clamdtop-status">Connecting</span>
<div id="native-clamdtop-terminal"></div></section></div></body></html>'''

JS_MOCK = '''() => {
    window.TestSockets = [];
    window.WebSocket = class {
        static OPEN=1;
        constructor(url) {this.url=url;this.readyState=1;this.handlers={};window.TestSockets.push(this)}
        addEventListener(e,fn) {this.handlers[e]=fn}
        send() {}
        close() {this.readyState=3;this.handlers.close?.({code:1000})}
    };
}'''

JS_MEASURE = '''() => {
    const p = document.querySelector('#native-clamdtop-terminal');
    const x = p.querySelector('.xterm');
    const vp = p.querySelector('.xterm-viewport');
    const canvas = p.querySelector('.xterm-screen canvas');
    const s = window.TestSockets.at(-1);
    const css = el => getComputedStyle(el);
    return {
       width: p.getBoundingClientRect().width,
       painted: canvas?.getBoundingClientRect().width,
       bg: css(p).backgroundColor,
       termBg: css(x).backgroundColor,
       vpBg: css(vp).backgroundColor,
       padding: css(p).padding,
       border: css(p).borderWidth,
       ws: s.url,
       requests: window.TestSockets.length
    };
}'''

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=BROWSER, args=['--no-sandbox'])
    try:
        for dpr in (1, 2):
            page = browser.new_page(viewport={'width': 750, 'height': 860}, device_scale_factor=dpr)
            page.set_content(FIXTURE)
            page.add_style_tag(path=str(ASSETS / 'css/xterm-vendored.css'))
            page.add_style_tag(path=str(ASSETS / 'css/phase0.css'))
            page.evaluate(JS_MOCK)
            page.add_script_tag(path=str(ASSETS / 'js/xterm-vendored.js'))
            page.add_script_tag(path=str(ASSETS / 'js/native-clamdtop.js'))
            page.evaluate("document.dispatchEvent(new Event('clamav:page-ready'))")
            for width in (750, 1000, 1450, 1850):
                page.set_viewport_size({'width': width, 'height': 860})
                page.wait_for_timeout(550)  # allow ResizeObserver debouncing and terminal reconnect
                v = page.evaluate(JS_MEASURE)
                assert v['bg'] == v['termBg'] == v['vpBg'] == 'rgb(46, 52, 54)', v
                assert v['padding'] == '0px' and v['border'] == '0px', v
                assert 0 <= v['width'] - v['painted'] <= 12, v
                assert 'cols=' in v['ws'] and 'rows=' in v['ws'], v
                print(f'PASS dpr={dpr} width={width}: {v["width"]-v["painted"]:.1f}px painted-grid remainder')
            before = page.evaluate('window.TestSockets.length')
            page.locator('#native-clamdtop-disconnect').click()
            page.set_viewport_size({'width': 1300, 'height': 860})
            page.wait_for_timeout(550)
            assert page.evaluate('window.TestSockets.length') == before, 'Resize must not reconnect user-disconnected session'
            page.close()
    finally:
        browser.close()
print('PASS: terminal painting, fitting, resize and disconnect')
