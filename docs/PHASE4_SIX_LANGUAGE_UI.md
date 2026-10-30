# ClamAV Web UI v2.0.0 — Six-language UI

## Supported languages

| Code | Language | Menu label |
|---|---|---|
| `en` | English (default) | English |
| `uk` | Ukrainian | Українська |
| `ro` | Romanian | Română |
| `fr` | French | Français |
| `es` | Spanish | Español |
| `de` | German | Deutsch |

Choose the language from the header dropdown on any authenticated page or from the language links on the login page. Switching reloads the current page in the chosen language. The `clamav_lang` cookie retains the selection for up to 365 days, including across logout and browser restarts. A new browser without this cookie defaults to English, regardless of its `Accept-Language` header.

The `/setLocale` endpoint is accessible before login and validates the requested language against the six permitted codes. Its `returnTo` argument accepts **only local application paths**; external redirect targets are rejected. The selected language does not modify authentication, group-to-role mappings, password handling, configuration or database records.

## Scope and implementation

- Localized: sidebar, header, login/logout, dashboards, endpoint health, ClamdTop control labels, scanning forms, jobs, API scan/report controls, admin settings, webhook settings, endpoints, users, API token controls, LDAP/OIDC administration and Phase 4 configuration observer.
- Shared translation catalog: `src/main/resources/static/js/i18n-catalog.js` (328 UI strings in each of five additional languages). English is the authored template fallback.
- Existing message bundles: `messages.properties` and `messages_uk.properties` are retained and new `messages_ro.properties`, `messages_fr.properties`, `messages_es.properties`, `messages_de.properties` translate the legacy service messages.
- UI translation runtime: `src/main/resources/static/js/i18n.js`. It works after PJAX changes and for new asynchronous status elements, and translates placeholders, titles and accessibility labels where cataloged.
- The user-entered and technical values are **not translated**: login names, scan paths, LDAP distinguished names and filters, tokens, passwords, endpoint addresses, ClamdTop terminal output, raw REST API JSON, ClamAV signature names, protocol identifiers, command-line instructions, and audit record payloads.
- Some unusual or dynamically assembled server diagnostics may still appear in English. Do not translate raw external-service error content indiscriminately: it could conceal important technical identifiers.
- The change has no new Flyway migrations; it does not rewrite the existing authentication implementation or scanning pipeline.

## Build and test

From the source directory:

```bash
python3 scripts/check-i18n.py            # Python 3 + Node; Chromium checks optional with playwright
mvn -B -ntp verify                         # actual project compile/tests (required before deploy)
docker compose build --no-cache clamav-web-client
```

Run the UI manually in all six languages: start from `/login`, select each language, log in, follow ordinary and PJAX navigation through Dashboard, Scan, API Reports, Authentication and Configuration, observe a live status update, log out and log back in. Verify that the saved language persists and does not affect role assignments or entered credentials. Refresh and restart the container, then repeat. Confirm `en` is the default in a fresh browser profile.

**Verification performed in the authoring environment:** browser tests for six locales, dynamically inserted elements and protected inputs; Java syntax compilation of changed classes against isolated stubs; JavaScript syntax checks; complete catalog shape checks. **Not performed:** full Maven build, actual HTTP `/setLocale` integration, LDAP/OIDC sessions and live ClamdTop on your Docker host. These still require release gating on the server.
