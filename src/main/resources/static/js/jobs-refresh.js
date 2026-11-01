/* Refresh the server-rendered job table without navigating or overlapping reads. */
(() => {
  'use strict';
  const instances = new Map();
  function stop(root) {
    const state = instances.get(root);
    if (!state) return;
    state.stopped = true;
    clearTimeout(state.timer);
    state.request?.abort();
    instances.delete(root);
  }
  function bind() {
    for (const root of instances.keys()) if (!root.isConnected) stop(root);
    for (const root of document.querySelectorAll('[data-jobs-table][data-refresh-url]')) {
      if (instances.has(root)) continue;
      const state = {busy: false, stopped: false, timer: null, request: null};
      instances.set(root, state);
      const message = text => {
        const target = root.querySelector('[data-jobs-refresh-status]');
        if (target && target.textContent !== text) target.textContent = text;
      };
      const schedule = () => {
        clearTimeout(state.timer);
        if (!root.isConnected) { stop(root); return; }
        if (!state.stopped && !document.hidden) {
          const pending = root.querySelector('[data-job-status="QUEUED"], [data-job-status="RUNNING"]');
          state.timer = setTimeout(refresh, pending ? 2000 : 5000);
        }
      };
      async function refresh() {
        clearTimeout(state.timer);
        if (!root.isConnected) { stop(root); return; }
        if (state.busy || state.stopped || document.hidden) return;
        state.busy = true;
        const request = new AbortController();
        state.request = request;
        const deadline = setTimeout(() => request.abort(), 10000);
        try {
          const response = await fetch(root.dataset.refreshUrl, {
            headers: {Accept: 'text/html', 'X-Requested-With': 'XMLHttpRequest'},
            cache: 'no-store', credentials: 'same-origin', signal: request.signal
          });
          if (response.redirected || response.status === 401 || response.status === 403) {
            message('Automatic updates paused. Sign in again to refresh jobs.');
            stop(root); return;
          }
          if (!response.ok || !response.headers.get('content-type')?.includes('text/html'))
            throw new Error('Job table unavailable');
          const doc = new DOMParser().parseFromString(await response.text(), 'text/html');
          const next = doc.querySelector('[data-jobs-table] table');
          if (!next) throw new Error('Job table unavailable');
          if (state.stopped || !root.isConnected) return;
          const current = root.querySelector('table');
          if (current && current.outerHTML !== next.outerHTML) {
            const focused = root.contains(document.activeElement) ? document.activeElement.getAttribute('href') : null;
            const scrollLeft = root.scrollLeft;
            current.replaceWith(next);
            root.scrollLeft = scrollLeft;
            if (focused) [...root.querySelectorAll('a[href]')].find(link => link.getAttribute('href') === focused)?.focus({preventScroll: true});
          }
          message('Updates automatically.');
        } catch (error) {
          if (!state.stopped && root.isConnected && !document.hidden)
            message('Could not refresh jobs. Retrying automatically; the last result is still displayed.');
        } finally {
          clearTimeout(deadline);
          if (state.request === request) state.request = null;
          state.busy = false;
          schedule();
        }
      }
      state.refresh = refresh;
      refresh();
    }
  }
  document.addEventListener('DOMContentLoaded', bind);
  document.addEventListener('clamav:page-ready', bind);
  document.addEventListener('visibilitychange', () => {
    for (const [root, state] of instances) {
      if (document.hidden) { clearTimeout(state.timer); state.request?.abort(); }
      else state.refresh();
    }
  });
  window.addEventListener('pagehide', () => { for (const root of instances.keys()) stop(root); });
  window.addEventListener('pageshow', bind);
  if (document.readyState !== 'loading') bind();
})();
