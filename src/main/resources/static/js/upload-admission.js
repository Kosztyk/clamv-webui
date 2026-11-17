/* Issue #30: provide non-authoritative, pre-body upload metadata for fast 413.
 * File count and endpoint are still validated by server after multipart parsing. */
(() => {
  document.addEventListener('submit', event => {
    const form = event.target;
    if (!(form instanceof HTMLFormElement) || !form.hasAttribute('data-upload-preflight')) return;
    const files = form.querySelector('input[type="file"][name="files"]');
    const endpoint = form.querySelector('select[name="endpointId"]');
    if (!files || !files.files || files.files.length === 0 || !endpoint || !endpoint.value) return;
    const url = new URL(form.getAttribute('action') || form.action, window.location.href);
    url.searchParams.set('uploadPreflightEndpointId', endpoint.value);
    url.searchParams.set('uploadFileCount', String(files.files.length));
    form.action = url.toString();
  }, true);
})();
