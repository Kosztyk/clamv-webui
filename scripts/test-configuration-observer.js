#!/usr/bin/env node
/* Dependency-free behavior checks for the read-only view refresher. */
'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const script = fs.readFileSync(path.join(__dirname, '../src/main/resources/static/js/configuration-observer.js'), 'utf8');

function page(fetchResult) {
  const timers = new Map(), events = new Map(), calls = [];
  let counter = 0;
  const state = { root: null, replacements: 0 };
  function root() {
    return { dataset: { pollMs: '30000' }, isConnected: true,
      details: [{ dataset: { detailKey: 'files' }, open: false }],
      querySelectorAll(selector) { return selector.includes('[open]') ? this.details.filter(item => item.open) : this.details; },
      replaceWith(next) { this.isConnected = false; state.root = next; state.replacements++; } };
  }
  state.root = root();
  const document = { hidden: false, readyState: 'complete',
    querySelector() { return state.root; },
    addEventListener(name, callback) { events.set(name, callback); } };
  const context = { document, window: { location: { href: 'https://example.test/admin/configuration' } }, AbortController,
    setTimeout(callback, delay) { timers.set(++counter, { callback, delay }); return counter; },
    clearTimeout(id) { timers.delete(id); },
    fetch(url, options) { calls.push({url, options}); return fetchResult(url, options); },
    DOMParser: class { parseFromString(html) { return { querySelector() { return html === 'login' ? null : root(); } }; } } };
  vm.runInNewContext(script, context);
  return { state, timers, events, calls, document,
    tick() { const [id, timer] = timers.entries().next().value; timers.delete(id); return timer.callback(); } };
}
const response = body => Promise.resolve({ ok: true, text: async () => body });

test('refreshes the existing view with current server content and retains one timer', async () => {
  const p = page(() => response('updated'));
  assert.equal([...p.timers.values()][0].delay, 30000);
  await p.tick();
  assert.equal(p.calls.length, 1);
  assert.equal(p.calls[0].options.credentials, 'same-origin');
  assert.equal(p.calls[0].options.cache, 'no-store');
  assert.equal(p.state.replacements, 1);
  assert.equal(p.timers.size, 1);
});

test('keeps the selected advanced section expanded across telemetry refreshes', async () => {
  const p = page(() => response('updated'));
  p.state.root.details[0].open = true;
  await p.tick();
  assert.equal(p.state.root.details[0].open, true);
  p.state.root.details[0].open = false;
  await p.tick();
  assert.equal(p.state.root.details[0].open, false);
});

test('hidden pages keep their snapshot and defer network requests', async () => {
  const p = page(() => response('updated'));
  p.document.hidden = true;
  await p.tick();
  assert.equal(p.calls.length, 0);
  assert.equal(p.state.replacements, 0);
  assert.equal(p.timers.size, 1);
});

test('navigation aborts a pending request and prevents an obsolete view from replacing another page', async () => {
  let resolve;
  const pending = new Promise(done => { resolve = done; });
  const p = page(() => pending);
  const refreshing = p.tick();
  const signal = p.calls[0].options.signal;
  p.state.root.isConnected = false;
  p.state.root = null;
  p.events.get('clamav:page-ready')();
  assert.equal(signal.aborted, true);
  resolve({ ok: true, text: async () => 'updated' });
  await refreshing;
  assert.equal(p.state.replacements, 0);
  assert.equal(p.state.root, null);
  assert.equal(p.timers.size, 0);
});

test('server errors and expired sessions preserve the displayed snapshot and its timestamp', async () => {
  for (const fetchResult of [() => Promise.resolve({ ok: false }), () => response('login')]) {
    const p = page(fetchResult);
    const original = p.state.root;
    await p.tick();
    assert.equal(p.state.root, original);
    assert.equal(p.state.replacements, 0);
    assert.equal(p.timers.size, 1);
  }
});
