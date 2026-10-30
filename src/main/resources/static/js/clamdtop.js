/* Rebindable, self-contained ClamdTop stats poller. Text rendering only, never raw HTML from clamd. */
(() => {
  let interval = null;
  function setText(id, text) { const el = document.getElementById(id); if (el) el.textContent = String(text ?? '—'); }
  async function refresh(root) {
    if (!root.isConnected) return;
    const endpoint = root.dataset.endpointId;
    try {
      const response = await fetch('/api/clamdtop' + (endpoint ? '?endpointId=' + encodeURIComponent(endpoint) : ''),
        {credentials:'same-origin', headers:{'Accept':'application/json'}, cache:'no-store'});
      if (!response.ok) throw new Error('HTTP ' + response.status);
      const data = await response.json();
      if (!root.isConnected) return;
      setText('clamdtop-state', data.ok ? 'Online · updated ' + data.timestamp : 'Offline · ' + (data.error || 'unknown error'));
      setText('clamdtop-version', data.version || 'Version unavailable');
      setText('clamdtop-raw', data.stats || '');
      const parsed = data.parsed || {}, threads = parsed.threads || {}, queue = parsed.queue || {};
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
    } catch(e) { if (root.isConnected) setText('clamdtop-state','Unable to read ClamD: ' + e.message); }
  }
  function bind() {
    if (interval !== null) clearInterval(interval);
    const root=document.getElementById('clamdtop-monitor');
    if (!root) return;
    refresh(root);
    interval=setInterval(() => {if (!root.isConnected) {clearInterval(interval);interval=null;return;}refresh(root);}, 3000);
  }
  window.__rebindAfterSwap=bind;
  document.addEventListener('DOMContentLoaded',bind);
})();
