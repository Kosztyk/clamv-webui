# Phase 1: right-hand header retained; native ClamdTop panel flush fit

## User-requested layout

The header's right side retains this precise sequence in the shared Thymeleaf
shell: **language selector**, **User: username**, **Dark Theme** toggle, and
**Logout**. The label says `Dark Theme`, not `Theme`; the existing working toggle,
session locale route, username binding and CSRF-protected logout are unchanged.

## Native ClamdTop black border regression

The black area in the screenshots was **inside the ClamdTop terminal panel**, not
the application's sidebar. No fullscreen/overlay/sidebar changes are made.

Two independent problems created visible black strips:

1. `#native-clamdtop-terminal` added an 8px black padding, a contrasting border,
   and a black background (`#03060c`) around the native ncurses content.
   ClamdTop itself renders ANSI color 0 as `#2e3436`. We removed the padding,
   border, and rounding and made the xterm default background and viewport
   match the ncurses color, so sub-cell remainders are visually seamless.
2. The previous fit algorithm calculated cell width by dividing `.xterm-screen`
   width by initial terminal columns. **The vendored xterm implementation adds
   `viewport.scrollBarWidth` to `.xterm-screen` width.** The result therefore
   overestimated the real glyph width and allocated too few columns, creating
   a widening black strip. The new calculation uses the renderer's actual
   painted cell width, with the canvas width as fallback. It also hides the
   scrollback gutter in this alternate-screen ncurses terminal.

The existing authenticated PTY/WebSocket process, ClamdTop keyboard controls,
connection buttons, external REST monitor, dashboard, tokens, Discord settings,
and Docker configuration have not been modified by this revision.

## Verification carried out

- Real headless Chromium, local mock WebSocket, using the *bundled old xterm
  renderer*, viewport widths 750/1000/1450/1850 px at DPR 1 and DPR 2.
- Painted canvas gap is <= 8px (one native glyph cell), with background
  `rgb(46, 52, 54)` continuous to the panel edge; padding and border are 0.
- Viewport resize updates the terminal grid and WebSocket dimensions, but does
  **not** reconnect a manually disconnected session.
- The header markup is covered by `UiSmokeTest.originalTopbarControlsRenderWithDarkThemeLabel`.
- Run the optional browser regression as:
  `python scripts/test-native-clamdtop-browser.py`
- The Phase 1 static checks and JS syntax validation were run locally.

**Not verified here:** the Maven/JUnit suite, actual Docker image startup,
remote clamd connection and true ncurses rendering against a daemon. Run the
host-side build and inspect the native terminal at your browser resolution.
