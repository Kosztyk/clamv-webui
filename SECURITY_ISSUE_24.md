# Issue #24 — scan path validation must not disclose target existence

This complete source retains fixes #13–#23. Untrusted path submissions are
screened lexically against trusted configured/canonical roots before resolving
the target. A final canonical containment check still blocks symlink escapes.
Missing, inaccessible and forbidden paths use one safe shared-validator message,
without a rejected path or filesystem exception cause.

UI and legacy/v1 error handling remains generic. Restricted Bearer path failures
now uniformly return 403, including missing targets, so a token cannot probe
existence in another globally permitted directory. Configured root aliases and
permitted in-root aliases remain supported; arbitrary outside aliases are denied.
Canonical job pinning and execution-time checks from #21 are retained.

Rebuild/recreate the application. No migration or new setting is required.
See [validation order, response behavior and compatibility](docs/SCAN_PATH_PRIVACY.md)
and [current verification](docs/security/path-privacy-validation.json).
