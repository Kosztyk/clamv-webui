/* The true native clamdtop PTY: fit ncurses to the *measured* xterm character grid.
   No terminal-only layout override: preserve the user's standard app navigation. */
(() => {
  function start() {
    const root = document.getElementById('native-clamdtop');
    if (!root || root.dataset.bound === 'true') return;
    root.dataset.bound = 'true';

    const screen = document.getElementById('native-clamdtop-terminal');
    const endpoint = document.getElementById('native-clamdtop-endpoint');
    const state = document.getElementById('native-clamdtop-status');
    const connectButton = document.getElementById('native-clamdtop-connect');
    const disconnectButton = document.getElementById('native-clamdtop-disconnect');
    if (typeof Terminal !== 'function') {
      state.textContent = 'Terminal renderer is missing';
      return;
    }

    let socket = null;
    let term = null;
    let decoding = new TextDecoder('utf-8');
    let intentional = false;
    let activeSize = null;
    let resizeTimer = null;
    let observer = null;

    // xterm-vendored measures a monospace cell after term.open(). The old
    // 8.4px-wide/17px-high estimates left an empty black band on the right.
    // The browser's actual font metrics and terminal element width are the
    // authoritative dimensions; the server must start ncurses at the SAME size.
    function fittedSize(terminal) {
      const style = window.getComputedStyle(screen);
      const contentWidth = screen.clientWidth - parseFloat(style.paddingLeft || 0)
        - parseFloat(style.paddingRight || 0);
      const contentHeight = screen.clientHeight - parseFloat(style.paddingTop || 0)
        - parseFloat(style.paddingBottom || 0);
      const viewport = screen.querySelector('.xterm-viewport');
      const scrollbar = viewport ? Math.max(0, viewport.offsetWidth - viewport.clientWidth) : 0;
      // Do NOT derive cell width from .xterm-screen: this old xterm build adds
      // viewport.scrollBarWidth (typically 15px) to that element. Dividing
      // screen width by the initial 80 columns overestimates character width,
      // leaving a visible, growing strip at the far right after resize.
      // Use the renderer's real painted canvas cell width instead.
      const paintedCanvas = screen.querySelector('.xterm-screen canvas');
      const canvasCellWidth = paintedCanvas && terminal.cols > 0
        ? paintedCanvas.getBoundingClientRect().width / terminal.cols : 0;
      const cellWidth = terminal.renderer?.dimensions?.actualCellWidth
        || canvasCellWidth || terminal.charMeasure?.width || 8.4;
      const cellHeight = terminal.renderer?.dimensions?.actualCellHeight
        || Math.ceil((terminal.charMeasure?.height || 17) * (terminal.options.lineHeight || 1));
      // Allow one pixel of breathing room to prevent a horizontal scrollbar.
      return {
        cols: Math.max(30, Math.min(400, Math.floor((contentWidth - scrollbar - 1) / cellWidth))),
        rows: Math.max(16, Math.min(160, Math.floor((contentHeight - 1) / cellHeight)))
      };
    }

    function disconnect() {
      intentional = true;
      if (socket) { socket.close(1000, 'Disconnected by user'); socket = null; }
      if (term) { term.destroy(); term = null; }
      activeSize = null;
      state.textContent = 'Disconnected';
    }

    function connect() {
      disconnect();
      intentional = false;
      screen.replaceChildren();
      term = new Terminal({ cols: 80, rows: 24, scrollback: 200,
        fontFamily: 'monospace', fontSize: 14,
        theme: { background: '#2e3436', black: '#2e3436', foreground: '#d9e5f4', cursor: '#61e2e7' } });
      term.open(screen);
      // Measure after open(), when the browser has rendered its real character size.
      const size = fittedSize(term);
      term.resize(size.cols, size.rows);
      activeSize = size;
      term.focus();
      decoding = new TextDecoder('utf-8');

      const scheme = location.protocol === 'https:' ? 'wss:' : 'ws:';
      const url = scheme + '//' + location.host + '/ws/clamdtop?endpointId='
        + encodeURIComponent(endpoint.value) + '&cols=' + size.cols + '&rows=' + size.rows;
      state.textContent = 'Connecting to native clamdtop…';
      const ws = new WebSocket(url);
      socket = ws;
      term.on('data', data => {
        if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify({ type: 'input', data }));
      });
      ws.addEventListener('open', () => { if (socket === ws) state.textContent = 'Connected'; });
      ws.addEventListener('message', event => {
        if (socket !== ws || !term) return;
        try {
          const msg = JSON.parse(event.data);
          if (msg.type === 'data' && msg.base64) {
            const raw = atob(msg.base64);
            const bytes = Uint8Array.from(raw, ch => ch.charCodeAt(0));
            term.write(decoding.decode(bytes, { stream: true }));
          } else if (msg.type === 'status') state.textContent = msg.message;
          else if (msg.type === 'error') {
            state.textContent = msg.message;
            term.write('\r\n' + msg.message + '\r\n');
          }
        } catch (e) { state.textContent = 'Terminal protocol error: ' + e.message; }
      });
      ws.addEventListener('close', event => {
        if (socket === ws) {
          socket = null;
          state.textContent = intentional ? 'Disconnected'
            : 'Native clamdtop disconnected (code ' + event.code + ')';
        }
      });
      ws.addEventListener('error', () => {
        if (socket === ws) state.textContent = 'WebSocket error. Check reverse proxy WebSocket upgrade and server logs.';
      });
    }

    function fitIfNeeded() {
      if (!root.isConnected) { dispose(); return; }
      if (!term || !socket) return; // Respect a user-initiated Disconnect.
      const next = fittedSize(term);
      if (activeSize && (next.cols !== activeSize.cols || next.rows !== activeSize.rows)) {
        // The fixed PTY command sizes ncurses only when it starts. Reconnect
        // with the new measured grid rather than stretching text or leaving a gap.
        connect();
      }
    }
    function scheduleFit() {
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(fitIfNeeded, 300);
    }
    function dispose() {
      clearTimeout(resizeTimer);
      observer?.disconnect();
      window.removeEventListener('resize', scheduleFit);
      document.removeEventListener('clamav:page-ready', onPageReady);
      disconnect();
    }
    function onPageReady() {
      if (!root.isConnected) dispose();
    }

    connectButton?.addEventListener('click', connect);
    disconnectButton?.addEventListener('click', disconnect);
    endpoint?.addEventListener('change', connect);
    window.addEventListener('beforeunload', disconnect, { once: true });
    window.addEventListener('resize', scheduleFit);
    document.addEventListener('clamav:page-ready', onPageReady);
    if (typeof ResizeObserver === 'function') {
      observer = new ResizeObserver(scheduleFit);
      observer.observe(screen);
    }
    connect();
    // Fonts may finish loading after DOM ready (or a PJAX transition).
    if (document.fonts?.ready) document.fonts.ready.then(scheduleFit);
  }
  document.addEventListener('DOMContentLoaded', start);
  document.addEventListener('clamav:page-ready', start);
})();
