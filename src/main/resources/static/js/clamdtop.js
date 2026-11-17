/* Rebindable, self-contained ClamdTop stats poller. Text rendering only, never raw HTML from clamd. */
(() => {
  let interval = null;
  let activeRoot = null;
  let inFlight = false;
  function setText(id, text) { const el = document.getElementById(id); if (el) el.textContent = String(text ?? '—'); }
  async function refresh(root) {
    if (!root.isConnected || document.visibilityState === 'hidden' || inFlight) return;
    inFlight = true;
    const endpoint = root.dataset.endpointId;
    try {
      const response = await fetch('/api/clamdtop' + (endpoint ? '?endpointId=' + encodeURIComponent(endpoint) : ''),
        {credentials:'same-origin', headers:{'Accept':'application/json'}, cache:'no-store'});
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const data = await response.json();
      if (!root.isConnected) return;
      setText('clamdtop-state', data.ok ? 'Online · updated ' + (data.checkedAt || data.timestamp || 'now') : 'Offline · ' + (data.error || 'health unavailable'));
      setText('clamdtop-version', data.version || 'Version unavailable');
      setText('clamdtop-raw', data.stats || '');
      // VIEWER receives the allowlisted top-level numeric projection; OPERATOR/ADMIN
      // retain the legacy raw STATS format. Never require raw data for the safe page.
      const parsed = data.parsed || {}, threads = data.threads || parsed.threads || {}, queue = data.queue || parsed.queue || {};
      setText('clamdtop-live', threads.live);
      setText('clamdtop-busy', threads.busy);
      setText('clamdtop-queue', queue.items);
      const body = document.getElementById('clamdtop-commands');
      if (!body) return;
      body.replaceChildren();
      const commands = parsed.commands || [];
      if (!commands.length) {
        const row = body.insertRow(), cell = row.insertCell();cell.colSpan=3;cell.textContent='No active scan commands reported.';
      } else commands.forEach(cmd => {
        const row = body.insertRow();
        [cmd.command,cmd.queuedSince,cmd.file || cmd.raw].forEach(text => { row.insertCell().textContent=text || ''; });
      });
    } catch(e) { if (root.isConnected && document.visibilityState !== 'hidden') setText('clamdtop-state','Unable to read ClamD: ' + e.message); }
    finally { inFlight = false; }
  }
  function bind() {
    if (interval !== null) clearInterval(interval);
    const root=document.getElementById('clamdtop-monitor');
    activeRoot = root;
    if (!root) return;
    if (document.visibilityState !== 'hidden') refresh(root);
    interval=setInterval(() => {
      if (!root.isConnected || root !== activeRoot) {clearInterval(interval);interval=null;return;}
      if (document.visibilityState !== 'hidden') refresh(root);
    }, 3000);
  }
  document.addEventListener('visibilitychange', () => {
    // Hidden tabs generate no daemon snapshots; refresh once when the viewer returns.
    if (document.visibilityState === 'visible' && activeRoot?.isConnected) refresh(activeRoot);
  });
  window.__rebindAfterSwap=bind;
  document.addEventListener('DOMContentLoaded',bind);
})();
