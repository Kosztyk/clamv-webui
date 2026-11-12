/* Configuration Center: shared by the administration page and protected setup. */
(() => {
  'use strict';
  const instances = new WeakMap();
  const labels = {Connections: 'Service connections', Scanning: 'Scanning & storage', 'Identity & API': 'Sign-in & machine access', Notifications: 'Scan notifications', Network: 'Network & certificates', Monitoring: 'Monitoring & configuration observation', System: 'Preferences & recovery'};
  const iconText = {clamd: '◇', rest: '↗', database: '▤', scanning: '↥', quarantine: '◈', watch: '▱', local: '♙', ldap: '♧', oidc: '⌘', api: '>_', webhook: '♧', proxy: '⇄', trust: '♧', metrics: '▥', observer: '☷', system: '⚙'};
  function node(tag, attributes, ...children) {
    const element = document.createElement(tag);
    for (const [key, value] of Object.entries(attributes || {})) {
      if (value === undefined || value === null) continue;
      if (key === 'class') element.className = value;
      else if (key === 'checked' || key === 'disabled' || key === 'hidden') element[key] = Boolean(value);
      else if (key === 'value') element.value = value;
      else element.setAttribute(key, String(value));
    }
    for (const child of children.flat()) {
      if (child !== undefined && child !== null) element.append(typeof child === 'string' ? document.createTextNode(child) : child);
    }
    return element;
  }
  function databaseType(url) {
    if (url.startsWith('jdbc:postgresql:')) return 'postgresql';
    if (url.startsWith('jdbc:mysql:')) return 'mysql';
    if (url.startsWith('jdbc:mariadb:')) return 'mariadb';
    return 'h2';
  }
  class Center {
    constructor(root) {
      this.root = root;
      this.setup = root.dataset.mode === 'setup';
      this.endpoint = root.dataset.endpoint === '.' ? new URL('.', location.href).pathname.replace(/\/$/, '') : root.dataset.endpoint;
      this.content = root.querySelector('.cc-content');
      this.message = root.querySelector('.cc-message');
      this.loading = root.querySelector('.cc-loading');
      this.changes = new Map(); this.clearSecrets = new Set(); this.open = new Map(); this.advanced = new Set();
      this.category = 'Connections'; this.token = ''; this.busy = false; this.acknowledgeHttp = false;
      const unlock = root.querySelector('.cc-unlock');
      if (unlock) unlock.addEventListener('submit', event => { event.preventDefault(); this.unlock(unlock); });
      else this.load();
    }
    async api(action, body, method = 'POST') {
      const sendsCredentials = Boolean(body?.verificationPassword) || Object.entries(body?.changes || {}).some(([key, value]) =>
        this.fields?.get(key)?.definition.secret && String(value).trim());
      if (location.protocol === 'http:' && !this.acknowledgeHttp && sendsCredentials)
        throw new Error('Select "Allow configuration over HTTP on this trusted network" before submitting credentials, or use HTTPS.');
      const headers = {Accept: 'application/json'};
      if (method !== 'GET') headers['Content-Type'] = 'application/json';
      if (this.setup && this.token) headers.Authorization = `Bearer ${this.token}`;
      const csrf = document.querySelector('meta[name="_csrf"]');
      const csrfHeader = document.querySelector('meta[name="_csrf_header"]');
      if (!this.setup && csrf && csrfHeader) headers[csrfHeader.content] = csrf.content;
      const response = await fetch(`${this.endpoint}/api/${action}`, {method, headers, body: method === 'GET' ? undefined : JSON.stringify(body || {}), credentials: this.setup ? 'omit' : 'same-origin', cache: 'no-store'});
      if (!response.headers.get('content-type')?.includes('application/json')) throw new Error('The session may have expired. Sign in again; your draft has not been saved.');
      const result = await response.json();
      if (!response.ok) throw new Error(result.message || 'Configuration request failed.');
      return result;
    }
    show(text, error = false) { this.message.hidden = false; this.message.classList.toggle('cc-error', error); this.message.textContent = text; }
    async unlock(form) {
      const button = form.querySelector('button'); button.disabled = true;
      this.token = form.querySelector('input').value.trim();
      try {
        const view = await this.api('login', {token: this.token});
        form.querySelector('input').value = ''; form.hidden = true; this.setView(view);
        this.show(view.recovery ? 'Recovery mode does not depend on your application database. Only explicitly edited fields are saved. Database-managed settings may be unavailable until the application starts.' : 'Choose your database and service connections, review the changes, then start the application.');
      } catch (error) { this.token = ''; this.show(error.message, true); }
      finally { button.disabled = false; }
    }
    async load() {
      try { this.setView(await this.api('view', {}, 'GET')); }
      catch (error) { this.loading.hidden = true; this.show(error.message, true); }
    }
    setView(view) {
      this.view = view; this.fields = new Map(view.fields.map(field => [field.definition.key, field]));
      if (!this.selectionApplied) {
        const requested = new URLSearchParams(location.search);
        const section = view.sections.find(item => item.id === requested.get('section'));
        const aliases = {connections: 'Connections', scanning: 'Scanning', identity: 'Identity & API', notifications: 'Notifications', network: 'Network', monitoring: 'Monitoring', system: 'System'};
        const category = section?.category || aliases[requested.get('category')];
        if (view.categories.includes(category)) this.category = category;
        if (section) this.open.set(section.category, section.id);
        this.selectionApplied = true;
      }
      this.loading.hidden = true; this.content.hidden = false; this.render();
    }
    value(key) { return this.changes.has(key) ? this.changes.get(key) : this.fields.get(key)?.value || ''; }
    set(key, value) {
      const field = this.fields.get(key);
      if (!field || field.locked) return;
      if (field.definition.secret || value !== field.value) this.changes.set(key, value);
      else this.changes.delete(key);
      if (field.definition.secret && !value) this.changes.delete(key);
      this.clearSecrets.delete(key); this.count();
    }
    draft(section) {
      const changes = Object.fromEntries([...this.changes].filter(([key]) => !section || this.fields.get(key).definition.section === section));
      return {version: this.view.version, changes, clearSecrets: [...this.clearSecrets].filter(key => !section || this.fields.get(key).definition.section === section), section, acknowledgeHttp: this.acknowledgeHttp};
    }
    enabled(section) { return !section.enableKey || (section.id === 'proxy' ? this.value(section.enableKey) !== 'none' : this.value(section.enableKey) === section.enabledValue); }
    sectionFields(section) { return [...this.fields.values()].filter(field => field.definition.section === section.id); }
    summary(section) {
      if (!this.enabled(section)) return 'Disabled';
      switch (section.id) {
        case 'clamd': return this.value('clamav.service.host').startsWith('/') ? `${this.value('clamav.service.host')} · Unix socket` : `${this.value('clamav.service.host')}:${this.value('clamav.service.port')} · TCP`;
        case 'rest': return this.value('CLAMAV_REST_URL') || 'No receiver configured';
        case 'database': return `${databaseType(this.value('spring.datasource.url')).toUpperCase()} · ${this.value('spring.datasource.url').replace(/^jdbc:/, '')}`;
        case 'ldap': return this.value('clamav.identity.ldap.url') || 'Directory not configured';
        case 'oidc': return this.value('clamav.identity.oidc.issuer') || 'Identity provider not configured';
        case 'webhook': return this.fields.get('app.webhook.url').configured || this.changes.has('app.webhook.url') ? 'Receiver configured · address kept private' : 'No receiver configured';
        case 'scanning': return `${this.value('app.concurrentScans')} scan workers · ${this.value('app.allowedScanRoots')}`;
        case 'watch': return `${this.value('app.watch.pollSeconds')} second polling interval`;
        case 'proxy': return `${this.value('server.forward-headers-strategy')} forwarded-header handling`;
        case 'trust': return this.value('javax.net.ssl.trustStore') || 'Use the JVM default certificate authorities';
        case 'metrics': return this.fields.get('clamav.metrics.scrape-token').configured ? 'Authenticated scraping · token configured' : 'Scrape token required';
        case 'observer': return this.value('clamav.config.clamd-path') || this.value('clamav.config.freshclam-path') || 'No configuration file mounted';
        default: return section.description;
      }
    }
    render() {
      this.content.replaceChildren();
      if (location.protocol === 'http:') {
        const confirmation = node('input', {type: 'checkbox', checked: this.acknowledgeHttp});
        confirmation.addEventListener('change', () => { this.acknowledgeHttp = confirmation.checked; });
        this.content.append(node('section', {class: 'cc-transport'}, node('h2', {}, 'Direct HTTP configuration'),
          node('p', {}, 'Setup works at this application address without a reverse proxy. HTTP does not encrypt credentials in transit; use this option only on a trusted network.'),
          node('label', {}, confirmation, 'Allow configuration over HTTP on this trusted network')));
      }
      if (this.view.pendingRestart.length) this.content.append(node('div', {class: 'cc-restart'}, 'Restart required to activate: ', this.view.pendingRestart.map(key => this.fields.get(key)?.definition.label || key).join(', '), '. Saved values survive container recreation when conf is persisted.'));
      const tabs = node('nav', {class: 'cc-tabs', role: 'tablist', 'aria-label': 'Configuration categories'});
      for (const category of this.view.categories) {
        const tab = node('button', {class: 'cc-tab', type: 'button', role: 'tab', 'aria-selected': category === this.category, tabindex: category === this.category ? '0' : '-1'}, category);
        tab.addEventListener('click', () => { this.category = category; this.render(); });
        tab.addEventListener('keydown', event => {
          if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
          event.preventDefault(); const index = this.view.categories.indexOf(category);
          this.category = this.view.categories[(index + (event.key === 'ArrowRight' ? 1 : -1) + this.view.categories.length) % this.view.categories.length];
          this.render(); this.content.querySelector('[aria-selected="true"]').focus();
        });
        tabs.append(tab);
      }
      this.content.append(tabs, node('div', {class: 'cc-category-heading'}, node('h2', {}, labels[this.category]), node('p', {}, 'Disabled features stay collapsed')));
      const sections = this.view.sections.filter(section => section.category === this.category);
      if (!this.open.has(this.category)) this.open.set(this.category, sections.find(section => this.enabled(section))?.id || '');
      const panel = node('div', {role: 'tabpanel', 'aria-label': this.category});
      sections.forEach(section => panel.append(this.card(section))); this.content.append(panel);
      const bottom = node('div', {class: 'cc-bottom'}, node('p', {class: 'cc-count'}), node('div', {class: 'cc-bottom-actions'}));
      this.reviewButton = node('button', {type: 'button', class: 'cc-button cc-primary'}, 'Review changes →');
      this.reviewButton.addEventListener('click', () => this.review());
      bottom.querySelector('.cc-bottom-actions').append(this.reviewButton);
      if (this.setup && this.view.canStart) {
        const start = node('button', {type: 'button', class: 'cc-button'}, 'Start application');
        start.addEventListener('click', () => this.start(start)); bottom.querySelector('.cc-bottom-actions').append(start);
      }
      this.content.append(bottom); this.count();
    }
    card(section) {
      const enabled = this.enabled(section);
      // Cookie and session limits also apply when forwarded-header trust is disabled.
      const editable = enabled || section.id === 'proxy';
      const expanded = editable && this.open.get(this.category) === section.id;
      const fields = this.sectionFields(section); const applyKinds = new Set(fields.map(field => field.definition.apply));
      const badge = applyKinds.size === 1 && applyKinds.has('live') ? 'Apply live' : applyKinds.size > 1 ? 'Live + restart' : 'Restart required';
      const card = node('section', {class: `cc-card${expanded ? ' cc-open' : ''}`, 'data-section': section.id});
      const header = node('div', {class: 'cc-card-heading'}, node('span', {class: 'cc-icon', 'aria-hidden': 'true'}, iconText[section.id] || '◇'), node('div', {class: 'cc-card-title'}, node('h3', {}, section.title), node('p', {class: 'cc-summary'}, this.summary(section))));
      if (fields.length) header.append(node('span', {class: `cc-badge${badge === 'Apply live' ? ' cc-live' : ''}`}, badge));
      if (section.id === 'database') {
        const external = databaseType(this.value('spring.datasource.url')) !== 'h2';
        const toggle = node('input', {type: 'checkbox', role: 'switch', checked: external, disabled: this.fields.get('spring.datasource.url').locked, 'aria-label': 'Use an external database'});
        toggle.addEventListener('change', () => { this.setDatabaseType(toggle.checked ? 'postgresql' : 'h2'); this.open.set(this.category, section.id); this.render(); });
        header.append(node('label', {class: 'cc-switch', title: 'Use an external database'}, toggle, node('span', {'aria-hidden': 'true'})));
      }
      if (section.enableKey) {
        const locked = this.fields.get(section.enableKey)?.locked;
        const toggleLabel = section.id === 'proxy' ? 'Trust forwarded headers' : section.title;
        const toggle = node('input', {type: 'checkbox', role: 'switch', checked: enabled, disabled: locked, 'aria-label': toggleLabel, title: locked ? 'Controlled by deployment configuration' : `Enable ${toggleLabel}`});
        toggle.addEventListener('change', () => {
          this.set(section.enableKey, toggle.checked ? section.enabledValue : section.disabledValue);
          if (toggle.checked) this.open.set(this.category, section.id); this.render();
        });
        header.append(node('label', {class: 'cc-switch'}, toggle, node('span', {'aria-hidden': 'true'})));
      }
      const expand = node('button', {type: 'button', class: 'cc-expand', 'aria-expanded': expanded, 'aria-label': `Edit ${section.title}`, disabled: !editable}, expanded ? '⌃' : '⌄');
      expand.addEventListener('click', () => { this.open.set(this.category, expanded ? '' : section.id); this.render(); });
      header.append(expand); card.append(header);
      if (!expanded) return card;
      const body = node('div', {class: 'cc-card-body'});
      if (section.id === 'database') this.databaseChoice(body);
      if (section.id === 'database' && this.view.databaseChoiceRequired) {
        const confirm = node('button', {type: 'button', class: 'cc-button'}, 'Review this database selection');
        confirm.addEventListener('click', () => { this.changes.set('spring.datasource.url', this.value('spring.datasource.url')); this.count(); this.review(); });
        body.append(node('p', {class: 'cc-restart'}, 'The previous external database configuration is missing. The application will not silently switch to H2. Restore the connection or explicitly review your database choice.'), confirm);
      }
      const basic = node('div', {class: 'cc-field-grid'}), advanced = node('div', {class: 'cc-field-grid'});
      if (section.id === 'clamd') this.connectionMethod(basic);
      for (const field of fields) {
        if (field.definition.key === section.enableKey && section.id !== 'proxy') continue;
        if (section.id === 'clamd' && field.definition.key === 'clamav.service.port' && this.value('clamav.service.host').startsWith('/')) continue;
        if (field.definition.key === 'spring.jpa.properties.hibernate.jdbc.lob.non_contextual_creation' && databaseType(this.value('spring.datasource.url')) !== 'postgresql') continue;
        if (field.definition.key === 'clamav.identity.ldap.max-nested-depth' && this.value('clamav.identity.ldap.nested-groups') !== 'true') continue;
        (field.definition.advanced ? advanced : basic).append(this.field(field));
      }
      if (basic.childNodes.length) body.append(basic);
      if (advanced.childNodes.length) {
        const details = node('details', {class: 'cc-advanced'}, node('summary', {}, 'Advanced settings'), advanced);
        details.open = this.advanced.has(section.id);
        details.addEventListener('toggle', () => { if (details.open) this.advanced.add(section.id); else this.advanced.delete(section.id); }); body.append(details);
      }
      body.append(node('p', {class: 'cc-note'}, section.description));
      const actions = node('div', {class: 'cc-card-actions'});
      if (['clamd', 'rest', 'database', 'ldap', 'oidc'].includes(section.id)) {
        const result = node('span', {class: 'cc-test-result', role: 'status'});
        const button = node('button', {type: 'button', class: 'cc-button'}, 'Test connection');
        button.addEventListener('click', async () => {
          button.disabled = true; result.classList.remove('cc-error', 'cc-test-deferred'); result.removeAttribute('data-test-status'); result.textContent = 'Testing…';
          try {
            const response = await this.api('test', this.draft(section.id)); result.textContent = response.message;
            result.dataset.testStatus = response.status || 'succeeded';
            result.classList.toggle('cc-test-deferred', response.status === 'not_initialized');
          }
          catch (error) { result.textContent = error.message; result.classList.add('cc-error'); }
          finally { button.disabled = false; }
        }); actions.append(button, result);
      }
      if (section.link && !this.setup) {
        const context = this.endpoint.replace(/\/admin\/configuration-center$/, '');
        actions.append(node('a', {class: 'cc-button', href: `${context}${section.link}`}, section.id === 'clamd' ? 'Manage all endpoints' : section.id === 'local' ? 'Users & roles' : 'Open detailed management'));
      }
      if (section.id === 'metrics') {
        const field = this.fields.get('clamav.metrics.scrape-token');
        const generate = node('button', {type: 'button', class: 'cc-button', disabled: field.locked}, 'Generate scrape token');
        generate.addEventListener('click', () => {
          const token = Array.from(crypto.getRandomValues(new Uint8Array(32)), byte => byte.toString(16).padStart(2, '0')).join('');
          this.set(field.definition.key, token);
          if (!this.fields.get('clamav.metrics.enabled').locked) this.set('clamav.metrics.enabled', 'true');
          this.render();
          const notice = node('div', {class: 'cc-note'}, node('p', {}, 'New draft token — copy it now. It is saved only after review and apply.'), node('code', {'data-i18n-ignore': ''}, token));
          this.content.querySelector('[data-section="metrics"] .cc-card-body').append(notice);
        });
        actions.append(generate);
      }
      if (['metrics', 'observer'].includes(section.id)) {
        const reset = node('button', {type: 'button', class: 'cc-button'}, 'Stage application defaults');
        reset.addEventListener('click', () => {
          for (const field of fields) {
            if (field.locked) continue;
            if (field.definition.secret) {
              this.changes.delete(field.definition.key);
              if (field.configured) this.clearSecrets.add(field.definition.key);
            } else this.set(field.definition.key, field.definition.defaultValue);
          }
          this.render(); this.show('Application defaults staged. Review and apply to save; deployment-controlled fields are retained.');
        });
        actions.append(reset);
      }
      if (section.id === 'oidc') {
        const context = this.endpoint.replace(/\/admin\/configuration-center$/, '').replace(/\/setup$/, '');
        const base = this.value('clamav.public-url') || location.origin + context;
        body.append(node('p', {class: 'cc-note'}, 'OAuth callback: ', node('code', {}, base.replace(/\/$/, '') + '/login/oauth2/code/clamav'), '. Register this exact HTTPS URL with the provider. Test connection checks issuer discovery; a real sign-in verifies the client secret. Unmapped groups receive no access.'));
      }
      if (section.id === 'system' && !this.setup) {
        actions.append(node('a', {class: 'cc-button', href: `${this.endpoint.replace(/\/admin\/configuration-center$/, '')}/setup/`}, 'Open protected recovery'));
      }
      if (actions.childNodes.length) body.append(actions);
      card.append(body); return card;
    }
    field(field) {
      const definition = field.definition, key = definition.key, id = `cc-${key.replace(/[^A-Za-z0-9_-]/g, '-')}`;
      const container = node('div', {class: 'cc-field', 'data-key': key});
      container.append(node('label', {for: id}, definition.label));
      let input;
      if (definition.type === 'boolean' || definition.type === 'select') {
        input = node('select', {id, disabled: field.locked});
        const options = definition.type === 'boolean' ? [['false', 'Disabled'], ['true', 'Enabled']] : definition.choices.map(value => [value, value || 'Automatic']);
        options.forEach(([value, label]) => input.append(node('option', {value}, label)));
        input.value = this.value(key);
      } else {
        input = node('input', {id, type: definition.secret ? 'password' : definition.type === 'number' ? 'number' : 'text', value: definition.secret ? this.changes.get(key) || '' : this.value(key), disabled: field.locked, autocomplete: definition.secret ? 'new-password' : 'off', spellcheck: 'false'});
        if (definition.type === 'number') { input.min = definition.minimum; input.max = definition.maximum; }
        if (definition.secret) input.placeholder = field.locked ? 'Controlled by deployment configuration' : field.configured ? 'Leave blank to retain the saved credential' : 'Not configured';
      }
      const event = input.tagName === 'SELECT' ? 'change' : 'input';
      input.addEventListener(event, () => { this.set(key, input.value); const card = container.closest('.cc-card'); const section = this.view.sections.find(entry => entry.id === definition.section); if (card) card.querySelector('.cc-summary').textContent = this.summary(section); if (key === 'clamav.identity.ldap.nested-groups') this.render(); });
      container.append(input, node('p', {class: `cc-field-source${field.locked ? ' cc-locked' : ''}`}, `${field.source}${field.pendingRestart ? ' · Restart pending' : ''}${definition.apply === 'restart' ? ' · Restart required' : ''}`));
      if (definition.secret) container.append(node('p', {class: 'cc-help'}, field.configured ? 'A credential is configured. Leave blank to retain it.' : 'No credential is configured.'));
      if (definition.hint) container.append(node('p', {class: 'cc-help'}, definition.hint));
      if (field.locked) container.append(node('p', {class: 'cc-help'}, `Change the deployment override and recreate the container to edit here. ${definition.environment[0] || key}`));
      if (definition.secret && !field.locked && field.configured) {
        const clear = node('input', {type: 'checkbox', checked: this.clearSecrets.has(key), 'aria-label': `Clear ${definition.label}`});
        clear.addEventListener('change', () => { this.changes.delete(key); input.value = ''; if (clear.checked) this.clearSecrets.add(key); else this.clearSecrets.delete(key); this.count(); });
        container.append(node('label', {class: 'cc-clear'}, clear, 'Clear the saved credential (review before applying)'));
      }
      return container;
    }
    connectionMethod(body) {
      const select = node('select', {'aria-label': 'Connection method', disabled: this.fields.get('clamav.service.host').locked}, node('option', {value: 'tcp'}, 'TCP / remote daemon'), node('option', {value: 'unix'}, 'Unix socket'));
      select.value = this.value('clamav.service.host').startsWith('/') ? 'unix' : 'tcp';
      select.addEventListener('change', () => { this.set('clamav.service.host', select.value === 'unix' ? '/var/run/clamav/clamd.ctl' : 'localhost'); this.render(); });
      body.append(node('div', {class: 'cc-helper-controls'}, node('label', {}, 'Connection method'), select));
    }
    databaseChoice(body) {
      const select = node('select', {'aria-label': 'Database type', disabled: this.fields.get('spring.datasource.url').locked});
      [['h2', 'Built-in H2 · single instance'], ['postgresql', 'PostgreSQL'], ['mysql', 'MySQL'], ['mariadb', 'MariaDB']].forEach(([value, label]) => select.append(node('option', {value}, label)));
      select.value = databaseType(this.value('spring.datasource.url'));
      select.addEventListener('change', () => { this.setDatabaseType(select.value); this.render(); });
      body.append(node('div', {class: 'cc-helper-controls'}, node('label', {}, 'Database type'), select));
    }
    setDatabaseType(type) {
      const urls = {h2: 'jdbc:h2:file:./data/clamav-web-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE', postgresql: 'jdbc:postgresql://db:5432/appdb', mysql: 'jdbc:mysql://db:3306/appdb', mariadb: 'jdbc:mariadb://db:3306/appdb'};
      this.set('spring.datasource.url', urls[type]);
      if (!this.fields.get('spring.datasource.username').locked) this.set('spring.datasource.username', type === 'h2' ? 'sa' : 'clamav');
      if (!this.fields.get('spring.datasource.driver-class-name').locked) this.set('spring.datasource.driver-class-name', '');
    }
    count() {
      const total = this.changes.size + this.clearSecrets.size;
      const counter = this.content.querySelector('.cc-count');
      if (counter) counter.textContent = total ? `${total} pending change${total === 1 ? '' : 's'}` : 'No pending changes';
      if (this.reviewButton) {
        this.reviewButton.disabled = this.busy || (!total && !(this.setup && !this.view.setupComplete));
        this.reviewButton.textContent = this.setup && !this.view.setupComplete && !total ? 'Review initial configuration →' : 'Review changes →';
      }
    }
    async review() {
      this.busy = true; this.count();
      try { this.dialog(await this.api('review', this.draft())); }
      catch (error) { this.show(error.message, true); }
      finally { this.busy = false; this.count(); }
    }
    dialog(review) {
      const dialog = node('dialog', {class: 'configuration-center cc-review', 'aria-label': 'Review configuration changes'}, node('h2', {}, 'Review changes'), node('p', {class: 'cc-help'}, 'Only the changes below will be saved. Secret values are masked in this review.'));
      const table = node('table', {}, node('thead', {}, node('tr', {}, ...['Setting', 'Current', 'After applying', 'Activation'].map(title => node('th', {}, title)))));
      const rows = node('tbody'); review.changes.forEach(change => rows.append(node('tr', {}, node('td', {}, change.label), node('td', {}, change.before || '—'), node('td', {}, change.after || '—'), node('td', {}, change.apply === 'live' ? 'Apply live' : 'Restart required')))); table.append(rows); dialog.append(table);
      let acknowledge, username, password;
      if (review.databaseChange) {
        acknowledge = node('input', {type: 'checkbox'});
        dialog.append(node('p', {class: 'cc-note'}, 'Changing this connection does not copy accounts, jobs or audit history. A different database can appear empty. Review legacy baselining separately.'), node('label', {class: 'cc-acknowledge'}, acknowledge, 'I have backed up the existing database and understand this changes the connection without migrating data.'));
      }
      if (review.sensitive) {
        username = node('input', {type: 'text', autocomplete: 'username', 'aria-label': 'Local administrator username', value: 'admin'});
        password = node('input', {type: 'password', autocomplete: 'current-password', 'aria-label': 'Local administrator password'});
        dialog.append(node('p', {class: 'cc-note'}, 'Verify with an enabled local recovery administrator before changing sensitive settings.'), node('div', {class: 'cc-field-grid'}, node('div', {class: 'cc-field'}, node('label', {}, 'Local administrator'), username), node('div', {class: 'cc-field'}, node('label', {}, 'Administrator password'), password)));
      }
      const status = node('p', {class: 'cc-help', role: 'status'}); dialog.append(status);
      const cancel = node('button', {class: 'cc-button', type: 'button'}, 'Back to editing');
      const apply = node('button', {class: 'cc-button cc-primary', type: 'button'}, 'Apply reviewed changes');
      const close = () => { if (typeof dialog.close === 'function') dialog.close(); dialog.remove(); };
      cancel.addEventListener('click', close);
      apply.addEventListener('click', async () => {
        if (acknowledge && !acknowledge.checked) { status.textContent = 'Confirm the database backup and connection change first.'; return; }
        if (review.sensitive && (!password.value || !username.value)) { status.textContent = 'Enter the local administrator credentials.'; return; }
        apply.disabled = true;
        try {
          const response = await this.api('apply', {reviewId: review.reviewId, acknowledgeDatabase: acknowledge?.checked || false, verificationUsername: username?.value || '', verificationPassword: password?.value || '', acknowledgeHttp: this.acknowledgeHttp});
          this.changes.clear(); this.clearSecrets.clear(); close(); this.setView(response.view); this.show(response.message);
        } catch (error) { status.textContent = error.message; apply.disabled = false; }
        finally { if (password) password.value = ''; }
      });
      dialog.append(node('div', {class: 'cc-review-actions'}, cancel, apply)); document.body.append(dialog);
      if (typeof dialog.showModal === 'function') dialog.showModal(); else dialog.setAttribute('open', '');
      dialog.addEventListener('close', () => dialog.remove(), {once: true});
    }
    async start(button) {
      if (this.changes.size || this.clearSecrets.size) { this.show('Review and save your pending changes before starting.', true); return; }
      button.disabled = true;
      try {
        const result = await this.api('start'); this.show(result.message);
        const prefix = this.endpoint.replace(/\/setup$/, '');
        for (let attempt = 0; attempt < 60 && this.root.isConnected; attempt += 1) {
          await new Promise(resolve => setTimeout(resolve, 3000));
          try {
            const response = await fetch(`${prefix}/login`, {cache: 'no-store', redirect: 'manual'});
            if (response.status === 200 && response.headers.get('content-type')?.includes('text/html') && (await response.text()).includes('name="username"')) { location.assign(`${prefix}/login`); return; }
          } catch (_) { /* Startup may temporarily close the listener. */ }
        }
        this.show('The application is still initializing. Check its container logs, then open the login page.', true);
      } catch (error) { this.show(error.message, true); button.disabled = false; }
    }
  }
  function lockLegacyForms() {
    document.querySelectorAll('.auth-provider-panel[data-locked-fields]').forEach(form => {
      if (form.dataset.locksApplied) return;
      form.dataset.locksApplied = 'true';
      const managed = form.dataset.centerManaged === 'true';
      const fields = new Set(form.dataset.lockedFields.split(','));
      form.querySelectorAll('input[name],select[name]').forEach(input => {
        if (!fields.has(input.name) || input.name === '_csrf') return;
        if (input.type !== 'password') {
          const hidden = document.createElement('input'); hidden.type = 'hidden'; hidden.name = input.name;
          hidden.value = input.type === 'checkbox' ? String(input.checked) : input.value;
          input.after(hidden);
        }
        input.disabled = true; input.title = managed ? 'Managed in Configuration Center' : 'Controlled by deployment configuration';
      });
      if (managed) form.querySelectorAll('button[type="submit"]').forEach(button => { button.disabled = true; button.title = 'Manage this provider in Configuration Center'; });
    });
  }
  function mount() { lockLegacyForms(); document.querySelectorAll('.configuration-center[data-endpoint]').forEach(root => { if (!instances.has(root)) instances.set(root, new Center(root)); }); }
  window.ClamavConfigurationCenter = {mount, databaseType};
  document.addEventListener('DOMContentLoaded', mount);
  document.addEventListener('clamav:page-ready', mount);
  if (document.readyState !== 'loading') mount();
})();
