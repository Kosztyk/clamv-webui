(() => {
  const MAIN_SEL = 'main.app-main';
  const TAB_SEL = '.tabs .tab-link';
  const PROGRESS_SEL = '#pjaxProgress';
  const CACHE_TTL_MS = 30_000;

  const cache = new Map(); // path -> { html, title, ts }
  let inFlight = null; // AbortController

  function sameOrigin(url) {
    try {
      const u = new URL(url, window.location.href);
      return u.origin === window.location.origin;
    } catch {
      return false;
    }
  }

  function pathOf(href) {
    try { return new URL(href, window.location.href).pathname; }
    catch { return href || ''; }
  }

  function setActiveTabs(urlPath) {
    document.querySelectorAll(TAB_SEL).forEach(a => {
      const href = a.getAttribute('href') || '';
      const path = pathOf(href);
      a.classList.toggle('active', path === urlPath || (urlPath === '/' && path === '/main'));
    });
  }

  function setLoadingTab(urlPath, isLoading) {
    document.querySelectorAll(TAB_SEL).forEach(a => {
      const href = a.getAttribute('href') || '';
      const path = pathOf(href);
      const match = (path === urlPath || (urlPath === '/' && path === '/main'));
      if (match) a.classList.toggle('is-loading', !!isLoading);
      else a.classList.remove('is-loading');
    });
  }

  function showProgress(on) {
    const p = document.querySelector(PROGRESS_SEL);
    if (!p) return;
    p.classList.toggle('is-active', !!on);
  }

  function getCached(path) {
    const item = cache.get(path);
    if (!item) return null;
    if ((Date.now() - item.ts) > CACHE_TTL_MS) { cache.delete(path); return null; }
    return item;
  }

  async function fetchPage(url, signal) {
    const res = await fetch(url, {
      method: 'GET',
      headers: { 'X-Requested-With': 'PJAX', 'Accept': 'text/html' },
      cache: 'no-store',
      credentials: 'same-origin',
      signal
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const html = await res.text();
    const doc = new DOMParser().parseFromString(html, 'text/html');
    const newMain = doc.querySelector(MAIN_SEL);
    if (!newMain) throw new Error('Missing app main in response');
    const titleEl = doc.querySelector('title');
    return {
      html: newMain.innerHTML,
      title: titleEl ? (titleEl.textContent || '') : '',
    };
  }

  function swapIntoMain(main, payload) {
    // Keep current content visible until swap, lock height to prevent jump.
    const rect = main.getBoundingClientRect();
    main.style.setProperty('--main-min-height', `${Math.max(200, rect.height)}px`);

    const apply = () => {
      main.innerHTML = payload.html;
      if (payload.title) document.title = payload.title;

      try { if (window.__applyThemeToggle) window.__applyThemeToggle(); } catch {}
      try { if (window.__rebindAfterSwap) window.__rebindAfterSwap(); } catch {}

      // Release height lock after paint
      requestAnimationFrame(() => setTimeout(() => {
        main.style.removeProperty('--main-min-height');
      }, 50));
    };

    if (document.startViewTransition) document.startViewTransition(apply);
    else apply();
  }

  async function navigate(url, { push = true } = {}) {
    if (!sameOrigin(url)) { window.location.href = url; return; }

    const main = document.querySelector(MAIN_SEL);
    if (!main) { window.location.href = url; return; }

    const targetPath = pathOf(url);

    // Immediate feedback (no blank screen): active tab + spinner + progress bar
    setActiveTabs(targetPath);
    setLoadingTab(targetPath, true);
    showProgress(true);

    // Abort previous fetch (fast user clicking)
    try { if (inFlight) inFlight.abort(); } catch {}
    inFlight = new AbortController();

    const cached = getCached(targetPath);
    if (cached) {
      swapIntoMain(main, cached);
      if (push) history.pushState({ pjax: true }, '', url);
      setLoadingTab(targetPath, false);
      showProgress(false);
      return;
    }

    try {
      const payload = await fetchPage(url, inFlight.signal);
      cache.set(targetPath, { ...payload, ts: Date.now() });
      swapIntoMain(main, payload);
      if (push) history.pushState({ pjax: true }, '', url);
    } catch (e) {
      if (e && e.name === 'AbortError') return;
      window.location.href = url;
      return;
    } finally {
      setLoadingTab(targetPath, false);
      showProgress(false);
    }
  }

  function onClick(e) {
    const a = e.target.closest('a');
    if (!a) return;
    if (!a.matches(TAB_SEL)) return;

    if (e.defaultPrevented) return;
    if (e.button !== 0) return;
    if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;

    const href = a.getAttribute('href');
    if (!href || href.startsWith('#')) return;

    e.preventDefault();
    navigate(href, { push: true });
  }

  async function prefetch(href) {
    if (!href || !sameOrigin(href)) return;
    const path = pathOf(href);
    if (!path) return;
    if (getCached(path)) return;

    const ac = new AbortController();
    const t = setTimeout(() => ac.abort(), 3000);
    try {
      const payload = await fetchPage(href, ac.signal);
      cache.set(path, { ...payload, ts: Date.now() });
    } catch {}
    finally { clearTimeout(t); }
  }

  function bindPrefetch() {
    document.querySelectorAll(TAB_SEL).forEach(a => {
      const href = a.getAttribute('href');
      a.addEventListener('mouseenter', () => prefetch(href), { passive: true });
      a.addEventListener('touchstart', () => prefetch(href), { passive: true });
    });

    const idle = window.requestIdleCallback || ((fn) => setTimeout(fn, 600));
    idle(() => {
      document.querySelectorAll(TAB_SEL).forEach(a => prefetch(a.getAttribute('href')));
    });
  }

  window.addEventListener('click', onClick, true);
  window.addEventListener('popstate', () => navigate(window.location.href, { push: false }));

  setActiveTabs(window.location.pathname);
  bindPrefetch();
})();