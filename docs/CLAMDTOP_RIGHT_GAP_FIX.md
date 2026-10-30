# ClamdTop right-side black-strip fix

The requested issue is the unused black strip **inside** the xterm panel to the
right of the rendered native clamdtop ncurses screen, not the main application
sidebar or global app shell. Previous page-wide/overlay navigation mode is
reverted.

## Root cause

`native-clamdtop.js` derived PTY columns using a hard-coded 8.4 px character
width, which did not match the browser renderer's actual monospace font metrics.
The PTY was started with the wrong number of columns, so ncurses colored only
part of the available terminal width.

## Correction

- Open terminal first; measure actual **rendered cell width** from the
  `.xterm-screen` canvas and rendered terminal column count; fall back to
  `term.charMeasure` when no renderer metrics are available.
- Compute available space from the terminal element's content box and scrollbar.
- Resize xterm before the WebSocket connects, sending exactly the same columns
  and rows to the native clamdtop PTY at startup.
- Observe terminal container and viewport resize and reconnect only when needed.
- Preserve normal sidebar/navigation; no overlay/menu.
- Keep `app.webhook.*`, REST scan/report adapters, and database settings unchanged.

## Verification

1. Open ClamdTop in the browser at your regular desktop resolution.
2. Confirm the colored ncurses screen extends to near the right edge of its
   surrounding terminal panel (at most one glyph + scrollbar space).
3. Change browser width; terminal should reconnect at the new width without a
   persistent black strip.
4. Confirm Connect/Disconnect, terminal typing, REST queue and Discord webhook
   settings continue to work.

Maven, Docker runtime and a live remote clamd session were not available in the
artifact-authoring environment. The actual PTY rendering must be confirmed on
the deployment host.

## Browser validation

A Chromium headless test of the bundled xterm renderer (using a mock WebSocket)
validated terminal grid sizing at 800px, 1120px, and 1440px wide. The remaining
non-grid gap was at most one text cell plus the normal 8px container padding.
The native clamdtop executable and remote daemon were not part of this browser test.
