/* Refresh only the read-only configuration view; never submit a form or reload ClamD. */
(() => {
  'use strict';
  let timer;
  let request;
  function init() {
    clearTimeout(timer);
    request?.abort();
    const root = document.querySelector('[data-daemon-configuration]');
    if (!root) return;
    const interval = Math.max(1000, Number(root.dataset.pollMs) || 30000);
    timer = setTimeout(async () => {
      if (!root.isConnected) return;
      if (document.hidden) { init(); return; }
      const controller = new AbortController();
      request = controller;
      const timeout = setTimeout(() => controller.abort(), 10000);
      try {
        const response = await fetch(window.location.href, {
          credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
          headers: { Accept: 'text/html' }
        });
        if (!response.ok) throw new Error('Configuration refresh failed');
        const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
        const next = doc.querySelector('[data-daemon-configuration]');
        if (next && root.isConnected && request === controller) {
          const opened = new Set(Array.from(root.querySelectorAll?.('details[data-detail-key][open]') || []).map(el => el.dataset.detailKey));
          for (const detail of next.querySelectorAll?.('details[data-detail-key]') || []) detail.open = opened.has(detail.dataset.detailKey);
          root.replaceWith(next);
        }
      } catch { /* Keep the last displayed timestamp visible until the next refresh. */ }
      finally {
        clearTimeout(timeout);
        // A navigation may have started another view while this request was pending.
        if (request === controller) { request = null; init(); }
      }
    }, interval);
  }
  document.addEventListener('clamav:page-ready', init);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, {once:true});
  else init();
})();
