/* Localized UI text only. Never modify form values, scan paths, raw API payloads,
   certificates, usernames, LDAP distinguished names, or clamdtop terminal output. */
(() => {
  'use strict';
  const lang = (document.documentElement.lang || 'en').toLowerCase().split('-')[0];
  const dictionary = window.ClamavTranslationCatalog?.[lang] || null;
  const forbidden = 'script,style,noscript,code,pre,textarea,input,svg,canvas,.xterm,.xterm-screen,.xterm-viewport,#native-clamdtop-terminal,[data-i18n-ignore]';
  const attributes = ['placeholder', 'title', 'aria-label'];

  function translate(value) {
    if (typeof value !== 'string') return value;
    return dictionary?.[value] || value;
  }
  window.clamavTranslate = translate;
  window.clamavLocale = lang;
  if (!dictionary) return; // Default English is the template's source language.

  function translateText(node) {
    const parent = node.parentElement;
    if (!parent || parent.closest(forbidden)) return;
    const source = node.nodeValue;
    const core = source.trim();
    if (!core) return;
    const result = dictionary[core];
    if (!result || result === core) return;
    const start = source.slice(0, source.indexOf(core));
    const end = source.slice(source.indexOf(core) + core.length);
    node.nodeValue = start + result + end;
  }

  function visit(root) {
    if (!root) return;
    if (root.nodeType === Node.TEXT_NODE) { translateText(root); return; }
    if (root.nodeType !== Node.ELEMENT_NODE && root.nodeType !== Node.DOCUMENT_NODE) return;
    if (root.nodeType === Node.ELEMENT_NODE && root.closest(forbidden)) return;
    if (root.nodeType === Node.ELEMENT_NODE) {
      for (const attr of attributes) {
        if (root.hasAttribute(attr)) {
          const old = root.getAttribute(attr);
          const next = translate(old);
          if (old !== next) root.setAttribute(attr, next);
        }
      }
    }
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
    const textNodes = [];
    while (walker.nextNode()) textNodes.push(walker.currentNode);
    textNodes.forEach(translateText);
    if (root.querySelectorAll) {
      root.querySelectorAll('[placeholder],[title],[aria-label]').forEach(el => {
        if (el.closest("script,style,noscript,code,pre,svg,canvas,.xterm,.xterm-screen,.xterm-viewport,#native-clamdtop-terminal,[data-i18n-ignore]")) return;
        attributes.forEach(attr => {
          if (!el.hasAttribute(attr)) return;
          const source = el.getAttribute(attr);
          const result = translate(source);
          if (source !== result) el.setAttribute(attr, result);
        });
      });
    }
  }

  function apply() {
    visit(document.body);
    const nextTitle = translate(document.title);
    if (nextTitle !== document.title) document.title = nextTitle;
  }
  function init() {
    apply();
    // Status messages are updated asynchronously by the existing app, and PJAX
    // replaces the page body. Observe changes rather than patching business logic.
    const observer = new MutationObserver(records => {
      for (const record of records) {
        if (record.type === 'characterData') translateText(record.target);
        else for (const node of record.addedNodes) visit(node);
      }
    });
    observer.observe(document.body, {childList:true,characterData:true,subtree:true});
    document.addEventListener('clamav:page-ready', apply);
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init, {once:true});
  else init();
})();
