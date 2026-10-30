# Restored Discord webhook configuration and ClamdTop page-wide mode

## GUI webhook

Admin > Settings now exposes **Webhook notifications** with enabled/disabled selection and a write-only **Webhook URL** replacement input. The existing configuration keys are still `app.webhook.enabled` and `app.webhook.url`, persisted to `./conf/clamav-web-client.properties` through the existing SettingsService. Existing values are preserved when the URL field is left empty. Checking **Remove saved webhook URL** clears and disables it. An invalid URL or attempting to enable an empty URL is rejected without persisting other settings.

The URL is never embedded into templates or into this source archive. Restrict read access to `./conf/clamav-web-client.properties`; avoid committing it to Git. Discord webhook URLs are credential-bearing. Rotate any webhook URL previously pasted into a shared channel.

This notification applies to **direct GUI scan jobs**. External REST API scans are processed by the separate REST server. Configure or select a webhook at that server and choose it from API Scan's webhook dropdown. Saving the GUI webhook does not automatically register it on the external REST server.

## ClamdTop terminal grid fitting (corrected)

The previous full-screen/sidebar-overlay change was not the requested fix and has been
reverted. The standard Phase 0 navigation/sidebar remains on ClamdTop as elsewhere.

The actual problem was the black, unused strip **inside** the terminal on the right.
The native terminal initially estimated cols and rows using fixed pixels-per-cell
constants before opening xterm. It now opens the real xterm renderer first,
measures the real browser font cell width and height, subtracts container padding
and scrollbar space, resizes the xterm grid, then starts ncurses with those exact
cols and rows via the existing WebSocket query.

The resize observer repeats this calculation after layout changes and reconnects
only when rows/cols actually change, since the native PTY wrapper fixes its size
at process startup. An explicit Disconnect remains disconnected until Connect.

No REST scan, report, webhook, authentication, or scanning logic was changed.

## Acceptance checks

1. After backup of existing `./data` and `./conf`, rebuild locally with `docker compose build --no-cache clamav-web-client`.
2. Admin > Settings: confirm **Webhook notifications** appears and saved state is shown, without the token displayed. Enter a newly rotated URL, enable it, save, then revisit Settings to confirm enabled and saved.
3. Perform a direct GUI scan (clean and EICAR test); check Discord notifications. The external REST API webhook setup is a separate feature.
4. Open ClamdTop: confirm the native ncurses colored area fills the terminal panel horizontally, without a wide unused black strip on the right. Standard sidebar navigation should remain visible. Verify the interactive keyboard and Connect/Disconnect controls.
5. On a reverse proxy, ensure WebSocket upgrade requests to `/ws/clamdtop` are forwarded.

Maven, Docker and live Discord delivery were not runnable in the artifact authoring environment; these checks are required on the user's test server.
