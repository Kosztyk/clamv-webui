(function () {
  function setTheme(theme) {
    const t = theme === 'light' ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', t);
    try { localStorage.setItem('theme', t); } catch (e) {}
    const toggle = document.getElementById('themeToggle');
    if (toggle) {
      toggle.checked = (t === 'dark');
    }
  }

  function initThemeToggle() {
    let saved = null;
    try { saved = localStorage.getItem('theme'); } catch (e) {}
    if (!saved) {
      // default to dark, but respect user's system preference
      const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
      saved = prefersDark ? 'dark' : 'light';
    }
    setTheme(saved);

    const toggle = document.getElementById('themeToggle');
    if (toggle) {
      toggle.addEventListener('change', () => {
        setTheme(toggle.checked ? 'dark' : 'light');
      });
    }
  }

  
  // Expose for PJAX swaps
  window.__applyThemeToggle = initThemeToggle;

  document.addEventListener('DOMContentLoaded', () => {
    initThemeToggle();
  });
})();

// Legacy helpers (kept for compatibility with existing templates)
function addFormEventListener(formName, waitingId, formButton) {
  const form = document.getElementById(formName);
  const waitingScreen = document.getElementById(waitingId);
  const button = document.getElementById(formButton);
  if (form && waitingScreen && button) {
    form.addEventListener('submit', () => {
      waitingScreen.style.display = 'block';
      button.disabled = true;
    });
  }
}
