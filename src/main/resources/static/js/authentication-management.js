/* Phase 3.1: independent LDAP/OIDC disclosures and no-redirect provider forms.
   Saved values are stored by the server; a Test alone never persists anything.
   Write-only secrets stay only in the form until a confirmed successful Save. */
(() => {
  'use strict';
  const BUTTON_SELECTOR = '.auth-admin-page button[data-auth-provider][aria-controls]';
  const PANEL_CLASS = 'auth-provider-panel';

  function setExpanded(button, expanded) {
    const panel = document.getElementById(button.getAttribute('aria-controls'));
    if (!panel || !panel.classList.contains(PANEL_CLASS)) return;
    panel.hidden = !expanded;
    button.setAttribute('aria-expanded', expanded ? 'true' : 'false');
    button.classList.toggle('is-open', expanded);
  }

  function openPanelFromHash() {
    const requested = window.location.hash.slice(1);
    if (requested !== 'ldap' && requested !== 'oidc') return;
    const button = document.querySelector(`${BUTTON_SELECTOR}[data-auth-provider="${requested}"]`);
    if (button) setExpanded(button, true);
  }

  function initializePage() {
    const page = document.querySelector('.auth-admin-page');
    if (!page) return;
    page.querySelectorAll(BUTTON_SELECTOR).forEach(button => {
      const panel = document.getElementById(button.getAttribute('aria-controls'));
      if (panel && panel.classList.contains(PANEL_CLASS)) setExpanded(button, !panel.hidden);
    });
    openPanelFromHash();
  }

  function showResult(form, message, ok) {
    const output = form.querySelector('.auth-inline-result');
    if (!output) return;
    output.hidden = false;
    output.textContent = message;
    output.classList.toggle('auth-result-ok', ok);
    output.classList.toggle('auth-result-error', !ok);
  }

  function httpFailure(status) {
    if (status === 401) return 'HTTP 401: Your session expired. Sign in again over HTTPS; your entered settings remain in this form.';
    if (status === 403) return 'HTTP 403: Request rejected (possibly an expired CSRF token or insufficient admin permissions). Reload the Authentication page and sign in again; unsaved credentials will need to be re-entered.';
    if (status === 502 || status === 503 || status === 504) return `HTTP ${status}: NGINX cannot reach the ClamAV application. Check proxy and application logs.`;
    if (status >= 500) return `HTTP ${status}: ClamAV could not process the request. Check 'docker logs --tail=120 clamav-web-client' for the actual server exception.`;
    return `HTTP ${status || 'unknown'}: The server returned a response outside the authentication settings API. Check your session, CSRF token and ClamAV logs.`;
  }

  function setProviderState(form, enabled, secretConfigured) {
    const button = document.querySelector(`${BUTTON_SELECTOR}[data-auth-provider="${form.id}"]`);
    if (button) {
      const status = button.querySelector('.auth-provider-status');
      if (status) status.textContent = enabled ? 'Enabled' : 'Disabled';
    }
    const state = form.querySelector('.auth-form-header .muted');
    if (state) state.textContent = 'Stored in database · overrides Docker';
    const secretInfo = form.querySelector('input[name="secret"] + small');
    if (secretInfo) secretInfo.textContent = secretConfigured
      ? (form.id === 'ldap' ? 'A bind secret is configured.' : 'Client secret configured.')
      : (form.id === 'ldap' ? 'No bind secret is configured.' : 'Client secret not configured.');
  }

  document.addEventListener('click', event => {
    const button = event.target.closest(BUTTON_SELECTOR);
    if (!button) return;
    const panel = document.getElementById(button.getAttribute('aria-controls'));
    if (panel && panel.classList.contains(PANEL_CLASS)) setExpanded(button, panel.hidden);
  });

  document.addEventListener('submit', async event => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.matches('.auth-admin-page form.auth-provider-panel')) return;
    event.preventDefault();
    if (form.dataset.authSubmitting === 'true') return;
    const submitter = event.submitter;
    // HTMLButtonElement.formAction resolves to the CURRENT PAGE URL when the
    // button has no formaction attribute (even if its parent form has action).
    // Therefore using `submitter.formAction || form.action` incorrectly POSTs
    // Save to the GET-only /admin/settings/authentication page (HTTP 405).
    // Only honor a submit-button override when it is explicitly present.
    const endpoint = submitter?.hasAttribute('formaction') ? submitter.formAction : form.action;
    const isTest = /\/test(?:\?|$)/.test(endpoint);
    const secretInput = form.querySelector('input[name="secret"]');

    // Do not transmit new credentials over plain HTTP (regardless of server configuration).
    if (secretInput?.value && window.location.protocol !== 'https:') {
      showResult(form, 'New bind/client credentials require HTTPS. Configure a trusted HTTPS reverse proxy before saving or testing a new secret. Your entered settings remain in the form.', false);
      return;
    }

    form.dataset.authSubmitting = 'true';
    const actions = form.querySelectorAll('.auth-actions button');
    actions.forEach(button => { button.disabled = true; });
    showResult(form, isTest ? 'Testing provider connection…' : 'Saving authentication settings…', true);
    try {
      const response = await fetch(endpoint, {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-Requested-With': 'XMLHttpRequest', 'Accept': 'application/json',
                   'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' },
        body: new URLSearchParams(new FormData(form)),
        cache: 'no-store',
        redirect: 'manual'
      });
      // A 401/403/500 error response from Spring (or an NGINX redirect) does not
      // carry the controller's {ok, message} JSON shape. Preserve the draft and
      // display an actionable status rather than "Unexpected server response".
      if (response.type === 'opaqueredirect' || response.status === 0) {
        throw new Error('Authentication session expired or the proxy redirected the request. Reopen the HTTPS login page, sign in, and retry. No form fields were cleared.');
      }
      const type = response.headers.get('content-type') || '';
      if (!type.includes('application/json')) {
        throw new Error(httpFailure(response.status));
      }
      const data = await response.json();
      if (!data || typeof data.ok !== 'boolean') throw new Error(httpFailure(response.status));
      showResult(form, data.message || (data.ok ? 'Operation completed.' : 'Operation failed.'), data.ok);
      if (data.ok && data.saved === true) {
        setProviderState(form, data.enabled === 'true', data.secretConfigured === true);
        // New secret is now safely encrypted in the database: do not leave it in the DOM.
        if (secretInput) secretInput.value = '';
      }
      // Keep every draft field (including a secret during a failed test) in place.
      // Users can correct inputs and Save without retyping their entire configuration.
    } catch (error) {
      showResult(form, error.message || 'Unable to reach the server; no settings were changed.', false);
    } finally {
      delete form.dataset.authSubmitting;
      actions.forEach(button => { button.disabled = false; });
    }
  });

  window.addEventListener('hashchange', openPanelFromHash);
  // PJAX replaces main.innerHTML and sends this event after each navigation.
  document.addEventListener('clamav:page-ready', initializePage);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initializePage);
  else initializePage();
})();
