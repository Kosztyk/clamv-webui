# Phase 1 header restoration

This revision changes only the shared application header markup, its focused CSS, and one rendering regression test. It keeps the Phase 0/Phase 1 layout, security, REST monitor, API tokens, native ClamdTop, Discord webhook settings and Docker Compose unchanged.

The restored right side follows the provided reference: English/Українська language selector (existing `/setLocale` session route), `User: <username>`, `Dark Theme` label and accessible switch (existing `themeToggle` JavaScript/localStorage), and `Logout` action with CSRF. The current locale display is driven from the server's session locale. Changing language redirects to the dashboard, preserving the application's existing locale-switch route. Full i18n coverage of new Phase 0/Phase 1 text is outside the scope of this cosmetic fix.

## Verification

Run `mvn -B verify` or `docker compose build --no-cache clamav-web-client` on a Maven/Docker capable machine. Use the dashboard to verify the four header elements and test the theme switch across page navigation, logout, and both language choices.
